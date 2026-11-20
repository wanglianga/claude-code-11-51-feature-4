package com.semantic.platform.source;

import com.semantic.platform.metric.MetricService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 样例数据播种：订单/订单行/退款/门店状态/节假日/批次，以及预置指标与报表。
 * 覆盖回放场景：跨营业日订单、多次部分退款、临时闭店、跨期新店、缺批次数据、迟到退款。
 * 分布查询场景：多行商品拆单、多渠道支付、重复/冲突回执、零额/撤销/未支付订单、
 * 大额团购长尾、待核订单、去重决议演示订单对。
 */
@Component
@Order(0) // 先于 DistBootstrap（@Order(10)）运行
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final OrderFactRepository orderRepo;
    private final OrderLineRepository orderLineRepo;
    private final RefundFactRepository refundRepo;
    private final StoreStatusRepository storeStatusRepo;
    private final HolidayRepository holidayRepo;
    private final SourceBatchRepository batchRepo;
    private final PaymentFactRepository paymentRepo;
    private final OrderReceiptRepository receiptRepo;
    private final MetricService metricService;

    public DataSeeder(OrderFactRepository orderRepo, OrderLineRepository orderLineRepo,
                      RefundFactRepository refundRepo, StoreStatusRepository storeStatusRepo,
                      HolidayRepository holidayRepo, SourceBatchRepository batchRepo,
                      PaymentFactRepository paymentRepo, OrderReceiptRepository receiptRepo,
                      MetricService metricService) {
        this.orderRepo = orderRepo;
        this.orderLineRepo = orderLineRepo;
        this.refundRepo = refundRepo;
        this.storeStatusRepo = storeStatusRepo;
        this.holidayRepo = holidayRepo;
        this.batchRepo = batchRepo;
        this.paymentRepo = paymentRepo;
        this.receiptRepo = receiptRepo;
        this.metricService = metricService;
    }

    @Override
    public void run(String... args) {
        if (orderRepo.count() > 0) {
            log.info("样例数据已存在，跳过播种");
            return;
        }
        log.info("开始播种样例数据...");
        seedStoreStatus();
        seedHolidays();
        seedOrders();
        seedRefunds();
        seedDistributionSamples();
        seedBatches();
        seedMetrics();
        log.info("样例数据播种完成");
    }

    // ---------- 门店营业状态 ----------
    private void seedStoreStatus() {
        List<StoreStatus> list = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 3, 31);
        String[] stores = {"S001", "S002", "S003", "S004", "S005"};
        for (String store : stores) {
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                StoreStatus s = new StoreStatus();
                s.setStoreId(store);
                s.setBusinessDate(d);
                // S004 新店 2/10 才开业
                if (store.equals("S004") && d.isBefore(LocalDate.of(2026, 2, 10))) {
                    s.setStatus("closed");
                    s.setOpenHours(BigDecimal.ZERO);
                }
                // S005 2/15-2/21 临时闭店
                else if (store.equals("S005") && !d.isBefore(LocalDate.of(2026, 2, 15)) && !d.isAfter(LocalDate.of(2026, 2, 21))) {
                    s.setStatus("temp_closed");
                    s.setOpenHours(BigDecimal.ZERO);
                } else {
                    s.setStatus("open");
                    s.setOpenHours(new BigDecimal("13"));
                }
                list.add(s);
            }
        }
        storeStatusRepo.saveAll(list);
    }

    // ---------- 节假日 ----------
    private void seedHolidays() {
        holidayRepo.save(holiday(LocalDate.of(2026, 1, 1), "元旦", "public_holiday"));
        holidayRepo.save(holiday(LocalDate.of(2026, 2, 16), "春节", "public_holiday"));
        holidayRepo.save(holiday(LocalDate.of(2026, 2, 17), "春节", "public_holiday"));
        holidayRepo.save(holiday(LocalDate.of(2026, 2, 18), "春节", "public_holiday"));
    }

    private Holiday holiday(LocalDate date, String name, String type) {
        Holiday h = new Holiday();
        h.setHolidayDate(date);
        h.setName(name);
        h.setType(type);
        return h;
    }

    // ---------- 订单（含跨营业日、多商品、迟到补录、外币） ----------
    private void seedOrders() {
        // S001 深夜门店（营业到次日 02:00）
        order("O001", "S001", "2026-01-09T20:00:00Z", "2026-01-09T20:05:00Z", LocalDate.of(2026, 1, 9), "500.00", "CNY", "completed", "pos");
        // 跨营业日：1/10 01:15 下单，计入 1/9 营业日
        order("O002", "S001", "2026-01-10T01:15:00Z", "2026-01-10T01:16:00Z", LocalDate.of(2026, 1, 9), "300.00", "CNY", "completed", "pos");
        // 多商品订单（3 行，合计 900）
        order("O003", "S001", "2026-01-15T12:00:00Z", "2026-01-15T12:01:00Z", LocalDate.of(2026, 1, 15), "900.00", "CNY", "completed", "pos");
        orderLine("L001", "O003", "S001", LocalDate.of(2026, 1, 15), "SKU-A", 2, "200.00", "400.00");
        orderLine("L002", "O003", "S001", LocalDate.of(2026, 1, 15), "SKU-B", 1, "300.00", "300.00");
        orderLine("L003", "O003", "S001", LocalDate.of(2026, 1, 15), "SKU-C", 2, "100.00", "200.00");
        // 外币订单（USD），用于币种校验演示
        order("O004", "S001", "2026-01-20T10:00:00Z", "2026-01-20T10:01:00Z", LocalDate.of(2026, 1, 20), "100.00", "USD", "completed", "online");
        // 部分退款订单
        order("O005", "S001", "2026-02-05T14:00:00Z", "2026-02-05T14:01:00Z", LocalDate.of(2026, 2, 5), "800.00", "CNY", "partial_refund", "pos");
        // S001 2月其它订单
        order("O006", "S001", "2026-02-10T11:00:00Z", "2026-02-10T11:01:00Z", LocalDate.of(2026, 2, 10), "650.00", "CNY", "completed", "pos");
        order("O007", "S001", "2026-03-05T11:00:00Z", "2026-03-05T11:01:00Z", LocalDate.of(2026, 3, 5), "720.00", "CNY", "completed", "pos");

        // S002 普通门店（22:00 闭店）
        order("O101", "S002", "2026-01-20T21:00:00Z", "2026-01-20T21:01:00Z", LocalDate.of(2026, 1, 20), "400.00", "CNY", "completed", "pos");
        // 迟到补录：1/20 23:30（闭店后）下单，补录计入下一营业日 1/21
        order("O102", "S002", "2026-01-20T23:30:00Z", "2026-01-20T23:31:00Z", LocalDate.of(2026, 1, 21), "250.00", "CNY", "completed", "pos");
        order("O103", "S002", "2026-02-10T10:00:00Z", "2026-02-10T10:01:00Z", LocalDate.of(2026, 2, 10), "600.00", "CNY", "completed", "pos");
        order("O104", "S002", "2026-03-08T10:00:00Z", "2026-03-08T10:01:00Z", LocalDate.of(2026, 3, 8), "550.00", "CNY", "completed", "pos");

        // S003（2/1-2/7 缺批次）
        order("O201", "S003", "2026-01-15T10:00:00Z", "2026-01-15T10:01:00Z", LocalDate.of(2026, 1, 15), "700.00", "CNY", "completed", "pos");
        order("O202", "S003", "2026-02-10T10:00:00Z", "2026-02-10T10:01:00Z", LocalDate.of(2026, 2, 10), "450.00", "CNY", "completed", "pos");
        order("O203", "S003", "2026-03-03T10:00:00Z", "2026-03-03T10:01:00Z", LocalDate.of(2026, 3, 3), "520.00", "CNY", "completed", "pos");

        // S004 新店（2/10 开业）
        order("O301", "S004", "2026-02-15T10:00:00Z", "2026-02-15T10:01:00Z", LocalDate.of(2026, 2, 15), "550.00", "CNY", "completed", "pos");
        order("O302", "S004", "2026-03-10T10:00:00Z", "2026-03-10T10:01:00Z", LocalDate.of(2026, 3, 10), "610.00", "CNY", "completed", "pos");

        // S005（2/15-2/21 临时闭店）
        order("O401", "S005", "2026-02-10T10:00:00Z", "2026-02-10T10:01:00Z", LocalDate.of(2026, 2, 10), "380.00", "CNY", "completed", "pos");
        order("O402", "S005", "2026-02-25T10:00:00Z", "2026-02-25T10:01:00Z", LocalDate.of(2026, 2, 25), "420.00", "CNY", "completed", "pos");
        order("O403", "S005", "2026-01-12T10:00:00Z", "2026-01-12T10:01:00Z", LocalDate.of(2026, 1, 12), "460.00", "CNY", "completed", "pos");
    }

    private void order(String id, String store, String orderedAt, String paidAt, LocalDate businessDate,
                       String amount, String currency, String status, String channel) {
        OrderFact o = new OrderFact();
        o.setOrderId(id);
        o.setStoreId(store);
        o.setOrderedAt(OffsetDateTime.parse(orderedAt));
        o.setPaidAt(paidAt == null ? null : OffsetDateTime.parse(paidAt));
        o.setBusinessDate(businessDate);
        o.setAmount(new BigDecimal(amount));
        o.setCurrency(currency);
        o.setStatus(status);
        o.setChannel(channel);
        orderRepo.save(o);
    }

    private void orderLine(String lineId, String orderId, String store, LocalDate businessDate,
                           String sku, int qty, String price, String amount) {
        OrderLine l = new OrderLine();
        l.setLineId(lineId);
        l.setOrderId(orderId);
        l.setStoreId(store);
        l.setBusinessDate(businessDate);
        l.setSku(sku);
        l.setQuantity(qty);
        l.setUnitPrice(new BigDecimal(price));
        l.setAmount(new BigDecimal(amount));
        orderLineRepo.save(l);
    }

    // ---------- 退款（含多次部分退款；迟到退款作为回放场景单独处理） ----------
    private void seedRefunds() {
        // O005 两次部分退款
        refund("R001", "O005", "S001", "2026-02-06T10:00:00Z", LocalDate.of(2026, 2, 6), "200.00", "质量问题");
        refund("R002", "O005", "S001", "2026-02-07T10:00:00Z", LocalDate.of(2026, 2, 7), "100.00", "少发");
        // 迟到退款 R003（O001，1月订单）在批次回放中作为“迟到退款”到达，不预置
    }

    private void refund(String id, String orderId, String store, String refundedAt, LocalDate businessDate,
                        String amount, String reason) {
        RefundFact r = new RefundFact();
        r.setRefundId(id);
        r.setOrderId(orderId);
        r.setStoreId(store);
        r.setRefundedAt(OffsetDateTime.parse(refundedAt));
        r.setBusinessDate(businessDate);
        r.setAmount(new BigDecimal(amount));
        r.setReason(reason);
        refundRepo.save(r);
    }

    // ---------- 分布查询样例 ----------
    // 密集小额日常单 + 长尾大额团购（平均客单价平稳、分布结构不同），
    // 以及多行拆单、多渠道支付、重复/冲突回执、零额/撤销/未支付、待核订单。
    private void seedDistributionSamples() {
        String[] ch = {"pos", "online", "delivery"};
        // S001 营业周 W06（2026-02-02 ~ 02-08）：38 笔小额日常单（25~95 元）
        for (int i = 0; i < 38; i++) {
            LocalDate d = LocalDate.of(2026, 2, 2).plusDays(i % 7);
            order(String.format("O5%02d", i + 1), "S001", d + "T10:00:00Z", d + "T10:01:00Z", d,
                    (25 + (i * 7) % 71) + ".00", "CNY", "completed", ch[i % 3]);
        }
        // S001 营业周 W07（2026-02-09 ~ 02-15）：30 笔小额日常单（30~90 元）
        for (int i = 0; i < 30; i++) {
            LocalDate d = LocalDate.of(2026, 2, 9).plusDays(i % 7);
            order(String.format("O6%02d", i + 1), "S001", d + "T11:00:00Z", d + "T11:01:00Z", d,
                    (30 + (i * 11) % 61) + ".00", "CNY", "completed", ch[(i + 1) % 3]);
        }
        // S002 W06：12 笔小额单；W07：仅 3 笔（小样本不可用演示）
        for (int i = 0; i < 12; i++) {
            LocalDate d = LocalDate.of(2026, 2, 2).plusDays(i % 7);
            order(String.format("O7%02d", i + 1), "S002", d + "T12:00:00Z", d + "T12:01:00Z", d,
                    (35 + (i * 5) % 46) + ".00", "CNY", "completed", ch[i % 3]);
        }
        String[][] s002w7 = {{"O801", "2026-02-09", "45.00"}, {"O802", "2026-02-10", "60.00"}, {"O803", "2026-02-11", "52.00"}};
        for (String[] r : s002w7) {
            order(r[0], "S002", r[1] + "T12:00:00Z", r[1] + "T12:01:00Z", LocalDate.parse(r[1]), r[2], "CNY", "completed", "pos");
        }

        // 长尾大额团购（groupon 渠道）
        order("O-GRP3", "S001", "2026-02-03T16:00:00Z", "2026-02-03T16:01:00Z", LocalDate.of(2026, 2, 3), "5200.00", "CNY", "completed", "groupon");
        order("O-GRP1", "S001", "2026-02-04T16:00:00Z", "2026-02-04T16:01:00Z", LocalDate.of(2026, 2, 4), "8600.00", "CNY", "completed", "groupon");
        order("O-GRP2", "S001", "2026-02-11T16:00:00Z", "2026-02-11T16:01:00Z", LocalDate.of(2026, 2, 11), "12000.00", "CNY", "completed", "groupon");

        // 零额订单（全额抵扣，净额 0 计入分布）
        order("O-ZERO", "S001", "2026-02-03T09:00:00Z", "2026-02-03T09:01:00Z", LocalDate.of(2026, 2, 3), "0.00", "CNY", "completed", "pos");
        // 撤销订单（不得混入已完成交易）
        order("O-VOID", "S001", "2026-02-03T09:30:00Z", null, LocalDate.of(2026, 2, 3), "150.00", "CNY", "void", "pos");
        receipt("RC-VOID-1", "O-VOID", "S001", "void", "150.00", "2026-02-03T09:35:00Z", "2026-02-03T09:35:05Z");
        // 未完成支付（不得混入已完成交易）
        order("O-UNPAID", "S001", "2026-02-04T09:00:00Z", null, LocalDate.of(2026, 2, 4), "200.00", "CNY", "unpaid", "online");

        // 重复回执（同一 receipt_id 到达两次，不得复制订单）
        order("O-DUP", "S001", "2026-02-04T10:00:00Z", "2026-02-04T10:01:00Z", LocalDate.of(2026, 2, 4), "260.00", "CNY", "completed", "pos");
        receipt("RC-DUP-1", "O-DUP", "S001", "completed", "260.00", "2026-02-04T10:05:00Z", "2026-02-04T10:05:05Z");
        receipt("RC-DUP-1", "O-DUP", "S001", "completed", "260.00", "2026-02-04T10:05:00Z", "2026-02-04T10:05:07Z");
        // O-DUP2 与 O-DUP 是同一真实订单的两个键（POS 重试产生），供去重决议演示
        order("O-DUP2", "S001", "2026-02-04T10:00:30Z", "2026-02-04T10:01:30Z", LocalDate.of(2026, 2, 4), "260.00", "CNY", "completed", "pos");

        // 多渠道支付（一张订单两个支付渠道，合计 = 订单金额）
        order("O-PAY2", "S001", "2026-02-04T13:00:00Z", "2026-02-04T13:01:00Z", LocalDate.of(2026, 2, 4), "320.00", "CNY", "completed", "pos");
        payment("P-101", "O-PAY2", "S001", "wechat", "120.00", "2026-02-04T13:01:00Z", LocalDate.of(2026, 2, 4));
        payment("P-102", "O-PAY2", "S001", "alipay", "200.00", "2026-02-04T13:01:00Z", LocalDate.of(2026, 2, 4));

        // 已有订单补充支付流水（O003 多行拆单 900 = 现金 400 + 卡 500）
        payment("P-001", "O003", "S001", "cash", "400.00", "2026-01-15T12:01:00Z", LocalDate.of(2026, 1, 15));
        payment("P-002", "O003", "S001", "card", "500.00", "2026-01-15T12:01:00Z", LocalDate.of(2026, 1, 15));
        // 团购单支付流水
        payment("P-301", "O-GRP1", "S001", "groupon", "8600.00", "2026-02-04T16:01:00Z", LocalDate.of(2026, 2, 4));
        payment("P-302", "O-GRP2", "S001", "groupon", "12000.00", "2026-02-11T16:01:00Z", LocalDate.of(2026, 2, 11));
        payment("P-303", "O-GRP3", "S001", "groupon", "5200.00", "2026-02-03T16:01:00Z", LocalDate.of(2026, 2, 3));

        // 冲突回执 → 无法确定最终状态 → 待核
        order("O-CONFLICT", "S002", "2026-02-04T11:30:00Z", "2026-02-04T11:31:00Z", LocalDate.of(2026, 2, 4), "300.00", "CNY", "completed", "pos");
        receipt("RC-CON-1", "O-CONFLICT", "S002", "completed", "300.00", "2026-02-04T12:00:00Z", "2026-02-04T12:00:05Z");
        receipt("RC-CON-2", "O-CONFLICT", "S002", "void", "300.00", "2026-02-04T12:00:00Z", "2026-02-04T12:00:06Z");
        // 支付合计与订单金额不一致（无订单行仲裁）→ 金额待核
        order("O-PAYX", "S002", "2026-02-05T14:00:00Z", "2026-02-05T14:01:00Z", LocalDate.of(2026, 2, 5), "500.00", "CNY", "completed", "pos");
        payment("P-201", "O-PAYX", "S002", "cash", "450.00", "2026-02-05T14:01:00Z", LocalDate.of(2026, 2, 5));

        // 正常回执流转示例（paid → completed）
        receipt("RC-501-1", "O501", "S001", "paid", "25.00", "2026-02-02T10:01:00Z", "2026-02-02T10:01:05Z");
        receipt("RC-501-2", "O501", "S001", "completed", "25.00", "2026-02-02T10:02:00Z", "2026-02-02T10:02:05Z");
    }

    private void payment(String id, String orderId, String store, String channel, String amount,
                         String paidAt, LocalDate businessDate) {
        PaymentFact p = new PaymentFact();
        p.setPaymentId(id);
        p.setOrderId(orderId);
        p.setStoreId(store);
        p.setChannel(channel);
        p.setAmount(new BigDecimal(amount));
        p.setPaidAt(OffsetDateTime.parse(paidAt));
        p.setBusinessDate(businessDate);
        paymentRepo.save(p);
    }

    private void receipt(String receiptId, String orderId, String store, String status, String amount,
                         String receiptAt, String receivedAt) {
        OrderReceipt r = new OrderReceipt();
        r.setReceiptId(receiptId);
        r.setOrderId(orderId);
        r.setStoreId(store);
        r.setStatus(status);
        r.setAmount(new BigDecimal(amount));
        r.setReceiptAt(OffsetDateTime.parse(receiptAt));
        r.setReceivedAt(OffsetDateTime.parse(receivedAt));
        receiptRepo.save(r);
    }

    // ---------- 批次（含缺批次、迟到批次） ----------
    private void seedBatches() {
        // 订单批次：按门店分批
        batch("orders", "S001", "loaded", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), 84);
        batch("orders", "S002", "loaded", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), 21);
        batch("orders", "S003", "loaded", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), 1);
        // S003 2/1-2/7 仍在补数（缺批次场景）
        batch("orders", "S003", "backfilling", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 7), 0);
        batch("orders", "S003", "loaded", LocalDate.of(2026, 2, 8), LocalDate.of(2026, 3, 31), 2);
        batch("orders", "S004", "loaded", LocalDate.of(2026, 2, 10), LocalDate.of(2026, 3, 31), 2);
        batch("orders", "S005", "loaded", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), 3);
        // 退款批次：先到一批（不含 R003）
        batch("refunds", null, "loaded", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 28), 2);
        // 支付流水与状态回执批次
        batch("payments", null, "loaded", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), 8);
        batch("receipts", null, "loaded", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), 6);
        // 门店状态与节假日批次
        batch("store_hours", null, "loaded", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), 450);
        batch("holidays", null, "loaded", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), 4);
    }

    private void batch(String type, String store, String status, LocalDate start, LocalDate end, int count) {
        SourceBatch b = new SourceBatch();
        b.setSourceType(type);
        b.setStoreId(store);
        b.setStatus(status);
        b.setPeriodStart(start);
        b.setPeriodEnd(end);
        b.setRecordCount(count);
        batchRepo.save(b);
    }

    // ---------- 预置指标 ----------
    private void seedMetrics() {
        // 毛销售额（仅 CNY，演示币种过滤）
        publish("gross_sales", "毛销售额", "已完成及部分退款订单的销售额（CNY）", "day",
                "{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"amount\"},"
                + "\"filter\":[{\"source\":\"orders\",\"field\":\"status\",\"operator\":\"in\",\"value\":[\"completed\",\"partial_refund\"]},"
                + "{\"source\":\"orders\",\"field\":\"currency\",\"operator\":\"eq\",\"value\":\"CNY\"}],"
                + "\"by\":[\"store_id\"]}");
        // 退款金额
        publish("refund_total", "退款金额", "退款合计", "day",
                "{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"refunds\",\"field\":\"amount\"},\"by\":[\"store_id\"]}");
        // 净销售额 = 毛销售额 - 退款
        publish("net_sales", "净销售额", "毛销售额减去退款", "day",
                "{\"op\":\"arith\",\"operator\":\"sub\",\"left\":{\"op\":\"ref\",\"metric\":\"gross_sales\"},\"right\":{\"op\":\"ref\",\"metric\":\"refund_total\"}}");
        // 订单数
        publish("order_count", "订单数", "已完成及部分退款订单数", "day",
                "{\"op\":\"agg\",\"measure\":\"count\",\"expr\":{\"op\":\"field\",\"source\":\"orders\",\"field\":\"order_id\"},"
                + "\"filter\":[{\"source\":\"orders\",\"field\":\"status\",\"operator\":\"in\",\"value\":[\"completed\",\"partial_refund\"]}],"
                + "\"by\":[\"store_id\"]}");
        // 客单价 = 净销售额 / 订单数
        publish("aov", "客单价", "净销售额 / 订单数", "day",
                "{\"op\":\"arith\",\"operator\":\"div\",\"left\":{\"op\":\"ref\",\"metric\":\"net_sales\"},\"right\":{\"op\":\"ref\",\"metric\":\"order_count\"}}");
        // 商品件数（演示订单行一对多）
        publish("units_sold", "商品件数", "订单行数量合计", "day",
                "{\"op\":\"agg\",\"measure\":\"sum\",\"expr\":{\"op\":\"field\",\"source\":\"order_lines\",\"field\":\"quantity\"},\"by\":[\"store_id\"]}");
        // 同店增长（环比）
        publish("same_store_growth", "同店增长", "同店队列净销售额环比增长", "month",
                "{\"op\":\"cohort\",\"base\":{\"op\":\"ref\",\"metric\":\"net_sales\"},\"compare\":\"prev_period\",\"cohortKey\":\"store_id\"}");
    }

    private void publish(String key, String name, String desc, String grain, String definition) {
        try {
            metricService.saveDraft(key, name, desc, grain, definition, "system");
            metricService.publish(key, 1, "system");
            log.info("发布指标 {} v1", key);
        } catch (Exception e) {
            log.warn("发布指标 {} 失败: {}", key, e.getMessage());
        }
    }
}
