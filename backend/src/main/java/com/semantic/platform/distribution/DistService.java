package com.semantic.platform.distribution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantic.platform.source.OrderFact;
import com.semantic.platform.source.OrderFactRepository;
import com.semantic.platform.source.OrderLine;
import com.semantic.platform.source.OrderLineRepository;
import com.semantic.platform.source.OrderReceipt;
import com.semantic.platform.source.OrderReceiptRepository;
import com.semantic.platform.source.PaymentFact;
import com.semantic.platform.source.PaymentFactRepository;
import com.semantic.platform.source.RefundFact;
import com.semantic.platform.source.RefundFactRepository;
import com.semantic.platform.source.SourceBatch;
import com.semantic.platform.source.SourceBatchRepository;
import com.semantic.platform.source.StoreStatus;
import com.semantic.platform.source.StoreStatusRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 分布查询服务：订单金额编译、近似摘要维护、分布查询（精确/近似/自动路由）、发布快照。
 *
 * <p><b>同源约定：</b>金额编译、摘要构建、查询筛选、结果发布引用同一退款状态截止点
 * （{@link DistCompile#getRefundCutoff()}）与同一套编译规则（{@link OrderAmountCompiler}）。
 *
 * <p><b>降级规则：</b>有效样本不足 → 标出不可用（不以均值替代）；
 * 摘要排名误差界超过展示要求 → 自动降级为精确重算并显式标记；
 * 不支持的过滤维度 → 不假称精确，降级精确重算并说明原因。
 */
@Service
public class DistService {

    /** 近似摘要支持的维度与过滤（其它维度/过滤对近似路线不可用）。 */
    public static final Set<String> APPROX_DIMS = Set.of("channel");
    public static final Set<String> APPROX_FILTERS = Set.of("channel");

    private static final String QUANTILE_DEF =
            "type-7 线性插值：升序样本 x(0..n-1)，h=q·(n-1)，v=x(floor h)+frac·(x(floor h+1)-x(floor h))；中位数=q(0.5)，P90=q(0.9)";
    private static final String ZERO_RULE =
            "净额为 0 的已完成订单（全额退款/全额抵扣）作为 0.00 观察值计入分布；金额无法确定的订单单列待核，不进入样本";

    private final OrderFactRepository orderRepo;
    private final OrderLineRepository lineRepo;
    private final PaymentFactRepository paymentRepo;
    private final OrderReceiptRepository receiptRepo;
    private final RefundFactRepository refundRepo;
    private final SourceBatchRepository batchRepo;
    private final StoreStatusRepository storeStatusRepo;
    private final DistCompileRepository compileRepo;
    private final OrderAmountObsRepository obsRepo;
    private final DistSummaryRepository summaryRepo;
    private final DistPublicationRepository publicationRepo;
    private final DedupDecisionRepository dedupRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    public DistService(OrderFactRepository orderRepo, OrderLineRepository lineRepo,
                       PaymentFactRepository paymentRepo, OrderReceiptRepository receiptRepo,
                       RefundFactRepository refundRepo, SourceBatchRepository batchRepo,
                       StoreStatusRepository storeStatusRepo, DistCompileRepository compileRepo,
                       OrderAmountObsRepository obsRepo, DistSummaryRepository summaryRepo,
                       DistPublicationRepository publicationRepo, DedupDecisionRepository dedupRepo) {
        this.orderRepo = orderRepo;
        this.lineRepo = lineRepo;
        this.paymentRepo = paymentRepo;
        this.receiptRepo = receiptRepo;
        this.refundRepo = refundRepo;
        this.batchRepo = batchRepo;
        this.storeStatusRepo = storeStatusRepo;
        this.compileRepo = compileRepo;
        this.obsRepo = obsRepo;
        this.summaryRepo = summaryRepo;
        this.publicationRepo = publicationRepo;
        this.dedupRepo = dedupRepo;
    }

    // ==================== 编译（全量 / 局部重建） ====================

    /**
     * 编译订单金额观察值并重建受影响范围的摘要。
     *
     * @param triggerType    full | late_refund | correction | dedup | manual
     * @param onlyOrderKeys  null 表示全量；否则只重建这些订单键及其所在（门店×营业周）摘要
     */
    @Transactional
    public Map<String, Object> compile(String triggerType, String triggerRef, List<String> onlyOrderKeys) {
        boolean full = onlyOrderKeys == null;
        OffsetDateTime cutoff = OffsetDateTime.now();

        List<OrderFact> orders = full ? orderRepo.findAll() : orderRepo.findByOrderIdIn(onlyOrderKeys);
        List<String> keys = orders.stream().map(OrderFact::getOrderId).toList();
        List<OrderLine> lines = full ? lineRepo.findAll() : lineRepo.findByOrderIdIn(keys);
        List<PaymentFact> pays = full ? paymentRepo.findAll() : paymentRepo.findByOrderIdIn(keys);
        List<OrderReceipt> receipts = full ? receiptRepo.findAll() : receiptRepo.findByOrderIdIn(keys);
        List<RefundFact> refunds = full ? refundRepo.findAll() : refundRepo.findByOrderIdIn(keys);

        DistCompile compile = new DistCompile();
        compile.setTriggerType(triggerType);
        compile.setTriggerRef(triggerRef);
        compile.setRefundCutoff(cutoff);
        compile.setAffectedOrders(orders.size());
        compile.setNotes(full ? "全量编译" : "局部重建 " + orders.size() + " 个订单键");
        compileRepo.save(compile);

        List<OrderAmountCompiler.Obs> compiled = OrderAmountCompiler.compile(
                new OrderAmountCompiler.Input(orders, lines, pays, receipts, refunds, cutoff));
        compiled = applyDedupDecisions(compiled);

        int completed = 0, pending = 0, excluded = 0;
        Set<String> affectedCells = new HashSet<>();
        for (OrderAmountCompiler.Obs o : compiled) {
            OrderAmountObs row = new OrderAmountObs();
            row.setOrderKey(o.orderKey());
            row.setStoreId(o.storeId());
            row.setBusinessDate(o.businessDate());
            row.setBizWeek(o.bizWeek());
            row.setChannel(o.channel());
            row.setCurrency(o.currency());
            row.setGrossAmount(o.gross());
            row.setRefundAmount(o.refund());
            row.setNetAmount(o.net());
            row.setState(o.state());
            row.setStateReason(o.reason());
            row.setAmountSource(o.amountSource());
            row.setCompileId(compile.getId());
            obsRepo.save(row);
            if (OrderAmountCompiler.COMPLETED.equals(o.state())) completed++;
            else if (OrderAmountCompiler.PENDING.equals(o.state())) pending++;
            else excluded++;
            if (o.bizWeek() != null) affectedCells.add(o.storeId() + "|" + o.bizWeek());
        }

        // 只重建受影响（门店 × 营业周）的摘要版本；其它范围保持原版本
        int rebuilt = rebuildSummaries(compile.getId(), affectedCells);

        compile.setFinishedAt(OffsetDateTime.now());
        compileRepo.save(compile);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("compileId", compile.getId());
        out.put("triggerType", triggerType);
        out.put("refundCutoff", cutoff.toString());
        out.put("scope", full ? "full" : "partial");
        out.put("compiledOrders", compiled.size());
        out.put("completed", completed);
        out.put("pending", pending);
        out.put("excluded", excluded);
        out.put("rebuiltSummaryCells", rebuilt);
        return out;
    }

    /** 应用去重决议：被裁定重复的订单键永远不得成为有效样本。 */
    private List<OrderAmountCompiler.Obs> applyDedupDecisions(List<OrderAmountCompiler.Obs> compiled) {
        Map<String, DedupDecision> byDup = new HashMap<>();
        for (DedupDecision d : dedupRepo.findAll()) byDup.put(d.getDuplicateKey(), d);
        if (byDup.isEmpty()) return compiled;
        List<OrderAmountCompiler.Obs> out = new ArrayList<>(compiled.size());
        for (OrderAmountCompiler.Obs o : compiled) {
            DedupDecision d = byDup.get(o.orderKey());
            if (d != null && !OrderAmountCompiler.EXCLUDED.equals(o.state())) {
                out.add(new OrderAmountCompiler.Obs(o.orderKey(), o.storeId(), o.businessDate(), o.bizWeek(),
                        o.channel(), o.currency(), o.gross(), o.refund(), o.net(),
                        OrderAmountCompiler.EXCLUDED,
                        "重复订单，并入 " + d.getCanonicalKey() + "（去重决议 #" + d.getId() + "）",
                        o.amountSource()));
            } else {
                out.add(o);
            }
        }
        return out;
    }

    /** 重建指定（门店|营业周）单元格的摘要：从当前最新观察值全量重算该格。 */
    private int rebuildSummaries(Long compileId, Set<String> cells) {
        if (cells.isEmpty()) return 0;
        Map<String, OrderAmountObs> current = currentObsByKey();
        int rebuilt = 0;
        for (String cell : cells) {
            String[] parts = cell.split("\\|", 2);
            String storeId = parts[0];
            String week = parts[1];
            // 该格当前全部已完成观察值，按渠道分组
            Map<String, List<BigDecimal>> byChannel = new TreeMap<>();
            for (OrderAmountObs o : current.values()) {
                if (!storeId.equals(o.getStoreId()) || !week.equals(o.getBizWeek())) continue;
                if (!OrderAmountCompiler.COMPLETED.equals(o.getState())) continue;
                if (o.getNetAmount() == null) continue;
                String ch = o.getChannel() == null ? "unknown" : o.getChannel();
                byChannel.computeIfAbsent(ch, k -> new ArrayList<>()).add(o.getNetAmount());
            }
            for (Map.Entry<String, List<BigDecimal>> en : byChannel.entrySet()) {
                List<BigDecimal> nets = en.getValue();
                TDigest digest = new TDigest();
                BigDecimal sum = BigDecimal.ZERO;
                BigDecimal min = null, max = null;
                for (BigDecimal v : nets) {
                    digest.add(v.doubleValue());
                    sum = sum.add(v);
                    min = min == null ? v : min.min(v);
                    max = max == null ? v : max.max(v);
                }
                DistSummary s = new DistSummary();
                s.setStoreId(storeId);
                s.setBizWeek(week);
                s.setDimChannel(en.getKey());
                s.setCompileId(compileId);
                s.setSampleCount(nets.size());
                s.setSumAmount(sum);
                s.setMinAmount(min);
                s.setMaxAmount(max);
                s.setSketchJson(digest.toJson());
                summaryRepo.save(s);
                rebuilt++;
            }
        }
        return rebuilt;
    }

    /** 当前观察值视图：每个订单键取 compile_id 最大的一行。 */
    public Map<String, OrderAmountObs> currentObsByKey() {
        Map<String, OrderAmountObs> latest = new HashMap<>();
        for (OrderAmountObs o : obsRepo.findAll()) {
            OrderAmountObs cur = latest.get(o.getOrderKey());
            if (cur == null || o.getCompileId() > cur.getCompileId()) latest.put(o.getOrderKey(), o);
        }
        return latest;
    }

    // ==================== 分布查询 ====================

    /** 分布查询：按门店 × 营业周（× 业态维度）输出中位数 / P90 / 高额占比及标签。 */
    public Map<String, Object> query(Map<String, Object> params) {
        LocalDate from = parseDate(params.get("from"), LocalDate.of(2026, 1, 1));
        LocalDate to = parseDate(params.get("to"), LocalDate.of(2026, 3, 31));
        List<String> storeIds = parseStringList(params.get("storeIds"));
        List<String> dims = parseStringList(params.get("dims"));
        Map<String, Object> filters = parseFilters(params.get("filters"));
        String route = String.valueOf(params.getOrDefault("route", "auto"));
        BigDecimal highThreshold = parseDecimal(params.get("highThreshold"), new BigDecimal("1000"));
        int minSample = parseInt(params.get("minSample"), 10);
        double maxRankError = parseDouble(params.get("maxRankError"), 0.05);

        // 维度与过滤的近似支持性
        List<String> unsupportedDims = dims.stream().filter(d -> !APPROX_DIMS.contains(d)).toList();
        List<String> unsupportedFilters = filters.keySet().stream().filter(f -> !APPROX_FILTERS.contains(f)).toList();
        boolean approxSupported = unsupportedDims.isEmpty() && unsupportedFilters.isEmpty();

        // 当前观察值 + 编译版本（截止点标签）
        Map<String, OrderAmountObs> current = currentObsByKey();
        Map<Long, DistCompile> compiles = new HashMap<>();
        for (DistCompile c : compileRepo.findAll()) compiles.put(c.getId(), c);

        // 范围过滤（状态无关）：日期 / 门店 / 渠道
        String channelFilter = filters.get("channel") == null ? null : String.valueOf(filters.get("channel"));
        BigDecimal amountMin = filters.get("amountMin") == null ? null : new BigDecimal(String.valueOf(filters.get("amountMin")));
        BigDecimal amountMax = filters.get("amountMax") == null ? null : new BigDecimal(String.valueOf(filters.get("amountMax")));

        List<OrderAmountObs> scoped = new ArrayList<>();
        for (OrderAmountObs o : current.values()) {
            if (o.getBusinessDate() == null) continue;
            if (o.getBusinessDate().isBefore(from) || o.getBusinessDate().isAfter(to)) continue;
            if (storeIds != null && !storeIds.isEmpty() && !storeIds.contains(o.getStoreId())) continue;
            if (channelFilter != null && !channelFilter.equals(o.getChannel())) continue;
            scoped.add(o);
        }

        // 分格：门店 × 营业周（× 渠道）
        boolean dimChannel = dims.contains("channel");
        Map<String, List<OrderAmountObs>> cells = new TreeMap<>();
        for (OrderAmountObs o : scoped) {
            String key = o.getStoreId() + "|" + o.getBizWeek() + (dimChannel ? "|" + (o.getChannel() == null ? "unknown" : o.getChannel()) : "");
            cells.computeIfAbsent(key, k -> new ArrayList<>()).add(o);
        }

        // 近似摘要索引（每格取最新编译版本）
        Map<String, DistSummary> summaries = latestSummaries();

        List<Map<String, Object>> cellResults = new ArrayList<>();
        OffsetDateTime maxCutoff = null;
        for (Map.Entry<String, List<OrderAmountObs>> en : cells.entrySet()) {
            Map<String, Object> cell = buildCell(en.getKey(), en.getValue(), dimChannel,
                    amountMin, amountMax, highThreshold, minSample, maxRankError,
                    route, approxSupported, unsupportedDims, unsupportedFilters,
                    summaries, compiles);
            cellResults.add(cell);
            String cut = (String) cell.get("refundCutoff");
            if (cut != null) {
                OffsetDateTime c = OffsetDateTime.parse(cut);
                if (maxCutoff == null || c.isAfter(maxCutoff)) maxCutoff = c;
            }
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("quantileDefinition", QUANTILE_DEF);
        meta.put("zeroAmountRule", ZERO_RULE);
        meta.put("highThreshold", highThreshold);
        meta.put("minSample", minSample);
        meta.put("maxRankError", maxRankError);
        meta.put("routeRequested", route);
        meta.put("approxSupported", approxSupported);
        if (!approxSupported) {
            meta.put("approxUnsupportedReason",
                    "维度 " + unsupportedDims + " / 过滤 " + unsupportedFilters + " 无近似摘要，相关单元格降级精确重算");
        }
        meta.put("refundCutoff", maxCutoff == null ? null : maxCutoff.toString());
        DistCompile latest = compileRepo.findTopByOrderByIdDesc();
        meta.put("latestCompileId", latest == null ? null : latest.getId());
        meta.put("verificationNote",
                "近似路线误差界由 t-digest 质心结构给出；与精确路线在相同样本上的对照见 scripts/verify.sh 分布章节");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("meta", meta);
        out.put("cells", cellResults);
        return out;
    }

    /** 构建一个单元格：总体/样本/待核标签 + 覆盖范围 + 路由与降级 + 三项指标。 */
    private Map<String, Object> buildCell(String cellKey, List<OrderAmountObs> obs, boolean dimChannel,
                                          BigDecimal amountMin, BigDecimal amountMax,
                                          BigDecimal highThreshold, int minSample, double maxRankError,
                                          String route, boolean approxSupported,
                                          List<String> unsupportedDims, List<String> unsupportedFilters,
                                          Map<String, DistSummary> summaries,
                                          Map<Long, DistCompile> compiles) {
        String[] parts = cellKey.split("\\|", 3);
        String storeId = parts[0];
        String week = parts[1];
        String channel = dimChannel ? parts[2] : null;

        int population = obs.size();
        int pendingCount = 0, excludedCount = 0;
        List<BigDecimal> nets = new ArrayList<>();
        Set<Long> compileIds = new HashSet<>();
        OffsetDateTime cellCutoff = null;
        for (OrderAmountObs o : obs) {
            compileIds.add(o.getCompileId());
            DistCompile c = compiles.get(o.getCompileId());
            if (c != null && (cellCutoff == null || c.getRefundCutoff().isAfter(cellCutoff))) {
                cellCutoff = c.getRefundCutoff();
            }
            switch (o.getState()) {
                case OrderAmountCompiler.PENDING -> pendingCount++;
                case OrderAmountCompiler.EXCLUDED -> excludedCount++;
                default -> {
                    if (o.getNetAmount() == null) { pendingCount++; continue; }
                    // 金额区间过滤仅作用于已完成观察值（样本选择），不影响订单总体口径
                    if (amountMin != null && o.getNetAmount().compareTo(amountMin) < 0) continue;
                    if (amountMax != null && o.getNetAmount().compareTo(amountMax) > 0) continue;
                    nets.add(o.getNetAmount());
                }
            }
        }
        int validSamples = nets.size();

        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("storeId", storeId);
        cell.put("bizWeek", week);
        Map<String, String> dimsMap = new LinkedHashMap<>();
        if (dimChannel) dimsMap.put("channel", channel);
        cell.put("dims", dimsMap);
        cell.put("population", population);
        cell.put("validSamples", validSamples);
        cell.put("pendingCount", pendingCount);
        cell.put("excludedCount", excludedCount);
        cell.put("coverage", coverageOf(storeId, week));
        cell.put("refundCutoff", cellCutoff == null ? null : cellCutoff.toString());
        cell.put("compileIds", compileIds.stream().sorted().toList());

        // ---- 样本不足：标出不可用，绝不以均值替代 ----
        if (validSamples == 0) {
            cell.put("status", "no_data");
            cell.put("route", null);
            cell.put("message", "无有效样本（订单总体 " + population + "：待核 " + pendingCount + "、排除 " + excludedCount + "）");
            putNullMetrics(cell);
            return cell;
        }
        if (validSamples < minSample) {
            cell.put("status", "unavailable_small_sample");
            cell.put("route", null);
            cell.put("message", "有效样本 " + validSamples + " < 最小样本数 " + minSample
                    + "，分位数不可用（按规则不以均值替代）");
            putNullMetrics(cell);
            return cell;
        }

        // ---- 路由决策 ----
        boolean wantApprox = "approx".equals(route) || "auto".equals(route);
        boolean degraded = false;
        String degradeReason = null;
        String useRoute = "exact";
        TDigest digest = null;
        if (wantApprox && !approxSupported) {
            degraded = true;
            degradeReason = "维度 " + unsupportedDims + " / 过滤 " + unsupportedFilters
                    + " 无近似摘要，降级精确重算（不对不支持的过滤维度假称精确）";
        } else if (wantApprox) {
            digest = digestFor(summaries, storeId, week, channel);
            if (digest == null) {
                degraded = true;
                degradeReason = "该单元格无近似摘要，降级精确重算";
            }
        }

        if (digest != null) {
            TDigest.Estimate med = digest.quantileEstimate(0.5);
            TDigest.Estimate p90 = digest.quantileEstimate(0.9);
            TDigest.Estimate high = digest.highRatioEstimate(highThreshold.doubleValue());
            double worstBound = Math.max(med.rankErrorBound(),
                    Math.max(p90.rankErrorBound(), high.rankErrorBound()));
            if (worstBound > maxRankError) {
                degraded = true;
                degradeReason = "摘要排名误差界 " + String.format("%.4f", worstBound)
                        + " 超过展示要求 " + maxRankError + "，降级精确重算";
                digest = null;
            } else {
                useRoute = "approx";
                cell.put("median", approxMetric(med));
                cell.put("p90", approxMetric(p90));
                cell.put("highRatio", approxMetric(high));
            }
        }
        if (digest == null) {
            // 精确路线：排序计算，可完全复算
            nets.sort(Comparator.naturalOrder());
            cell.put("median", exactMetric(Quantiles.median(nets)));
            cell.put("p90", exactMetric(Quantiles.p90(nets)));
            cell.put("highRatio", exactMetric(Quantiles.highRatio(nets, highThreshold)));
        }

        cell.put("status", "ok");
        cell.put("route", useRoute);
        cell.put("degraded", degraded);
        cell.put("degradeReason", degradeReason);
        cell.put("message", null);
        return cell;
    }

    private void putNullMetrics(Map<String, Object> cell) {
        cell.put("median", null);
        cell.put("p90", null);
        cell.put("highRatio", null);
        cell.put("degraded", false);
        cell.put("degradeReason", null);
    }

    private Map<String, Object> exactMetric(BigDecimal value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", value);
        m.put("rankErrorBound", 0);
        m.put("rankLo", null);
        m.put("rankHi", null);
        return m;
    }

    private Map<String, Object> approxMetric(TDigest.Estimate est) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", BigDecimal.valueOf(est.value()).setScale(4, java.math.RoundingMode.HALF_UP));
        m.put("rankErrorBound", BigDecimal.valueOf(est.rankErrorBound()).setScale(6, java.math.RoundingMode.HALF_UP));
        m.put("rankLo", est.rankLo());
        m.put("rankHi", est.rankHi());
        return m;
    }

    /** 取单元格的近似摘要：指定渠道取该渠道摘要，否则合并该（门店×周）全部渠道摘要。 */
    private TDigest digestFor(Map<String, DistSummary> summaries, String storeId, String week, String channel) {
        if (channel != null) {
            DistSummary s = summaries.get(storeId + "|" + week + "|" + channel);
            return s == null ? null : TDigest.fromJson(s.getSketchJson());
        }
        TDigest merged = null;
        for (Map.Entry<String, DistSummary> en : summaries.entrySet()) {
            String[] p = en.getKey().split("\\|", 3);
            if (!p[0].equals(storeId) || !p[1].equals(week)) continue;
            TDigest d = TDigest.fromJson(en.getValue().getSketchJson());
            if (merged == null) merged = d; else merged.merge(d);
        }
        return merged;
    }

    /** 最新摘要索引：(store|week|channel) -> 最新编译版本的摘要。 */
    public Map<String, DistSummary> latestSummaries() {
        Map<String, DistSummary> latest = new HashMap<>();
        for (DistSummary s : summaryRepo.findAll()) {
            String key = s.getStoreId() + "|" + s.getBizWeek() + "|" + s.getDimChannel();
            DistSummary cur = latest.get(key);
            if (cur == null || s.getCompileId() > cur.getCompileId()) latest.put(key, s);
        }
        return latest;
    }

    /** 单元格覆盖范围：该营业周 7 天中订单源已覆盖的天数（闭店日视为已覆盖的合法零值）。 */
    private Map<String, Object> coverageOf(String storeId, String bizWeek) {
        LocalDate weekStart = weekStart(bizWeek);
        if (weekStart == null) {
            return Map.of("status", "unknown", "coveredDays", 0, "totalDays", 0, "message", "无法解析营业周");
        }
        // 覆盖集合：orders 批次（loaded）按门店覆盖的日期
        Set<String> covered = new HashSet<>();
        for (SourceBatch b : batchRepo.findAll()) {
            if (!"orders".equals(b.getSourceType()) || !"loaded".equals(b.getStatus())) continue;
            if (b.getStoreId() != null && !b.getStoreId().equals(storeId)) continue;
            LocalDate s = b.getPeriodStart(), e = b.getPeriodEnd();
            if (s == null || e == null) continue;
            for (LocalDate d = s; !d.isAfter(e); d = d.plusDays(1)) covered.add(d.toString());
        }
        Set<String> closed = new HashSet<>();
        for (StoreStatus st : storeStatusRepo.findByStoreIdAndBusinessDateBetween(storeId, weekStart, weekStart.plusDays(6))) {
            if ("closed".equals(st.getStatus()) || "temp_closed".equals(st.getStatus())) {
                closed.add(st.getBusinessDate().toString());
            }
        }
        int coveredDays = 0;
        for (int i = 0; i < 7; i++) {
            String d = weekStart.plusDays(i).toString();
            if (covered.contains(d) || closed.contains(d)) coveredDays++;
        }
        String status = coveredDays == 7 ? "ok" : (coveredDays == 0 ? "not_covered" : "partial");
        Map<String, Object> cov = new LinkedHashMap<>();
        cov.put("status", status);
        cov.put("coveredDays", coveredDays);
        cov.put("totalDays", 7);
        cov.put("message", "ok".equals(status) ? null
                : "营业周覆盖 " + coveredDays + "/7 天，未到数据不计入（不当成零销售）");
        return cov;
    }

    /** 营业周标签 → 周一日期。 */
    public static LocalDate weekStart(String bizWeek) {
        try {
            String[] p = bizWeek.split("-W");
            int year = Integer.parseInt(p[0]);
            int week = Integer.parseInt(p[1]);
            return LocalDate.of(year, 1, 4)
                    .with(java.time.temporal.WeekFields.ISO.weekBasedYear(), year)
                    .with(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear(), week)
                    .with(java.time.DayOfWeek.MONDAY);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 观察值 / 摘要 / 编译版本查询 ====================

    /** 当前观察值列表（待核清单、复算验证用）。 */
    public List<Map<String, Object>> listObs(String state, String storeId, String bizWeek) {
        List<Map<String, Object>> out = new ArrayList<>();
        currentObsByKey().values().stream()
                .filter(o -> state == null || state.equals(o.getState()))
                .filter(o -> storeId == null || storeId.equals(o.getStoreId()))
                .filter(o -> bizWeek == null || bizWeek.equals(o.getBizWeek()))
                .sorted(Comparator.comparing(OrderAmountObs::getStoreId)
                        .thenComparing(OrderAmountObs::getBizWeek)
                        .thenComparing(OrderAmountObs::getOrderKey))
                .forEach(o -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("orderKey", o.getOrderKey());
                    m.put("storeId", o.getStoreId());
                    m.put("businessDate", o.getBusinessDate() == null ? null : o.getBusinessDate().toString());
                    m.put("bizWeek", o.getBizWeek());
                    m.put("channel", o.getChannel());
                    m.put("currency", o.getCurrency());
                    m.put("grossAmount", o.getGrossAmount());
                    m.put("refundAmount", o.getRefundAmount());
                    m.put("netAmount", o.getNetAmount());
                    m.put("state", o.getState());
                    m.put("stateReason", o.getStateReason());
                    m.put("amountSource", o.getAmountSource());
                    m.put("compileId", o.getCompileId());
                    out.add(m);
                });
        return out;
    }

    /** 摘要列表（含版本，验证局部重建用）。 */
    public List<Map<String, Object>> listSummaries() {
        List<Map<String, Object>> out = new ArrayList<>();
        summaryRepo.findAll().stream()
                .sorted(Comparator.comparing(DistSummary::getStoreId)
                        .thenComparing(DistSummary::getBizWeek)
                        .thenComparing(DistSummary::getDimChannel)
                        .thenComparing(DistSummary::getCompileId))
                .forEach(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("storeId", s.getStoreId());
                    m.put("bizWeek", s.getBizWeek());
                    m.put("channel", s.getDimChannel());
                    m.put("compileId", s.getCompileId());
                    m.put("sampleCount", s.getSampleCount());
                    m.put("sumAmount", s.getSumAmount());
                    m.put("minAmount", s.getMinAmount());
                    m.put("maxAmount", s.getMaxAmount());
                    m.put("centroids", TDigest.fromJson(s.getSketchJson()).centroidCount());
                    out.add(m);
                });
        return out;
    }

    public List<DistCompile> listCompiles() {
        return compileRepo.findAll().stream()
                .sorted(Comparator.comparing(DistCompile::getId).reversed()).toList();
    }

    // ==================== 发布快照 ====================

    /** 发布：执行查询并冻结结果（样本与数值），后续重建不回写。 */
    @Transactional
    public Map<String, Object> publish(String name, Map<String, Object> params, String user) {
        Map<String, Object> result = query(params);
        DistCompile latest = compileRepo.findTopByOrderByIdDesc();
        DistPublication p = new DistPublication();
        p.setName(name);
        p.setParamsJson(toJson(params));
        p.setResultJson(toJson(result));
        p.setCompileId(latest == null ? 0 : latest.getId());
        Object cutoff = ((Map<?, ?>) result.get("meta")).get("refundCutoff");
        p.setRefundCutoff(cutoff == null ? null : OffsetDateTime.parse(cutoff.toString()));
        p.setPublishedBy(user);
        publicationRepo.save(p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", p.getId());
        out.put("name", p.getName());
        out.put("compileId", p.getCompileId());
        out.put("refundCutoff", p.getRefundCutoff());
        out.put("publishedAt", p.getPublishedAt());
        return out;
    }

    public List<Map<String, Object>> listPublications() {
        List<Map<String, Object>> out = new ArrayList<>();
        publicationRepo.findAll().stream()
                .sorted(Comparator.comparing(DistPublication::getId).reversed())
                .forEach(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p.getId());
                    m.put("name", p.getName());
                    m.put("compileId", p.getCompileId());
                    m.put("refundCutoff", p.getRefundCutoff());
                    m.put("publishedBy", p.getPublishedBy());
                    m.put("publishedAt", p.getPublishedAt());
                    out.add(m);
                });
        return out;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getPublication(Long id) {
        DistPublication p = publicationRepo.findById(id).orElseThrow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", p.getId());
        out.put("name", p.getName());
        out.put("compileId", p.getCompileId());
        out.put("refundCutoff", p.getRefundCutoff());
        out.put("publishedBy", p.getPublishedBy());
        out.put("publishedAt", p.getPublishedAt());
        out.put("params", parseJson(p.getParamsJson()));
        out.put("result", parseJson(p.getResultJson()));
        return out;
    }

    /** 发布快照 vs 当前重算对比：已发布结果保持当时样本与数值，漂移仅用于展示。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> comparePublication(Long id) {
        Map<String, Object> pub = getPublication(id);
        Map<String, Object> params = (Map<String, Object>) pub.get("params");
        Map<String, Object> current = query(params);
        Map<String, Object> published = (Map<String, Object>) pub.get("result");

        Map<String, Map<String, Object>> pubCells = indexCells(published);
        Map<String, Map<String, Object>> curCells = indexCells(current);
        List<Map<String, Object>> drift = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> en : pubCells.entrySet()) {
            Map<String, Object> cur = curCells.get(en.getKey());
            Map<String, Object> old = en.getValue();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("cell", en.getKey());
            d.put("publishedMedian", metricValue(old, "median"));
            d.put("currentMedian", cur == null ? null : metricValue(cur, "median"));
            d.put("publishedP90", metricValue(old, "p90"));
            d.put("currentP90", cur == null ? null : metricValue(cur, "p90"));
            d.put("publishedSamples", old.get("validSamples"));
            d.put("currentSamples", cur == null ? null : cur.get("validSamples"));
            boolean changed = !String.valueOf(d.get("publishedMedian")).equals(String.valueOf(d.get("currentMedian")))
                    || !String.valueOf(d.get("publishedP90")).equals(String.valueOf(d.get("currentP90")))
                    || !String.valueOf(d.get("publishedSamples")).equals(String.valueOf(d.get("currentSamples")));
            d.put("changed", changed);
            drift.add(d);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("publication", pub);
        out.put("current", current);
        out.put("drift", drift);
        out.put("note", "已发布结果保持发布时的样本与数值；当前列仅用于对比漂移，不回写快照");
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> indexCells(Map<String, Object> result) {
        Map<String, Map<String, Object>> idx = new LinkedHashMap<>();
        Object cells = result.get("cells");
        if (!(cells instanceof List<?> list)) return idx;
        for (Object c : list) {
            Map<String, Object> cell = (Map<String, Object>) c;
            String key = cell.get("storeId") + "|" + cell.get("bizWeek") + "|" + cell.get("dims");
            idx.put(key, cell);
        }
        return idx;
    }

    @SuppressWarnings("unchecked")
    private Object metricValue(Map<String, Object> cell, String metric) {
        Object m = cell.get(metric);
        if (m instanceof Map<?, ?> map) return map.get("value");
        return null;
    }

    // ==================== 工具 ====================

    private String toJson(Object o) {
        try { return mapper.writeValueAsString(o); } catch (Exception e) { return "{}"; }
    }

    private Object parseJson(String s) {
        try { return mapper.readValue(s, Object.class); } catch (Exception e) { return Map.of(); }
    }

    private static LocalDate parseDate(Object v, LocalDate dflt) {
        return v == null ? dflt : LocalDate.parse(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseStringList(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        String s = String.valueOf(v);
        if (s.isBlank()) return List.of();
        return List.of(s.split(",")).stream().map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseFilters(Object v) {
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, val) -> out.put(String.valueOf(k), val));
            return out;
        }
        return Map.of();
    }

    private static BigDecimal parseDecimal(Object v, BigDecimal dflt) {
        return v == null ? dflt : new BigDecimal(String.valueOf(v));
    }

    private static int parseInt(Object v, int dflt) {
        return v == null ? dflt : Integer.parseInt(String.valueOf(v));
    }

    private static double parseDouble(Object v, double dflt) {
        return v == null ? dflt : Double.parseDouble(String.valueOf(v));
    }
}
