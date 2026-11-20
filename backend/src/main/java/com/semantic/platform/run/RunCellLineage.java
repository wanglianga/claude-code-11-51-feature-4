package com.semantic.platform.run;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "run_cell_lineage")
public class RunCellLineage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "cell_key", nullable = false)
    private String cellKey;

    @Column(name = "record_type", nullable = false)
    private String recordType;

    @Column(name = "record_id", nullable = false)
    private String recordId;

    private BigDecimal contribution;

    @Column(nullable = false)
    private Boolean included = true;

    private String reason;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public String getCellKey() { return cellKey; }
    public void setCellKey(String cellKey) { this.cellKey = cellKey; }
    public String getRecordType() { return recordType; }
    public void setRecordType(String recordType) { this.recordType = recordType; }
    public String getRecordId() { return recordId; }
    public void setRecordId(String recordId) { this.recordId = recordId; }
    public BigDecimal getContribution() { return contribution; }
    public void setContribution(BigDecimal contribution) { this.contribution = contribution; }
    public Boolean getIncluded() { return included; }
    public void setIncluded(Boolean included) { this.included = included; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
