package com.semantic.platform.distribution;

import com.semantic.platform.source.OrderFact;
import com.semantic.platform.source.OrderLine;
import com.semantic.platform.source.OrderReceipt;
import com.semantic.platform.source.PaymentFact;
import com.semantic.platform.source.RefundFact;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 订单金额编译器：把多源原始记录编译为"每个稳定订单键恰好一个金额观察值"。
 * <p>纯函数实现（不依赖持久层），同一输入必然得到同一输出，结果可完全复算。
 *
 * <p><b>编译规则（与指标定义同源，全平台唯一约定）：</b>
 * <ol>
 *   <li>稳定订单键 = order_id；一张订单拆成多行商品 / 多个支付渠道 / 多条状态回执，
 *       统计前必须先汇总为单一观察值；</li>
 *   <li>重复回执：同一 receipt_id 只计最早到达的一条，不得复制订单；</li>
 *   <li>撤销（void/cancelled）与未完成支付（unpaid）不得混入已完成交易 → state=excluded；</li>
 *   <li>部分退款：按退款状态截止点重算订单净额 net = gross − Σ(refunded_at ≤ cutoff)；</li>
 *   <li>无法确定最终状态或金额的订单（回执冲突、支付合计与订单金额不一致且无订单行仲裁、
 *       退款超过订单金额）→ state=pending，单列待核，不进入分位样本；</li>
 *   <li>金额口径：有订单行取行合计（多行商品拆单），否则取订单头金额；
 *       支付流水用于校验（无订单行时支付合计与订单头不一致 → 待核）；</li>
 *   <li>零金额规则：净额为 0 的已完成订单（全额退款/全额抵扣）作为 0.00 观察值计入分布；</li>
 *   <li>外币订单（非 CNY）未经换算不混入 → state=excluded。</li>
 * </ol>
 */
public final class OrderAmountCompiler {

    private OrderAmountCompiler() {}

    public static final String COMPLETED = "completed";
    public static final String PENDING = "pending";
    public static final String EXCLUDED = "excluded";

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private static final Set<String> VOID_STATUSES = Set.of("void", "cancelled");
    private static final Set<String> UNPAID_STATUSES = Set.of("unpaid");

    /** 一个订单键的编译结果。 */
    public record Obs(
            String orderKey,
            String storeId,
            LocalDate businessDate,
            String bizWeek,
            String channel,
            String currency,
            BigDecimal gross,
            BigDecimal refund,
            BigDecimal net,
            String state,          // completed | pending | excluded
            String reason,         // pending / excluded 的原因；completed 为 null
            String amountSource    // lines | header
    ) {}

    /** 编译输入：同一批源数据 + 退款状态截止点。 */
    public record Input(
            List<OrderFact> orders,
            List<OrderLine> lines,
            List<PaymentFact> payments,
            List<OrderReceipt> receipts,
            List<RefundFact> refunds,
            OffsetDateTime refundCutoff
    ) {}

    public static List<Obs> compile(Input in) {
        // 按订单键归集
        Map<String, List<OrderLine>> linesByOrder = new HashMap<>();
        for (OrderLine l : in.lines()) linesByOrder.computeIfAbsent(l.getOrderId(), k -> new ArrayList<>()).add(l);
        Map<String, List<PaymentFact>> paysByOrder = new HashMap<>();
        for (PaymentFact p : in.payments()) paysByOrder.computeIfAbsent(p.getOrderId(), k -> new ArrayList<>()).add(p);
        Map<String, List<RefundFact>> refundsByOrder = new HashMap<>();
        for (RefundFact r : in.refunds()) refundsByOrder.computeIfAbsent(r.getOrderId(), k -> new ArrayList<>()).add(r);

        // 回执去重：同一 receipt_id 只保留最早到达的一条
        Map<String, List<OrderReceipt>> receiptsByOrder = new HashMap<>();
        for (OrderReceipt r : dedup(in.receipts())) {
            receiptsByOrder.computeIfAbsent(r.getOrderId(), k -> new ArrayList<>()).add(r);
        }

        List<Obs> out = new ArrayList<>();
        for (OrderFact o : in.orders()) {
            out.add(compileOne(o,
                    linesByOrder.getOrDefault(o.getOrderId(), List.of()),
                    paysByOrder.getOrDefault(o.getOrderId(), List.of()),
                    receiptsByOrder.getOrDefault(o.getOrderId(), List.of()),
                    refundsByOrder.getOrDefault(o.getOrderId(), List.of()),
                    in.refundCutoff()));
        }
        return out;
    }

    /** 回执去重：receipt_id 相同的重复回执只保留 received_at 最早的一条。 */
    static List<OrderReceipt> dedup(List<OrderReceipt> receipts) {
        Map<String, OrderReceipt> first = new LinkedHashMap<>();
        for (OrderReceipt r : receipts) {
            OrderReceipt cur = first.get(r.getReceiptId());
            if (cur == null || r.getReceivedAt().isBefore(cur.getReceivedAt())) {
                first.put(r.getReceiptId(), r);
            }
        }
        return new ArrayList<>(first.values());
    }

