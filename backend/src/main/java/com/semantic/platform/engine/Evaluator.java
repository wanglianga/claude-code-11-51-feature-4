package com.semantic.platform.engine;

import com.semantic.platform.dsl.Expr;
import com.semantic.platform.dsl.Filter;
import com.semantic.platform.source.OrderFact;
import com.semantic.platform.source.RefundFact;
import com.semantic.platform.source.StoreStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 表达式求值器：把 AST 求值为 Series（cellKey -> value）。
 * 处理：记录级过滤、分组聚合、四则组合、引用、币种换算、覆盖范围与算式追踪。
 */
public class Evaluator {

    private final EvaluationContext ctx;

    public Evaluator(EvaluationContext ctx) {
        this.ctx = ctx;
    }

    public Series eval(Expr e) {
        if (e instanceof Expr.Agg a) return evalAgg(a);
        if (e instanceof Expr.Arith a) return evalArith(a);
        if (e instanceof Expr.Ref r) return evalRef(r);
        if (e instanceof Expr.Convert c) return evalConvert(c);
        if (e instanceof Expr.Cohort c) return evalCohort(c);
        if (e instanceof Expr.If i) return evalIf(i);
        if (e instanceof Expr.Const c) return evalConst(c);
        if (e instanceof Expr.Field f) {
            throw new IllegalStateException("字段必须置于聚合 agg 内使用: " + f.source() + "." + f.field());
        }
        throw new IllegalStateException("未知表达式节点: " + e);
    }

    // ---------------- 聚合 ----------------
    private Series evalAgg(Expr.Agg a) {
        // 被聚合表达式必须是字段（受控 DSL，不支持下钻到行级表达式）
        if (!(a.expr() instanceof Expr.Field f)) {
            throw new IllegalStateException("agg.expr 目前仅支持源字段");
        }
        String source = f.source();
        String field = f.field();
        String measure = a.measure();
        List<String> by = a.by() == null ? List.of() : a.by();

        Series out = new Series();
        // 单元格累加器
        Map<CellKey, AggAcc> acc = new HashMap<>();
        // 先按记录聚合
        for (Object[] rec : recordsOf(source)) {
            String storeId = (String) rec[COL_STORE];
            LocalDate date = (LocalDate) rec[COL_DATE];
            if (date == null || date.isBefore(ctx.from) || date.isAfter(ctx.to)) continue;
            // 过滤
            FilterResult fr = passesFilters(a.filter(), source, rec);
            String period = periodKey(date, ctx.grain);
            Map<String, String> dims = dimsOf(by, source, rec);
            CellKey key = CellKey.of(period, dims);
            AggAcc cellAcc = acc.computeIfAbsent(key, k -> new AggAcc());
            BigDecimal fieldVal = numericField(source, field, rec);
            String recId = recordId(source, rec);
            if (!fr.pass) {
                cellAcc.excluded.add(new CellValue.Lineage(recordType(source), recId, fieldVal, false, fr.reason));
                continue;
            }
            cellAcc.include(recordType(source), recId, fieldVal, measure);
        }

        // 补齐“已覆盖但无记录”的宇宙单元格（仅对含 store 维度或全量的情况）
        fillUniverseCells(acc, source, by);

        // 计算每个单元格的值与覆盖状态
        for (Map.Entry<CellKey, AggAcc> en : acc.entrySet()) {
            CellKey key = en.getKey();
            AggAcc c = en.getValue();
            CellValue cv = new CellValue();
            // 覆盖状态
            Coverage cov = coverageOf(source, key);
            cv.setStatus(cov.status);
            cv.setMessage(cov.message);
            if (cov.status == CellValue.Status.NOT_COVERED) {
                cv.setTrace(measure + "(" + source + "." + field + ") [数据未覆盖]");
                out.put(key, cv);
                continue;
            }
            // 计算值
            switch (measure) {
                case "sum" -> {
                    cv.setNum(c.sum);
                    cv.setTrace("SUM(" + source + "." + field + ")=" + c.sum);
                }
                case "count" -> {
                    cv.setNum(BigDecimal.valueOf(c.count));
                    cv.setTrace("COUNT(" + source + ")=" + c.count);
                }
                case "count_distinct" -> {
                    cv.setNum(BigDecimal.valueOf(c.distinct.size()));
                    cv.setTrace("COUNT_DISTINCT(" + source + "." + field + ")=" + c.distinct.size());
                }
                case "avg" -> {
                    if (c.count == 0) {
                        cv.setStatus(CellValue.Status.NO_DATA);
                        cv.setMessage("无记录，无法求平均");
                    } else {
                        cv.setNum(c.sum);
                        cv.setDen(BigDecimal.valueOf(c.count));
                        cv.setTrace("AVG(" + source + "." + field + ")=" + c.sum + "/" + c.count);
                    }
                }
                case "min" -> { cv.setNum(c.min); cv.setTrace("MIN(" + source + "." + field + ")=" + c.min); }
                case "max" -> { cv.setNum(c.max); cv.setTrace("MAX(" + source + "." + field + ")=" + c.max); }
                default -> throw new IllegalStateException("未知聚合: " + measure);
            }
            // 部分覆盖标记（不覆盖值，但提示不完整）
            if (cov.status == CellValue.Status.PARTIAL) {
                cv.setStatus(CellValue.Status.PARTIAL);
            }
            cv.addAllLineage(c.included);
            cv.addAllLineage(c.excluded);
            out.put(key, cv);
        }
        return out;
    }

