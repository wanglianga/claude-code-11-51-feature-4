package com.semantic.platform.distribution;

import com.semantic.platform.source.OrderFact;
import com.semantic.platform.source.OrderLine;
import com.semantic.platform.source.OrderReceipt;
import com.semantic.platform.source.PaymentFact;
import com.semantic.platform.source.RefundFact;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 订单金额编译器规则测试：多行拆单、多渠道支付、重复/冲突回执、撤销/未支付、
 * 截止点退款净额、零金额规则、外币排除、待核单列、样本守恒。
 */
class OrderAmountCompilerTest {

    private static final OffsetDateTime CUTOFF = OffsetDateTime.parse("2026-03-01T00:00:00Z");

    private static OrderFact order(String id, String amount, String status) {
        return order(id, "S001", LocalDate.of(2026, 2, 3), amount, "CNY", status, "pos");
    }

    private static OrderFact order(String id, String store, LocalDate date, String amount,
                                   String currency, String status, String channel) {
        OrderFact o = new OrderFact();
        o.setOrderId(id);
        o.setStoreId(store);
        o.setOrderedAt(OffsetDateTime.parse(date + "T10:00:00Z"));
        o.setPaidAt("unpaid".equals(status) || "void".equals(status) ? null : OffsetDateTime.parse(date + "T10:01:00Z"));
        o.setBusinessDate(date);
        o.setAmount(new BigDecimal(amount));
        o.setCurrency(currency);
        o.setStatus(status);
        o.setChannel(channel);
        return o;
    }

    private static OrderLine line(String lineId, String orderId, String amount) {
        OrderLine l = new OrderLine();
        l.setLineId(lineId);
        l.setOrderId(orderId);
        l.setStoreId("S001");
        l.setBusinessDate(LocalDate.of(2026, 2, 3));
        l.setQuantity(1);
        l.setUnitPrice(new BigDecimal(amount));
        l.setAmount(new BigDecimal(amount));
        return l;
    }

    private static PaymentFact pay(String id, String orderId, String channel, String amount) {
        PaymentFact p = new PaymentFact();
        p.setPaymentId(id);
        p.setOrderId(orderId);
        p.setStoreId("S001");
        p.setChannel(channel);
        p.setAmount(new BigDecimal(amount));
        p.setPaidAt(OffsetDateTime.parse("2026-02-03T10:01:00Z"));
        p.setBusinessDate(LocalDate.of(2026, 2, 3));
        return p;
    }

    private static OrderReceipt receipt(String receiptId, String orderId, String status,
                                        String receiptAt, String receivedAt) {
        OrderReceipt r = new OrderReceipt();
        r.setReceiptId(receiptId);
        r.setOrderId(orderId);
        r.setStoreId("S001");
        r.setStatus(status);
        r.setReceiptAt(OffsetDateTime.parse(receiptAt));
        r.setReceivedAt(OffsetDateTime.parse(receivedAt));
        return r;
    }

    private static RefundFact refund(String id, String orderId, String amount, String refundedAt) {
        RefundFact r = new RefundFact();
        r.setRefundId(id);
        r.setOrderId(orderId);
        r.setStoreId("S001");
        r.setAmount(new BigDecimal(amount));
        r.setRefundedAt(OffsetDateTime.parse(refundedAt));
        r.setBusinessDate(LocalDate.of(2026, 2, 3));
        return r;
    }

    private static OrderAmountCompiler.Obs compileOne(OrderFact o, List<OrderLine> lines,
                                                      List<PaymentFact> pays, List<OrderReceipt> receipts,
                                                      List<RefundFact> refunds) {
        List<OrderAmountCompiler.Obs> out = OrderAmountCompiler.compile(
                new OrderAmountCompiler.Input(List.of(o), lines, pays, receipts, refunds, CUTOFF));
        assertEquals(1, out.size(), "每个订单键必须恰好产出一个观察值（样本守恒）");
        return out.get(0);
    }

    @Test
    void multiLineOrderUsesLineSum() {
        OrderFact o = order("O1", "900", "completed");
        OrderAmountCompiler.Obs obs = compileOne(o,
                List.of(line("L1", "O1", "400"), line("L2", "O1", "300"), line("L3", "O1", "200")),
                List.of(), List.of(), List.of());
        assertEquals(OrderAmountCompiler.COMPLETED, obs.state());
        assertEquals(0, new BigDecimal("900").compareTo(obs.gross()));
        assertEquals("lines", obs.amountSource());
        assertEquals(0, new BigDecimal("900").compareTo(obs.net()));
    }

    @Test
    void multiChannelPaymentConsistent() {
        OrderFact o = order("O2", "320", "completed");
        OrderAmountCompiler.Obs obs = compileOne(o, List.of(),
                List.of(pay("P1", "O2", "wechat", "120"), pay("P2", "O2", "alipay", "200")),
                List.of(), List.of());
        assertEquals(OrderAmountCompiler.COMPLETED, obs.state());
        assertEquals(0, new BigDecimal("320").compareTo(obs.net()));
    }

    @Test
    void paymentMismatchWithoutLinesIsPending() {
        OrderFact o = order("O3", "500", "completed");
        OrderAmountCompiler.Obs obs = compileOne(o, List.of(),
                List.of(pay("P1", "O3", "cash", "450")), List.of(), List.of());
        assertEquals(OrderAmountCompiler.PENDING, obs.state());
        assertNull(obs.net(), "待核订单不给出净额观察值");
        assertTrue(obs.reason().contains("不一致"));
    }

