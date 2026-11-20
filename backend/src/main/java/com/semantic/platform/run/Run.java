package com.semantic.platform.run;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "run")
public class Run {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "metric_key", nullable = false)
    private String metricKey;

    @Column(name = "metric_version_id", nullable = false)
    private Long metricVersionId;

    @Column(nullable = false)
    private String grain = "day";

    @Column(name = "period_from")
    private LocalDate periodFrom;

    @Column(name = "period_to")
    private LocalDate periodTo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scope_json", columnDefinition = "jsonb")
    private String scopeJson;

    @Column(nullable = false)
    private String status = "success";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_cutoff_json", columnDefinition = "jsonb")
    private String dataCutoffJson;

    @Column(name = "result_kind", nullable = false)
    private String resultKind = "metric";

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt = OffsetDateTime.now();

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    private String error;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMetricKey() { return metricKey; }
    public void setMetricKey(String metricKey) { this.metricKey = metricKey; }
    public Long getMetricVersionId() { return metricVersionId; }
    public void setMetricVersionId(Long metricVersionId) { this.metricVersionId = metricVersionId; }
    public String getGrain() { return grain; }
    public void setGrain(String grain) { this.grain = grain; }
    public LocalDate getPeriodFrom() { return periodFrom; }
    public void setPeriodFrom(LocalDate periodFrom) { this.periodFrom = periodFrom; }
    public LocalDate getPeriodTo() { return periodTo; }
    public void setPeriodTo(LocalDate periodTo) { this.periodTo = periodTo; }
    public String getScopeJson() { return scopeJson; }
    public void setScopeJson(String scopeJson) { this.scopeJson = scopeJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDataCutoffJson() { return dataCutoffJson; }
    public void setDataCutoffJson(String dataCutoffJson) { this.dataCutoffJson = dataCutoffJson; }
    public String getResultKind() { return resultKind; }
    public void setResultKind(String resultKind) { this.resultKind = resultKind; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
}
