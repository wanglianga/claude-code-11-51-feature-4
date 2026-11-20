package com.semantic.platform.source;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 订单状态回执：同一 receipt_id 可重复到达（重复回执），
 * 编译时按 receipt_id 去重，取最新 receipt_at 的业务状态。
 */
@Entity
@Table(name = "order_receipt")
public class OrderReceipt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "receipt_id", nullable = false)
    private String receiptId;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(nullable = false)
    private String status;

    private BigDecimal amount;

    @Column(name = "receipt_at", nullable = false)
    private OffsetDateTime receiptAt;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt = OffsetDateTime.now();

    @Column(name = "created_batch")
    private Long createdBatch;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getReceiptId() { return receiptId; }
    public void setReceiptId(String receiptId) { this.receiptId = receiptId; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getStoreId() { return storeId; }
    public void setStoreId(String storeId) { this.storeId = storeId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public OffsetDateTime getReceiptAt() { return receiptAt; }
    public void setReceiptAt(OffsetDateTime receiptAt) { this.receiptAt = receiptAt; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(OffsetDateTime receivedAt) { this.receivedAt = receivedAt; }
    public Long getCreatedBatch() { return createdBatch; }
    public void setCreatedBatch(Long createdBatch) { this.createdBatch = createdBatch; }
    }
