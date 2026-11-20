package com.semantic.platform.distribution;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 订单金额观察值：每个稳定订单键在每个编译版本一行。
 * 查询时取每个订单键 compile_id 最大的一行作为当前值；历史行保留用于审计与复算。
 */
@Entity
@Table(name = "order_amount_obs")
public class OrderAmountObs {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_key", nullable = false)
    private String orderKey;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "biz_week", nullable = false)
    private String bizWeek;

    private String channel;

    @Column(nullable = false)
    private String currency = "CNY";

    @Column(name = "gross_amount")
    private BigDecimal grossAmount;

    @Column(name = "refund_amount", nullable = false)
    private BigDecimal refundAmount = BigDecimal.ZERO;

    @Column(name = "net_amount")
    private BigDecimal netAmount;

    @Column(nullable = false)
    private String state;

    @Column(name = "state_reason")
    private String stateReason;

    @Column(name = "amount_source")
    private String amountSource;

    @Column(name = "compile_id", nullable = false)
    private Long compileId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOrderKey() { return orderKey; }
    public void setOrderKey(String orderKey) { this.orderKey = orderKey; }
    public String getStoreId() { return storeId; }
    public void setStoreId(String storeId) { this.storeId = storeId; }
    public LocalDate getBusinessDate() { return businessDate; }
    public void setBusinessDate(LocalDate businessDate) { this.businessDate = businessDate; }
    public String getBizWeek() { return bizWeek; }
    public void setBizWeek(String bizWeek) { this.bizWeek = bizWeek; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public BigDecimal getGrossAmount() { return grossAmount; }
    public void setGrossAmount(BigDecimal grossAmount) { this.grossAmount = grossAmount; }
    public BigDecimal getRefundAmount() { return refundAmount; }
    public void setRefundAmount(BigDecimal refundAmount) { this.refundAmount = refundAmount; }
    public BigDecimal getNetAmount() { return netAmount; }
    public void setNetAmount(BigDecimal netAmount) { this.netAmount = netAmount; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getStateReason() { return stateReason; }
    public void setStateReason(String stateReason) { this.stateReason = stateReason; }
    public String getAmountSource() { return amountSource; }
    public void setAmountSource(String amountSource) { this.amountSource = amountSource; }
    public Long getCompileId() { return compileId; }
    public void setCompileId(Long compileId) { this.compileId = compileId; }
}
