package com.semantic.platform.report;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "report_item")
public class ReportItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Column(name = "metric_key", nullable = false)
    private String metricKey;

    @Column(name = "metric_version_id", nullable = false)
    private Long metricVersionId;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "display_json", columnDefinition = "jsonb")
    private String displayJson;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getReportId() { return reportId; }
    public void setReportId(Long reportId) { this.reportId = reportId; }
    public String getMetricKey() { return metricKey; }
    public void setMetricKey(String metricKey) { this.metricKey = metricKey; }
    public Long getMetricVersionId() { return metricVersionId; }
    public void setMetricVersionId(Long metricVersionId) { this.metricVersionId = metricVersionId; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public String getDisplayJson() { return displayJson; }
    public void setDisplayJson(String displayJson) { this.displayJson = displayJson; }
}
