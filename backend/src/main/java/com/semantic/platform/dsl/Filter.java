package com.semantic.platform.dsl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 记录级过滤条件：field operator value。
 * operator: eq | ne | in | gt | ge | lt | le
 */
public record Filter(String source, String field, String operator, Object value) {

    @JsonCreator
    public Filter(
            @JsonProperty("source") String source,
            @JsonProperty("field") String field,
            @JsonProperty("operator") String operator,
            @JsonProperty("value") Object value) {
        this.source = source;
        this.field = field;
        this.operator = operator;
        this.value = value;
    }

    public boolean isValidOperator() {
        return switch (operator) {
            case "eq", "ne", "in", "gt", "ge", "lt", "le" -> true;
            default -> false;
        };
    }
}