    /** 单元格覆盖状态：含 store 维度时看该门店该期，否则看全部门店。 */
    private Coverage coverageOf(String source, CellKey key) {
        String storeId = key.dims().get("store_id");
        LocalDate periodStart = periodStart(key.periodKey(), ctx.grain);
        LocalDate periodEnd = periodEnd(key.periodKey(), ctx.grain);
        int totalDays = 0, coveredDays = 0;
        if (storeId != null) {
            for (LocalDate d = periodStart; !d.isAfter(periodEnd); d = d.plusDays(1)) {
                totalDays++;
                if (ctx.isCovered(source, storeId, d) || ctx.isStoreClosed(storeId, d)) coveredDays++;
            }
        } else {
            // 全量：要求范围内所有门店都覆盖
            Set<String> stores = ctx.allStores();
            if (stores.isEmpty()) return new Coverage(CellValue.Status.NOT_COVERED, "无门店数据");
            for (LocalDate d = periodStart; !d.isAfter(periodEnd); d = d.plusDays(1)) {
                for (String s : stores) {
                    totalDays++;
                    if (ctx.isCovered(source, s, d) || ctx.isStoreClosed(s, d)) coveredDays++;
                }
            }
        }
        if (totalDays == 0) return new Coverage(CellValue.Status.NOT_COVERED, "期间无营业日");
        if (coveredDays == 0) return new Coverage(CellValue.Status.NOT_COVERED, "数据未覆盖（批次未到）");
        if (coveredDays < totalDays) {
            return new Coverage(CellValue.Status.PARTIAL, "部分覆盖 " + coveredDays + "/" + totalDays + " 天，未到数据未计入");
        }
        return new Coverage(CellValue.Status.OK, null);
    }

    private record Coverage(CellValue.Status status, String message) {}

    /** 补齐宇宙单元格：让“已覆盖但无记录”的期间/门店也有单元格（值为 0 或 NO_DATA）。 */
    private void fillUniverseCells(Map<CellKey, AggAcc> acc, String source, List<String> by) {
        boolean hasStoreDim = by.contains("store_id");
        Set<String> stores = hasStoreDim ? ctx.allStores() : Set.of();
        for (LocalDate d = ctx.from; !d.isAfter(ctx.to); d = d.plusDays(1)) {
            String period = periodKey(d, ctx.grain);
            if (hasStoreDim) {
                for (String s : stores) {
                    CellKey key = CellKey.of(period, Map.of("store_id", s));
                    acc.computeIfAbsent(key, k -> new AggAcc());
                }
            } else {
                CellKey key = CellKey.of(period, Map.of());
                acc.computeIfAbsent(key, k -> new AggAcc());
            }
        }
    }

