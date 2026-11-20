package com.semantic.platform.dsl;

import java.util.Set;

/**
 * 指标表达式的类型：值类别 + 币种 + 时间粒度 + 维度。
 * 校验器用它捕获：币种不一致相加、日/月粒度混乘、比率被算术平均等错误。
 */
public record MetricType(
        ValueKind kind,
        String currency,      // 仅 AMOUNT 有意义；null 表示无币种
        TimeGrain timeGrain,  // 值所处的时间粒度
        Set<String> dims      // 值已按哪些维度分组（如 store_id）
) {
    public enum ValueKind { AMOUNT, QUANTITY, RATIO, TIMESTAMP, BOOLEAN }

    public enum TimeGrain { DAY, MONTH, QUARTER, NONE }

    public static MetricType amount(String currency, TimeGrain grain, Set<String> dims) {
        return new MetricType(ValueKind.AMOUNT, currency, grain, dims);
    }

    public static MetricType quantity(TimeGrain grain, Set<String> dims) {
        return new MetricType(ValueKind.QUANTITY, null, grain, dims);
    }

    public static MetricType ratio(TimeGrain grain, Set<String> dims) {
        return new MetricType(ValueKind.RATIO, null, grain, dims);
    }

    public static MetricType bool() {
        return new MetricType(ValueKind.BOOLEAN, null, TimeGrain.NONE, Set.of());
    }

    public boolean isAmount() { return kind == ValueKind.AMOUNT; }
    public boolean isRatio() { return kind == ValueKind.RATIO; }
    public boolean isQuantity() { return kind == ValueKind.QUANTITY; }

    /** 同类别且（金额时）同币种、同时间粒度、同维度集合，才允许加减。 */
    public boolean addableWith(MetricType other) {
        if (this.kind != other.kind) return false;
        if (this.kind == ValueKind.AMOUNT) {
            if (this.currency == null || other.currency == null) return false;
            if (!this.currency.equals(other.currency)) return false;
        }
        return this.timeGrain == other.timeGrain && this.dims.equals(other.dims);
    }

    public String describe() {
        return kind + (currency != null ? "(" + currency + ")" : "")
                + "@" + timeGrain + (dims.isEmpty() ? "" : dims.toString());
    }
}
