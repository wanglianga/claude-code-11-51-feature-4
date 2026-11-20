package com.semantic.platform.source;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "refund_fact")
public class RefundFact {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "refund_id", nullable = false, unique = true)
    private String refundId;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "refunded_at", nullable = false)
    private OffsetDateTime refundedAt;

    @Column(name = "business_date")
    private LocalDate businessDate;

    @Column(nullable = false)
    private BigDecimal amount;

    private String reason;

    @Column(name = "created_batch")
    private Long createdBatch;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRefundId() { return refundId; }
    public void setRefundId(String refundId) { this.refundId = refundId; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getStoreId() { return storeId; }
    public void setStoreId(String storeId) { this.storeId = storeId; }
    public OffsetDateTime getRefundedAt() { return refundedAt; }
    public void setRefundedAt(OffsetDateTime refundedAt) { this.refundedAt = refundedAt; }
    public LocalDate getBusinessDate() { return businessDate; }
    public void setBusinessDate(LocalDate businessDate) { this.businessDate = businessDate; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getCreatedBatch() { return createdBatch; }
    public void setCreatedBatch(Long createdBatch) { this.createdBatch = createdBatch; }
}
