package com.semantic.platform.engine;

import com.semantic.platform.dsl.Expr;
import com.semantic.platform.source.OrderFact;
import com.semantic.platform.source.RefundFact;
import com.semantic.platform.source.StoreStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 运行引擎求值测试：聚合、覆盖范围、比率、同店队列。
 */
class EvaluatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private EvaluationContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new EvaluationContext(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "day");
        // 门店状态：S001 全月营业，S002 1/15 闭店
        for (int d = 1; d <= 31; d++) {
            ctx.storeStatus.add(status("S001", LocalDate.of(2026, 1, d), "open"));
            ctx.storeStatus.add(status("S002", LocalDate.of(2026, 1, d), d == 15 ? "closed" : "open"));
        }
        // 订单：S001 1/10 两笔（500、300），S002 1/15 一笔（闭店，不应有）
        ctx.orders.add(order("O1", "S001", LocalDate.of(2026, 1, 10), "500"));
        ctx.orders.add(order("O2", "S001", LocalDate.of(2026, 1, 10), "300"));
        ctx.orders.add(order("O3", "S002", LocalDate.of(2026, 1, 20), "400"));
        // 退款：S001 1/10 一笔 100
        ctx.refunds.add(refund("R1", "O1", "S001", LocalDate.of(2026, 1, 10), "100"));
        // 覆盖：S001 全月覆盖，S002 仅 1/1-1/10 覆盖（1/11 起缺批次）
        for (int d = 1; d <= 31; d++) {
            ctx.addCoverage("orders", "S001", LocalDate.of(2026, 1, d));
            ctx.addCoverage("refunds", "S001", LocalDate.of(2026, 1, d));
            ctx.addCoverage("store_status", "S001", LocalDate.of(2026, 1, d));
        }
        for (int d = 1; d <= 10; d++) {
            ctx.addCoverage("orders", "S002", LocalDate.of(2026, 1, d));
        }
    }

    private StoreStatus status(String store, LocalDate date, String s) {
        StoreStatus st = new StoreStatus();
        st.setStoreId(store);
        st.setBusinessDate(date);
        st.setStatus(s);
        return st;
    }

    private OrderFact order(String id, String store, LocalDate date, String amount) {
        OrderFact o = new OrderFact();
        o.setOrderId(id);
        o.setStoreId(store);
        o.setOrderedAt(OffsetDateTime.now());
        o.setBusinessDate(date);
        o.setAmount(new BigDecimal(amount));
        o.setCurrency("CNY");
        o.setStatus("completed");
        return o;
    }

    private RefundFact refund(String id, String orderId, String store, LocalDate date, String amount) {
        RefundFact r = new RefundFact();
        r.setRefundId(id);
        r.setOrderId(orderId);
        r.setStoreId(store);
        r.setRefundedAt(OffsetDateTime.now());
        r.setBusinessDate(date);
        r.setAmount(new BigDecimal(amount));
        return r;
    }

    private Expr parse(String json) throws Exception {
        return mapper.readValue(json, Expr.class);
    }

    @Test
    void testSumAggregation() throws Exception {
        Expr expr = parse("{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"amount\"},\"by\":[\"store_id\"]}");
        Evaluator ev = new Evaluator(ctx);
        Series s = ev.eval(expr);
        CellKey key = CellKey.of("2026-01-10", Map.of("store_id", "S001"));
        CellValue cell = s.get(key);
        assertNotNull(cell, "S001 1/10 应有单元格");
        assertEquals(0, new BigDecimal("800").compareTo(cell.getNum()), "S001 1/10 销售额应为 800");
    }

    @Test
    void testNotCoveredVsZero() throws Exception {
        // S002 1/20 有订单但订单批次仅覆盖到 1/10 → 该单元格应 NOT_COVERED（不是零）
        Expr expr = parse("{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"amount\"},\"by\":[\"store_id\"]}");
        Evaluator ev = new Evaluator(ctx);
        Series s = ev.eval(expr);
        CellKey key = CellKey.of("2026-01-20", Map.of("store_id", "S002"));
        CellValue cell = s.get(key);
        assertNotNull(cell);
        assertEquals(CellValue.Status.NOT_COVERED, cell.getStatus(), "S002 1/20 未覆盖应标记 NOT_COVERED");
    }

    @Test
    void testRatioNumDen() throws Exception {
        // 净销售额 / 订单数 → 比率，分子分母分别存
        ctx.refTypes = Map.of();
        ctx.refResolver = k -> null;
        Expr expr = parse("{\"op\":\"arith\",\"operator\":\"div\"," +
                "\"left\":{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"amount\"},\"by\":[\"store_id\"]}," +
                "\"right\":{\"op\":\"agg\",\"measure\":\"count\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"order_id\"},\"by\":[\"store_id\"]}}");
        Evaluator ev = new Evaluator(ctx);
        Series s = ev.eval(expr);
        CellKey key = CellKey.of("2026-01-10", Map.of("store_id", "S001"));
        CellValue cell = s.get(key);
        assertNotNull(cell);
        assertEquals(0, new BigDecimal("800").compareTo(cell.getNum()), "分子应为 800");
        assertEquals(0, new BigDecimal("2").compareTo(cell.getDen()), "分母应为 2");
        assertEquals(0, new BigDecimal("400.000000").compareTo(cell.value()), "客单价应为 400");
    }

    @Test
    void testDivZero() throws Exception {
        // 除以零 → DIV_ZERO
        Expr expr = parse("{\"op\":\"arith\",\"operator\":\"div\"," +
                "\"left\":{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"amount\"},\"by\":[\"store_id\"]}," +
                "\"right\":{\"op\":\"const\",\"value\":0}}");
        Evaluator ev = new Evaluator(ctx);
        Series s = ev.eval(expr);
        CellKey key = CellKey.of("2026-01-10", Map.of("store_id", "S001"));
        CellValue cell = s.get(key);
        assertNotNull(cell);
        assertEquals(CellValue.Status.DIV_ZERO, cell.getStatus(), "分母为零应标记 DIV_ZERO");
    }

    @Test
    void testClosedStoreIsLegitimateZero() throws Exception {
        // S002 1/15 闭店 → 销售额为合法零（不是缺数据）
        Expr expr = parse("{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"amount\"},\"by\":[\"store_id\"]}");
        Evaluator ev = new Evaluator(ctx);
        Series s = ev.eval(expr);
        CellKey key = CellKey.of("2026-01-15", Map.of("store_id", "S002"));
        CellValue cell = s.get(key);
        assertNotNull(cell);
        // 闭店视为已覆盖的合法零
        assertNotEquals(CellValue.Status.NOT_COVERED, cell.getStatus(), "闭店不应标记为未覆盖");
    }
}
