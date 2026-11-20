package com.semantic.platform.run;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;

@Entity
@Table(name = "run_cell")
public class RunCell {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "period_key", nullable = false)
    private String periodKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dims_json", columnDefinition = "jsonb")
    private String dimsJson;

    @Column(name = "value_num")
    private BigDecimal valueNum;

    @Column(name = "value_den")
    private BigDecimal valueDen;

    @Column(name = "cell_status", nullable = false)
    private String cellStatus = "ok";

    private String message;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public String getPeriodKey() { return periodKey; }
    public void setPeriodKey(String periodKey) { this.periodKey = periodKey; }
    public String getDimsJson() { return dimsJson; }
    public void setDimsJson(String dimsJson) { this.dimsJson = dimsJson; }
    public BigDecimal getValueNum() { return valueNum; }
    public void setValueNum(BigDecimal valueNum) { this.valueNum = valueNum; }
    public BigDecimal getValueDen() { return valueDen; }
    public void setValueDen(BigDecimal valueDen) { this.valueDen = valueDen; }
    public String getCellStatus() { return cellStatus; }
    public void setCellStatus(String cellStatus) { this.cellStatus = cellStatus; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
