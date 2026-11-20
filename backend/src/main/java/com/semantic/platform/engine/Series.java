package com.semantic.platform.engine;

import java.util.Map;
import java.util.TreeMap;

/** 一个表达式求值得到的结果序列：cellKey -> value。 */
public class Series {

    private final Map<CellKey, CellValue> cells = new TreeMap<>();
    private MetricTypeLite type;

    /** 轻量类型（避免引擎依赖校验器）：值类别/币种/粒度/维度。 */
    public record MetricTypeLite(String kind, String currency, String timeGrain, java.util.Set<String> dims) {}

    public Map<CellKey, CellValue> cells() { return cells; }

    public CellValue get(CellKey k) { return cells.get(k); }

    public void put(CellKey k, CellValue v) { cells.put(k, v); }

    public MetricTypeLite getType() { return type; }
    public void setType(MetricTypeLite type) { this.type = type; }

    public int size() { return cells.size(); }
}