    // ---------------- 四则运算 ----------------
    private Series evalArith(Expr.Arith a) {
        // 常量广播：一侧是 const 时，按另一侧单元格逐一运算
        if (a.left() instanceof Expr.Const lc) return evalArithWithConst(a.operator(), constSeries(lc), eval(a.right()), true);
        if (a.right() instanceof Expr.Const rc) return evalArithWithConst(a.operator(), eval(a.left()), constSeries(rc), false);
        Series l = eval(a.left());
        Series r = eval(a.right());
        return combine(l, r, a.operator());
    }

    private Series constSeries(Expr.Const c) {
        Series s = new Series();
        CellValue cv = CellValue.of(BigDecimal.valueOf(c.value()));
        cv.setTrace(String.valueOf(c.value()));
        s.put(CellKey.of("*", Map.of()), cv);
        return s;
    }

    private Series evalArithWithConst(String op, Series l, Series r, boolean constOnLeft) {
        Series out = new Series();
        CellValue constVal = (constOnLeft ? l : r).cells().values().iterator().next();
        Series seriesSide = constOnLeft ? r : l;
        BigDecimal k = constVal.getNum();
        String ktr = constVal.getTrace();
        String opSym = switch (op) { case "add" -> "+"; case "sub" -> "-"; case "mul" -> "*"; case "div" -> "/"; default -> "?"; };
        for (Map.Entry<CellKey, CellValue> en : seriesSide.cells().entrySet()) {
            CellValue sv = en.getValue();
            CellValue cv = new CellValue();
            cv.setStatus(sv.getStatus());
            cv.setMessage(sv.getMessage());
            BigDecimal svNum = nvl(sv.getNum());
            BigDecimal ln = constOnLeft ? k : svNum;
            BigDecimal rn = constOnLeft ? svNum : k;
            switch (op) {
                case "add" -> cv.setNum(ln.add(rn));
                case "sub" -> cv.setNum(ln.subtract(rn));
                case "mul" -> cv.setNum(ln.multiply(rn));
                case "div" -> {
                    cv.setNum(ln); cv.setDen(rn);
                    if (rn.compareTo(BigDecimal.ZERO) == 0) { cv.setStatus(CellValue.Status.DIV_ZERO); cv.setMessage("分母为零"); }
                }
            }
            String ltr = constOnLeft ? ktr : sv.getTrace();
            String rtr = constOnLeft ? sv.getTrace() : ktr;
            cv.setTrace("(" + ltr + ") " + opSym + " (" + rtr + ")");
            cv.addAllLineage(sv.getLineage());
            out.put(en.getKey(), cv);
        }
        return out;
    }

    private Series combine(Series l, Series r, String operator) {
        Series out = new Series();
        Set<CellKey> allCells = new HashSet<>();
        allCells.addAll(l.cells().keySet());
        allCells.addAll(r.cells().keySet());
        String opSym = switch (operator) {
            case "add" -> "+"; case "sub" -> "-"; case "mul" -> "*"; case "div" -> "/";
            default -> "?";
        };
        for (CellKey key : allCells) {
            CellValue lv = l.get(key);
            CellValue rv = r.get(key);
            CellValue cv = new CellValue();
            CellValue.Status st = combineStatus(lv, rv);
            cv.setStatus(st);
            if (st == CellValue.Status.NOT_COVERED) {
                cv.setMessage("操作数数据未覆盖");
                out.put(key, cv);
                continue;
            }
            BigDecimal ln = lv == null ? BigDecimal.ZERO : nvl(lv.getNum());
            BigDecimal rn = rv == null ? BigDecimal.ZERO : nvl(rv.getNum());
            String ltr = lv == null ? "0" : (lv.getTrace() == null ? ln.toString() : lv.getTrace());
            String rtr = rv == null ? "0" : (rv.getTrace() == null ? rn.toString() : rv.getTrace());
            switch (operator) {
                case "add" -> cv.setNum(ln.add(rn));
                case "sub" -> cv.setNum(ln.subtract(rn));
                case "mul" -> cv.setNum(ln.multiply(rn));
                case "div" -> {
                    cv.setNum(ln);
                    cv.setDen(rn);
                    if (rn.compareTo(BigDecimal.ZERO) == 0) {
                        cv.setStatus(CellValue.Status.DIV_ZERO);
                        cv.setMessage("分母为零");
                    }
                }
                default -> throw new IllegalStateException("未知运算符: " + operator);
            }
            cv.setTrace("(" + ltr + ") " + opSym + " (" + rtr + ")");
            if (lv != null) cv.addAllLineage(lv.getLineage());
            if (rv != null) cv.addAllLineage(rv.getLineage());
            out.put(key, cv);
        }
        return out;
    }