    private static Obs compileOne(OrderFact o, List<OrderLine> lines, List<PaymentFact> pays,
                                  List<OrderReceipt> receipts, List<RefundFact> refunds,
                                  OffsetDateTime refundCutoff) {
        String key = o.getOrderId();
        LocalDate bizDate = o.getBusinessDate() != null ? o.getBusinessDate()
                : (o.getOrderedAt() != null ? o.getOrderedAt().toLocalDate() : null);
        String bizWeek = bizDate == null ? null : isoWeek(bizDate);
        String currency = o.getCurrency() == null ? "CNY" : o.getCurrency();

        // ---- 1. 最终状态判定 ----
        String effectiveStatus;
        if (!receipts.isEmpty()) {
            OffsetDateTime latest = receipts.stream().map(OrderReceipt::getReceiptAt)
                    .max(Comparator.naturalOrder()).orElse(null);
            Set<String> latestStatuses = new HashSet<>();
            for (OrderReceipt r : receipts) {
                if (r.getReceiptAt().equals(latest)) latestStatuses.add(r.getStatus());
            }
            if (latestStatuses.size() > 1) {
                return obs(key, o, bizDate, bizWeek, currency, null, BigDecimal.ZERO, null,
                        PENDING, "回执冲突：同一时刻存在多种最终状态 " + latestStatuses + "，无法确定订单最终状态", null);
            }
            effectiveStatus = latestStatuses.iterator().next();
        } else {
            effectiveStatus = o.getStatus();
        }

        if (VOID_STATUSES.contains(effectiveStatus)) {
            return obs(key, o, bizDate, bizWeek, currency, null, BigDecimal.ZERO, null,
                    EXCLUDED, "撤销订单（" + effectiveStatus + "），不混入已完成交易", null);
        }
        if (UNPAID_STATUSES.contains(effectiveStatus)) {
            return obs(key, o, bizDate, bizWeek, currency, null, BigDecimal.ZERO, null,
                    EXCLUDED, "未完成支付，不混入已完成交易", null);
        }
        if (!"CNY".equals(currency)) {
            return obs(key, o, bizDate, bizWeek, currency, null, BigDecimal.ZERO, null,
                    EXCLUDED, "外币订单（" + currency + "）未经换算，不混入 CNY 分布", null);
        }

        // ---- 2. 金额口径：行合计 > 订单头 ----
        BigDecimal gross;
        String source;
        if (!lines.isEmpty()) {
            gross = BigDecimal.ZERO;
            for (OrderLine l : lines) gross = gross.add(l.getAmount());
            source = "lines";
        } else {
            gross = o.getAmount();
            source = "header";
        }
        // 支付流水校验：无订单行仲裁时，支付合计与订单金额不一致 → 金额无法确定 → 待核
        if (!pays.isEmpty()) {
            BigDecimal paySum = BigDecimal.ZERO;
            for (PaymentFact p : pays) paySum = paySum.add(p.getAmount());
            if (lines.isEmpty() && gross != null && paySum.subtract(gross).abs().compareTo(TOLERANCE) > 0) {
                return obs(key, o, bizDate, bizWeek, currency, gross, BigDecimal.ZERO, null,
                        PENDING, "支付合计 " + paySum + " 与订单金额 " + gross + " 不一致且无订单行仲裁，金额待核", source);
            }
        }

        // ---- 3. 截止点内退款，重算净额 ----
        BigDecimal refund = BigDecimal.ZERO;
        for (RefundFact r : refunds) {
            if (refundCutoff == null || !r.getRefundedAt().isAfter(refundCutoff)) {
                refund = refund.add(r.getAmount());
            }
        }
        BigDecimal net = gross == null ? null : gross.subtract(refund);
        if (net != null && net.signum() < 0) {
            return obs(key, o, bizDate, bizWeek, currency, gross, refund, null,
                    PENDING, "截止点内退款合计 " + refund + " 超过订单金额 " + gross + "，数据不一致待核", source);
        }
        // 零金额规则：net = 0 的已完成订单作为 0.00 观察值计入分布
        return obs(key, o, bizDate, bizWeek, currency, gross, refund, net, COMPLETED, null, source);
    }

    private static Obs obs(String key, OrderFact o, LocalDate bizDate, String bizWeek, String currency,
                           BigDecimal gross, BigDecimal refund, BigDecimal net,
                           String state, String reason, String source) {
        return new Obs(key, o.getStoreId(), bizDate, bizWeek, o.getChannel(), currency,
                gross, refund, net, state, reason, source);
    }

    /** ISO 营业周标签，如 2026-W06（周一起始）。 */
    public static String isoWeek(LocalDate d) {
        int year = d.get(WeekFields.ISO.weekBasedYear());
        int week = d.get(WeekFields.ISO.weekOfWeekBasedYear());
        return String.format("%04d-W%02d", year, week);
    }
}
