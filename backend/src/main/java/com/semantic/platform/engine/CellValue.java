package com.semantic.platform.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 单元格的值：分子/分母（比率分别存，便于上卷时重新汇总）+ 状态 + 血缘。
 * 普通值：num=值，den=null。比率：num/den。
 */
public class CellValue {

    public enum Status { OK, NOT_COVERED, DIV_ZERO, NO_DATA, PARTIAL, EXCLUDED }

    public record Lineage(String recordType, String recordId, BigDecimal contribution,
                          boolean included, String reason) {}

    private BigDecimal num;
    private BigDecimal den;
    private Status status = Status.OK;
    private String message;
    private String trace; // 算式追踪，如 "(SUM(orders.amount)=12500.00) - (SUM(refunds.amount)=300.00)"
    private final List<Lineage> lineage = new ArrayList<>();

    public CellValue() {}

    public static CellValue of(BigDecimal value) {
        CellValue c = new CellValue();
        c.num = value;
        return c;
    }

    public static CellValue ratio(BigDecimal num, BigDecimal den) {
        CellValue c = new CellValue();
        c.num = num;
        c.den = den;
        return c;
    }

    public static CellValue status(Status s, String message) {
        CellValue c = new CellValue();
        c.status = s;
        c.message = message;
        return c;
    }

    /** 展示值：比率= num/den，普通=num。分母零返回 null（由调用方按 DIV_ZERO 处理）。 */
    public BigDecimal value() {
        if (den != null) {
            if (den.compareTo(BigDecimal.ZERO) == 0) return null;
            return num.divide(den, 6, java.math.RoundingMode.HALF_UP);
        }
        return num;
    }

    public void addLineage(String type, String id, BigDecimal contribution, boolean included, String reason) {
        lineage.add(new Lineage(type, id, contribution, included, reason));
    }

    public void addAllLineage(List<Lineage> l) { lineage.addAll(l); }

    // getters / setters
    public BigDecimal getNum() { return num; }
    public void setNum(BigDecimal num) { this.num = num; }
    public BigDecimal getDen() { return den; }
    public void setDen(BigDecimal den) { this.den = den; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getTrace() { return trace; }
    public void setTrace(String trace) { this.trace = trace; }
    public List<Lineage> getLineage() { return lineage; }
}