    private CellValue.Status combineStatus(CellValue l, CellValue r) {
        CellValue.Status a = l == null ? CellValue.Status.OK : l.getStatus();
        CellValue.Status b = r == null ? CellValue.Status.OK : r.getStatus();
        if (a == CellValue.Status.NOT_COVERED || b == CellValue.Status.NOT_COVERED) return CellValue.Status.NOT_COVERED;
        if (a == CellValue.Status.PARTIAL || b == CellValue.Status.PARTIAL) return CellValue.Status.PARTIAL;
        if (a == CellValue.Status.NO_DATA || b == CellValue.Status.NO_DATA) return CellValue.Status.NO_DATA;
        return CellValue.Status.OK;
    }

    // ---------------- 引用 ----------------
    private Series evalRef(Expr.Ref r) {
        Series cached = ctx.refCache.get(r.metric());
        if (cached != null) return cached;
        Expr def = ctx.refResolver.apply(r.metric());
        if (def == null) throw new IllegalStateException("引用的指标未发布: " + r.metric());
        Series result = eval(def);
        ctx.refCache.put(r.metric(), result);
        return result;
    }

    // ---------------- 币种换算 ----------------
    private Series evalConvert(Expr.Convert c) {
        Series inner = eval(c.expr());
        Series out = new Series();
        BigDecimal rate = BigDecimal.valueOf(c.rate());
        for (Map.Entry<CellKey, CellValue> en : inner.cells().entrySet()) {
            CellValue iv = en.getValue();
            CellValue cv = new CellValue();
            cv.setStatus(iv.getStatus());
            cv.setMessage(iv.getMessage());
            if (iv.getNum() != null) cv.setNum(iv.getNum().multiply(rate).setScale(4, RoundingMode.HALF_UP));
            if (iv.getDen() != null) cv.setDen(iv.getDen().multiply(rate).setScale(4, RoundingMode.HALF_UP));
            cv.setTrace("CONVERT(" + iv.getTrace() + ", " + c.fromCurrency() + "->" + c.toCurrency() + "@" + c.rate() + ")");
            cv.addAllLineage(iv.getLineage());
            out.put(en.getKey(), cv);
        }
        return out;
    }

    // ---------------- 条件 ----------------
    private Series evalIf(Expr.If i) {
        Series cond = eval(i.cond());
        Series then = eval(i.then());
        Series other = eval(i.otherwise());
        Series out = new Series();
        Set<CellKey> allCells = new HashSet<>();
        allCells.addAll(then.cells().keySet());
        allCells.addAll(other.cells().keySet());
        for (CellKey key : allCells) {
            CellValue condV = cond.get(key);
            boolean truth = condV != null && condV.getNum() != null && condV.getNum().compareTo(BigDecimal.ZERO) != 0;
            CellValue chosen = truth ? then.get(key) : other.get(key);
            if (chosen != null) out.put(key, chosen);
        }
        return out;
    }

    private Series evalConst(Expr.Const c) {
        Series out = new Series();
        // 常量无维度、无期间——在组合时按需要对齐；此处给一个占位列由组合逻辑广播
        // 简化：常量在与序列运算时按对方单元格广播（在 evalArith 中作为 null 处理为 0 不合适）
        // 因此这里返回一个特殊序列，调用方需处理。为保持简单，常量展开到全宇宙单元格。
        for (LocalDate d = ctx.from; !d.isAfter(ctx.to); d = d.plusDays(1)) {
            String period = periodKey(d, ctx.grain);
            CellValue cv = CellValue.of(BigDecimal.valueOf(c.value()));
            cv.setTrace(String.valueOf(c.value()));
            out.put(CellKey.of(period, Map.of()), cv);
        }
        return out;
    }

