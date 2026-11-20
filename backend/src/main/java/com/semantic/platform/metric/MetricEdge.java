package com.semantic.platform.metric;

import jakarta.persistence.*;

@Entity
@Table(name = "metric_edge")
public class MetricEdge {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_metric", nullable = false)
    private String fromMetric;

    @Column(name = "to_metric", nullable = false)
    private String toMetric;

    @Column(name = "from_version", nullable = false)
    private Integer fromVersion;

    @Column(name = "edge_type", nullable = false)
    private String edgeType = "ref";

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFromMetric() { return fromMetric; }
    public void setFromMetric(String fromMetric) { this.fromMetric = fromMetric; }
    public String getToMetric() { return toMetric; }
    public void setToMetric(String toMetric) { this.toMetric = toMetric; }
    public Integer getFromVersion() { return fromVersion; }
    public void setFromVersion(Integer fromVersion) { this.fromVersion = fromVersion; }
    public String getEdgeType() { return edgeType; }
    public void setEdgeType(String edgeType) { this.edgeType = edgeType; }
}
