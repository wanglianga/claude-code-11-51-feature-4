package com.semantic.platform.distribution;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;

/** 分布查询发布快照：冻结发布时的样本与数值；后续局部重建不回写。 */
@Entity
@Table(name = "dist_publication")
public class DistPublication {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "params_json", nullable = false, columnDefinition = "jsonb")
    private String paramsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", nullable = false, columnDefinition = "jsonb")
    private String resultJson;

    @Column(name = "compile_id", nullable = false)
    private Long compileId;

    @Column(name = "refund_cutoff")
    private OffsetDateTime refundCutoff;

    @Column(name = "published_by")
    private String publishedBy;

    @Column(name = "published_at", nullable = false)
    private OffsetDateTime publishedAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getParamsJson() { return paramsJson; }
    public void setParamsJson(String paramsJson) { this.paramsJson = paramsJson; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
    public Long getCompileId() { return compileId; }
    public void setCompileId(Long compileId) { this.compileId = compileId; }
    public OffsetDateTime getRefundCutoff() { return refundCutoff; }
    public void setRefundCutoff(OffsetDateTime refundCutoff) { this.refundCutoff = refundCutoff; }
    public String getPublishedBy() { return publishedBy; }
    public void setPublishedBy(String publishedBy) { this.publishedBy = publishedBy; }
    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(OffsetDateTime publishedAt) { this.publishedAt = publishedAt; }
}
