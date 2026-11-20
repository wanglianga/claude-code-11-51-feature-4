package com.semantic.platform.dsl;

import java.util.Map;
import java.util.Set;

/**
 * 源字段注册表：声明每个来源有哪些字段、类型、币种、时间粒度。
 * 校验器据此判断字段是否存在、类型是否匹配。
 * 与数据库 source_field 表对应，但此处为静态契约（代码即真源）。
 */
public final class SourceRegistry {

    public record FieldDef(String name, MetricType.ValueKind kind, String currency, boolean nullable) {}

    /** source -> field -> def */
    public static final Map<String, Map<String, FieldDef>> SOURCES = Map.of(
            "orders", Map.of(
                    "order_id", new FieldDef("order_id", MetricType.ValueKind.QUANTITY, null, false),
                    "store_id", new FieldDef("store_id", MetricType.ValueKind.QUANTITY, null, false),
                    "amount", new FieldDef("amount", MetricType.ValueKind.AMOUNT, "CNY", false),
                    "quantity", new FieldDef("quantity", MetricType.ValueKind.QUANTITY, null, false),
                    "status", new FieldDef("status", MetricType.ValueKind.BOOLEAN, null, false),
                    "channel", new FieldDef("channel", MetricType.ValueKind.QUANTITY, null, true),
                    "currency", new FieldDef("currency", MetricType.ValueKind.QUANTITY, null, false),
                    "business_date", new FieldDef("business_date", MetricType.ValueKind.TIMESTAMP, null, false),
                    "ordered_at", new FieldDef("ordered_at", MetricType.ValueKind.TIMESTAMP, null, false),
                    "paid_at", new FieldDef("paid_at", MetricType.ValueKind.TIMESTAMP, null, true)
            ),
            "order_lines", Map.of(
                    "line_id", new FieldDef("line_id", MetricType.ValueKind.QUANTITY, null, false),
                    "order_id", new FieldDef("order_id", MetricType.ValueKind.QUANTITY, null, false),
                    "store_id", new FieldDef("store_id", MetricType.ValueKind.QUANTITY, null, false),
                    "sku", new FieldDef("sku", MetricType.ValueKind.QUANTITY, null, false),
                    "quantity", new FieldDef("quantity", MetricType.ValueKind.QUANTITY, null, false),
                    "unit_price", new FieldDef("unit_price", MetricType.ValueKind.AMOUNT, "CNY", false),
                    "amount", new FieldDef("amount", MetricType.ValueKind.AMOUNT, "CNY", false),
                    "business_date", new FieldDef("business_date", MetricType.ValueKind.TIMESTAMP, null, false)
            ),
            "refunds", Map.of(
                    "refund_id", new FieldDef("refund_id", MetricType.ValueKind.QUANTITY, null, false),
                    "order_id", new FieldDef("order_id", MetricType.ValueKind.QUANTITY, null, false),
                    "store_id", new FieldDef("store_id", MetricType.ValueKind.QUANTITY, null, false),
                    "amount", new FieldDef("amount", MetricType.ValueKind.AMOUNT, "CNY", false),
                    "business_date", new FieldDef("business_date", MetricType.ValueKind.TIMESTAMP, null, false),
                    "refunded_at", new FieldDef("refunded_at", MetricType.ValueKind.TIMESTAMP, null, false)
            ),
            "store_status", Map.of(
                    "store_id", new FieldDef("store_id", MetricType.ValueKind.QUANTITY, null, false),
                    "business_date", new FieldDef("business_date", MetricType.ValueKind.TIMESTAMP, null, false),
                    "status", new FieldDef("status", MetricType.ValueKind.BOOLEAN, null, false),
                    "open_hours", new FieldDef("open_hours", MetricType.ValueKind.QUANTITY, null, true)
            ),
            "holidays", Map.of(
                    "holiday_date", new FieldDef("holiday_date", MetricType.ValueKind.TIMESTAMP, null, false),
                    "name", new FieldDef("name", MetricType.ValueKind.QUANTITY, null, false),
                    "type", new FieldDef("type", MetricType.ValueKind.QUANTITY, null, false)
            )
    );

    /** 每个来源的时间字段（用于营业日归属与粒度对齐） */
    public static final Map<String, String> TIME_FIELD = Map.of(
            "orders", "business_date",
            "order_lines", "business_date",
            "refunds", "business_date",
            "store_status", "business_date",
            "holidays", "holiday_date"
    );

    /** 每个来源的门店维度字段 */
    public static final Map<String, String> STORE_FIELD = Map.of(
            "orders", "store_id",
            "order_lines", "store_id",
            "refunds", "store_id",
            "store_status", "store_id"
    );

    public static boolean hasSource(String source) {
        return SOURCES.containsKey(source);
    }

    public static FieldDef field(String source, String field) {
        Map<String, FieldDef> fields = SOURCES.get(source);
        return fields == null ? null : fields.get(field);
    }

    public static boolean hasField(String source, String field) {
        return field(source, field) != null;
    }

    public static Set<String> sourceNames() {
        return SOURCES.keySet();
    }
}