    // ---------------- 同店增长 ----------------
    private Series evalCohort(Expr.Cohort c) {
        // 基指标在两个比较期（当前期 vs 对比期）上按门店求和，再算增长率。
        Series base = eval(c.base());
        // 收集基指标的 (store, period) -> value
        Map<String, Map<String, BigDecimal>> byStorePeriod = new HashMap<>();
        Set<String> periods = new HashSet<>();
        Set<String> stores = new HashSet<>();
        for (Map.Entry<CellKey, CellValue> en : base.cells().entrySet()) {
            String store = en.getKey().dims().get("store_id");
            if (store == null) continue;
            stores.add(store);
            String period = en.getKey().periodKey();
            periods.add(period);
            CellValue cv = en.getValue();
            if (cv.getStatus() == CellValue.Status.NOT_COVERED) continue; // 未覆盖不计入（不填零）
            BigDecimal v = cv.value();
            if (v == null) continue;
            byStorePeriod.computeIfAbsent(store, k -> new HashMap<>())
                    .merge(period, v, BigDecimal::add);
        }
        Series out = new Series();
        for (String store : stores) {
            for (String period : periods) {
                String cmpPeriod = comparePeriod(period, c.compare());
                CellKey key = CellKey.of(period, Map.of("store_id", store));
                // 两期的营业与覆盖状态
                PeriodInfo cur = periodInfo(store, period);
                PeriodInfo prev = periodInfo(store, cmpPeriod);
                BigDecimal curVal = byStorePeriod.getOrDefault(store, Map.of()).get(period);
                BigDecimal prevVal = byStorePeriod.getOrDefault(store, Map.of()).get(cmpPeriod);
                CellValue cv = new CellValue();
                // 队列规则：两期均须营业且完全覆盖；跨期新开/闭店/缺资料各自说明，不填零
                if (prev.openDays == 0) {
                    cv.setStatus(CellValue.Status.EXCLUDED);
                    cv.setMessage("对比期 " + cmpPeriod + " 未开业/闭店（跨期新开），不进入同店队列");
                } else if (cur.openDays == 0) {
                    cv.setStatus(CellValue.Status.EXCLUDED);
                    cv.setMessage("当前期 " + period + " 闭店，不进入同店队列");
                } else if (prev.coveredDays < prev.totalDays) {
                    cv.setStatus(CellValue.Status.EXCLUDED);
                    cv.setMessage("对比期 " + cmpPeriod + " 缺资料（覆盖 " + prev.coveredDays + "/" + prev.totalDays + " 天），不填零");
                } else if (cur.coveredDays < cur.totalDays) {
                    cv.setStatus(CellValue.Status.EXCLUDED);
                    cv.setMessage("当前期 " + period + " 缺资料（覆盖 " + cur.coveredDays + "/" + cur.totalDays + " 天），不填零");
                } else if (prevVal == null || prevVal.compareTo(BigDecimal.ZERO) == 0) {
                    cv.setStatus(CellValue.Status.DIV_ZERO);
                    cv.setMessage("对比期基数为零，无法计算增长率");
                } else if (curVal == null) {
                    cv.setStatus(CellValue.Status.EXCLUDED);
                    cv.setMessage("当前期无销售记录");
                } else {
                    BigDecimal growth = curVal.subtract(prevVal).divide(prevVal, 6, RoundingMode.HALF_UP);
                    cv.setNum(curVal);
                    cv.setDen(prevVal);
                    cv.setTrace("同店增长 = (" + curVal + " - " + prevVal + ") / " + prevVal + " = " + growth);
                    cv.setMessage("队列门店：两期均营业且覆盖（" + cur.openDays + "/" + prev.openDays + " 天）");
                }
                cv.addLineage("store", store, curVal, cv.getStatus() == CellValue.Status.OK, cv.getMessage());
                out.put(key, cv);
            }
        }
        return out;
    }

