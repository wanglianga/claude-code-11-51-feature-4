package com.semantic.platform.engine;

import java.util.Map;
import java.util.TreeMap;

/** 结果单元格的键：期间 + 维度值集合（维度名有序，保证可比较）。 */
public record CellKey(String periodKey, TreeMap<String, String> dims) implements Comparable<CellKey> {

    public static CellKey of(String periodKey, Map<String, String> dims) {
        return new CellKey(periodKey, new TreeMap<>(dims));
    }

    /** 用于血缘表存储的字符串键。 */
    public String asString() {
        StringBuilder sb = new StringBuilder(periodKey);
        dims.forEach((k, v) -> sb.append('|').append(k).append('=').append(v));
        return sb.toString();
    }

    @Override
    public int compareTo(CellKey o) {
        int c = periodKey.compareTo(o.periodKey);
        if (c != 0) return c;
        return asString().compareTo(o.asString());
    }

    @Override
    public String toString() { return asString(); }
}
