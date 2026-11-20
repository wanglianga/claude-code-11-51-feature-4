package com.semantic.platform.distribution;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 近似分位摘要：t-digest 质心，粒度为 门店 × 营业周 × 渠道（最细）。
 * 查询更粗范围（多周、全渠道、多门店）时按可合并性质向上合并。
 * 同一范围可有多行（不同编译版本），查询取 compile_id 最大的一行。
 */
@Entity
@Table(name = "dist_summary")
public class DistSummary {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "biz_week", nullable = false)
    private String bizWeek;

    @Column(name = "dim_channel", nullable = false)
    private String dimChannel;

    @Column(name = "compile_id", nullable = false)
    private Long compileId;

    @Column(name = "sample_count", nullable = false)
    private int sampleCount;

    @Column(name = "sum_amount", nullable = false)
    private BigDecimal sumAmount;

    @Column(name = "min_amount")
    private BigDecimal minAmount;

    @Column(name = "max_amount")
    private BigDecimal maxAmount;

    @Column(name = "sketch_json", nullable = false, columnDefinition = "TEXT")
    private String sketchJson;

    @Column(name = "built_at", nullable = false)
    private OffsetDateTime builtAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStoreId() { return storeId; }
    public void setStoreId(String storeId) { this.storeId = storeId; }
    public String getBizWeek() { return bizWeek; }
    public void setBizWeek(String bizWeek) { this.bizWeek = bizWeek; }
    public String getDimChannel() { return dimChannel; }
    public void setDimChannel(String dimChannel) { this.dimChannel = dimChannel; }
    public Long getCompileId() { return compileId; }
    public void setCompileId(Long compileId) { this.compileId = compileId; }
    public int getSampleCount() { return sampleCount; }
    public void setSampleCount(int sampleCount) { this.sampleCount = sampleCount; }
    public BigDecimal getSumAmount() { return sumAmount; }
    public void setSumAmount(BigDecimal sumAmount) { this.sumAmount = sumAmount; }
    public BigDecimal getMinAmount() { return minAmount; }
    public void setMinAmount(BigDecimal minAmount) { this.minAmount = minAmount; }
    public BigDecimal getMaxAmount() { return maxAmount; }
    public void setMaxAmount(BigDecimal maxAmount) { this.maxAmount = maxAmount; }
    public String getSketchJson() { return sketchJson; }
    public void setSketchJson(String sketchJson) { this.sketchJson = sketchJson; }
    public OffsetDateTime getBuiltAt() { return builtAt; }
    public void setBuiltAt(OffsetDateTime builtAt) { this.builtAt = builtAt; }
}