    private record PeriodInfo(int openDays, int coveredDays, int totalDays) {}

    /** 门店在某期的营业天数、覆盖天数、总天数。 */
    private PeriodInfo periodInfo(String storeId, String period) {
        LocalDate start = periodStart(period, ctx.grain);
        LocalDate end = periodEnd(period, ctx.grain);
        int openDays = 0, coveredDays = 0, total = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            total++;
            if (ctx.isStoreOpen(storeId, d)) openDays++;
            if (ctx.isCovered("orders", storeId, d) || ctx.isStoreClosed(storeId, d)) coveredDays++;
        }
        return new PeriodInfo(openDays, coveredDays, total);
    }

    private String comparePeriod(String period, String compare) {
        YearMonth ym = YearMonth.parse(period);
        return "prev_year".equals(compare) ? ym.minusYears(1).toString() : ym.minusMonths(1).toString();
    }

    // ---------------- 工具 ----------------

    private static final int COL_STORE = 0;
    private static final int COL_DATE = 1;
    private static final int COL_ID = 2;
    private static final int COL_AMOUNT = 3;
    private static final int COL_EXTRA = 4; // status / channel 等

    /** 把源记录统一为 Object[]： [storeId, businessDate, recordId, amount, extraMap] */
    private List<Object[]> recordsOf(String source) {
        List<Object[]> out = new ArrayList<>();
        switch (source) {
            case "orders" -> {
                for (OrderFact o : ctx.orders) {
                    Map<String, Object> extra = new HashMap<>();
                    extra.put("status", o.getStatus());
                    extra.put("channel", o.getChannel());
                    extra.put("currency", o.getCurrency());
                    out.add(new Object[]{o.getStoreId(), o.getBusinessDate(), o.getOrderId(), o.getAmount(), extra});
                }
            }
            case "order_lines" -> {
                for (com.semantic.platform.source.OrderLine l : ctx.orderLines) {
                    Map<String, Object> extra = new HashMap<>();
                    extra.put("sku", l.getSku());
                    extra.put("quantity", BigDecimal.valueOf(l.getQuantity()));
                    extra.put("unit_price", l.getUnitPrice());
                    extra.put("order_id", l.getOrderId());
                    out.add(new Object[]{l.getStoreId(), l.getBusinessDate(), l.getLineId(), l.getAmount(), extra});
                }
            }
            case "refunds" -> {
                for (RefundFact r : ctx.refunds) {
                    Map<String, Object> extra = new HashMap<>();
                    extra.put("reason", r.getReason());
                    out.add(new Object[]{r.getStoreId(), r.getBusinessDate(), r.getRefundId(), r.getAmount(), extra});
                }
            }
            case "store_status" -> {
                for (StoreStatus s : ctx.storeStatus) {
                    Map<String, Object> extra = new HashMap<>();
                    extra.put("status", s.getStatus());
                    extra.put("open_hours", s.getOpenHours());
                    out.add(new Object[]{s.getStoreId(), s.getBusinessDate(), s.getStoreId() + "@" + s.getBusinessDate(),
                            s.getOpenHours() == null ? BigDecimal.ZERO : s.getOpenHours(), extra});
                }
            }
            default -> throw new IllegalStateException("不支持的来源: " + source);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private BigDecimal numericField(String source, String field, Object[] rec) {
        Map<String, Object> extra = (Map<String, Object>) rec[COL_EXTRA];
        return switch (field) {
            case "amount" -> (BigDecimal) rec[COL_AMOUNT];
            case "quantity" -> extra.get("quantity") instanceof BigDecimal q ? q : BigDecimal.ONE;
            case "unit_price" -> extra.get("unit_price") instanceof BigDecimal p ? p : BigDecimal.ZERO;
            case "open_hours" -> (BigDecimal) rec[COL_AMOUNT];
            default -> extra.get(field) instanceof BigDecimal b ? b : BigDecimal.ONE;
        };
    }

    private String recordId(String source, Object[] rec) {
        return (String) rec[COL_ID];
    }

    private String recordType(String source) {
        return switch (source) {
            case "orders" -> "order";
            case "order_lines" -> "order_line";
            case "refunds" -> "refund";
            case "store_status" -> "store_status";
            default -> source;
        };
    }

    @SuppressWarnings("unchecked")
    private FilterResult passesFilters(List<Filter> filters, String source, Object[] rec) {
        if (filters == null || filters.isEmpty()) return FilterResult.ok();
        Map<String, Object> extra = (Map<String, Object>) rec[COL_EXTRA];
        for (Filter f : filters) {
            Object actual = switch (f.field()) {
                case "amount" -> rec[COL_AMOUNT];
                case "store_id" -> rec[COL_STORE];
                case "status" -> extra.get("status");
                case "channel" -> extra.get("channel");
                case "currency" -> extra.get("currency");
                default -> extra.get(f.field());
            };
            if (!matches(f.operator(), actual, f.value())) {
                return FilterResult.reject(f.field() + " " + f.operator() + " " + f.value() + " 不满足（实际=" + actual + "）");
            }
        }
        return FilterResult.ok();
    }

    private boolean matches(String op, Object actual, Object expected) {
        if (actual == null) return "ne".equals(op);
        String a = actual.toString();
        switch (op) {
            case "eq" -> { return a.equals(String.valueOf(expected)); }
            case "ne" -> { return !a.equals(String.valueOf(expected)); }
            case "in" -> {
                if (expected instanceof List<?> list) {
                    for (Object o : list) if (a.equals(String.valueOf(o))) return true;
                    return false;
                }
                return a.equals(String.valueOf(expected));
            }
            case "gt", "ge", "lt", "le" -> {
                BigDecimal av = actual instanceof BigDecimal b ? b : new BigDecimal(a);
                BigDecimal ev = new BigDecimal(String.valueOf(expected));
                int c = av.compareTo(ev);
                return switch (op) {
                    case "gt" -> c > 0; case "ge" -> c >= 0; case "lt" -> c < 0; default -> c <= 0;
                };
            }
            default -> { return false; }
        }
    }

    private record FilterResult(boolean pass, String reason) {
        static FilterResult ok() { return new FilterResult(true, null); }
        static FilterResult reject(String r) { return new FilterResult(false, r); }
    }

    private Map<String, String> dimsOf(List<String> by, String source, Object[] rec) {
        Map<String, String> dims = new TreeMap<>();
        for (String dim : by) {
            switch (dim) {
                case "store_id" -> dims.put("store_id", (String) rec[COL_STORE]);
                case "channel" -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> extra = (Map<String, Object>) rec[COL_EXTRA];
                    Object ch = extra.get("channel");
                    if (ch != null) dims.put("channel", ch.toString());
                }
                default -> { }
            }
        }
        return dims;
    }

    public static String periodKey(LocalDate date, String grain) {
        return "month".equals(grain) ? YearMonth.from(date).toString() : date.toString();
    }

    public static LocalDate periodStart(String periodKey, String grain) {
        return "month".equals(grain) ? YearMonth.parse(periodKey).atDay(1) : LocalDate.parse(periodKey);
    }

    public static LocalDate periodEnd(String periodKey, String grain) {
        return "month".equals(grain) ? YearMonth.parse(periodKey).atEndOfMonth() : LocalDate.parse(periodKey);
    }

    private BigDecimal nvl(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /** 单元格聚合累加器。 */
    private static class AggAcc {
        BigDecimal sum = BigDecimal.ZERO;
        long count = 0;
        Set<String> distinct = new HashSet<>();
        BigDecimal min = null;
        BigDecimal max = null;
        List<CellValue.Lineage> included = new ArrayList<>();
        List<CellValue.Lineage> excluded = new ArrayList<>();

        void include(String type, String id, BigDecimal value, String measure) {
            count++;
            if (value != null) {
                sum = sum.add(value);
                min = min == null ? value : min.min(value);
                max = max == null ? value : max.max(value);
                distinct.add(id);
            }
            included.add(new CellValue.Lineage(type, id, value, true, null));
        }
    }
}