    @Test
    void duplicateReceiptsDoNotDuplicateOrder() {
        OrderFact o = order("O4", "260", "completed");
        List<OrderReceipt> receipts = List.of(
                receipt("RC-1", "O4", "completed", "2026-02-03T10:05:00Z", "2026-02-03T10:05:05Z"),
                receipt("RC-1", "O4", "completed", "2026-02-03T10:05:00Z", "2026-02-03T10:05:07Z"));
        OrderAmountCompiler.Obs obs = compileOne(o, List.of(), List.of(), receipts, List.of());
        assertEquals(OrderAmountCompiler.COMPLETED, obs.state());
        assertEquals(0, new BigDecimal("260").compareTo(obs.net()));
    }

    @Test
    void conflictingReceiptsArePending() {
        OrderFact o = order("O5", "300", "completed");
        List<OrderReceipt> receipts = List.of(
                receipt("RC-1", "O5", "completed", "2026-02-03T12:00:00Z", "2026-02-03T12:00:05Z"),
                receipt("RC-2", "O5", "void", "2026-02-03T12:00:00Z", "2026-02-03T12:00:06Z"));
        OrderAmountCompiler.Obs obs = compileOne(o, List.of(), List.of(), receipts, List.of());
        assertEquals(OrderAmountCompiler.PENDING, obs.state());
        assertTrue(obs.reason().contains("回执冲突"));
    }

    @Test
    void voidOrderExcluded() {
        OrderAmountCompiler.Obs obs = compileOne(order("O6", "150", "void"), List.of(), List.of(), List.of(), List.of());
        assertEquals(OrderAmountCompiler.EXCLUDED, obs.state());
        assertTrue(obs.reason().contains("撤销"));
    }

    @Test
    void unpaidOrderExcluded() {
        OrderAmountCompiler.Obs obs = compileOne(order("O7", "200", "unpaid"), List.of(), List.of(), List.of(), List.of());
        assertEquals(OrderAmountCompiler.EXCLUDED, obs.state());
        assertTrue(obs.reason().contains("未完成支付"));
    }

    @Test
    void partialRefundRecomputesNetAtCutoff() {
        OrderFact o = order("O8", "800", "partial_refund");
        List<RefundFact> refunds = List.of(
                refund("R1", "O8", "200", "2026-02-06T10:00:00Z"),
                refund("R2", "O8", "100", "2026-02-07T10:00:00Z"),
                // 截止点之后的退款不计入（晚到退款属下一版本）
                refund("R3", "O8", "50", "2026-03-05T10:00:00Z"));
        OrderAmountCompiler.Obs obs = compileOne(o, List.of(), List.of(), List.of(), refunds);
        assertEquals(OrderAmountCompiler.COMPLETED, obs.state());
        assertEquals(0, new BigDecimal("300").compareTo(obs.refund()), "只计截止点内退款");
        assertEquals(0, new BigDecimal("500").compareTo(obs.net()));
    }

    @Test
    void fullRefundIsZeroObservation() {
        OrderFact o = order("O9", "500", "full_refund");
        OrderAmountCompiler.Obs obs = compileOne(o, List.of(), List.of(), List.of(),
                List.of(refund("R1", "O9", "500", "2026-02-06T10:00:00Z")));
        assertEquals(OrderAmountCompiler.COMPLETED, obs.state(), "全额退款仍是已完成交易");
        assertEquals(0, BigDecimal.ZERO.compareTo(obs.net()), "零金额规则：净额 0 计入分布");
    }

    @Test
    void overRefundIsPending() {
        OrderFact o = order("O10", "100", "partial_refund");
        OrderAmountCompiler.Obs obs = compileOne(o, List.of(), List.of(), List.of(),
                List.of(refund("R1", "O10", "150", "2026-02-06T10:00:00Z")));
        assertEquals(OrderAmountCompiler.PENDING, obs.state());
        assertTrue(obs.reason().contains("超过订单金额"));
    }

    @Test
    void foreignCurrencyExcluded() {
        OrderFact o = order("O11", "S001", LocalDate.of(2026, 2, 3), "100", "USD", "completed", "online");
        OrderAmountCompiler.Obs obs = compileOne(o, List.of(), List.of(), List.of(), List.of());
        assertEquals(OrderAmountCompiler.EXCLUDED, obs.state());
        assertTrue(obs.reason().contains("外币"));
    }

    @Test
    void isoWeekLabel() {
        assertEquals("2026-W06", OrderAmountCompiler.isoWeek(LocalDate.of(2026, 2, 3)));
        assertEquals("2026-W01", OrderAmountCompiler.isoWeek(LocalDate.of(2026, 1, 1)));
        assertEquals("2026-W07", OrderAmountCompiler.isoWeek(LocalDate.of(2026, 2, 11)));
    }

    @Test
    void sampleConservationAcrossStates() {
        // 样本守恒：N 个订单键 → N 个观察值，且 completed+pending+excluded = N
        List<OrderFact> orders = new ArrayList<>();
        orders.add(order("A1", "100", "completed"));
        orders.add(order("A2", "200", "void"));
        orders.add(order("A3", "300", "unpaid"));
        orders.add(order("A4", "400", "completed"));
        List<OrderAmountCompiler.Obs> out = OrderAmountCompiler.compile(
                new OrderAmountCompiler.Input(orders, List.of(), List.of(), List.of(), List.of(), CUTOFF));
        assertEquals(orders.size(), out.size());
        long sum = out.stream().filter(o -> OrderAmountCompiler.COMPLETED.equals(o.state())).count()
                + out.stream().filter(o -> OrderAmountCompiler.PENDING.equals(o.state())).count()
                + out.stream().filter(o -> OrderAmountCompiler.EXCLUDED.equals(o.state())).count();
        assertEquals(orders.size(), sum, "订单总体 = 有效样本 + 待核 + 排除");
    }
}
