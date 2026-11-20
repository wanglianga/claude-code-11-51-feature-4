package com.semantic.platform.dsl;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * 指标定义表达式 AST。以 JSON 存储，由服务解析、类型检查并执行。
 * 明确不支持任意 SQL；所有计算都经由受控节点。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Expr.Field.class, name = "field"),
        @JsonSubTypes.Type(value = Expr.Const.class, name = "const"),
        @JsonSubTypes.Type(value = Expr.Agg.class, name = "agg"),
        @JsonSubTypes.Type(value = Expr.Arith.class, name = "arith"),
        @JsonSubTypes.Type(value = Expr.Convert.class, name = "convert"),
        @JsonSubTypes.Type(value = Expr.Ref.class, name = "ref"),
        @JsonSubTypes.Type(value = Expr.Cohort.class, name = "cohort"),
        @JsonSubTypes.Type(value = Expr.If.class, name = "if")
})
public sealed interface Expr {

    /** 取源字段：source=orders/refunds/store_status/holidays，field=字段名 */
    record Field(String source, String field) implements Expr {}

    /** 字面常量 */
    record Const(double value) implements Expr {}

    /**
     * 聚合。measure=sum|count|count_distinct|avg|min|max
     * expr 为被聚合表达式；filter 为记录级过滤；by 为分组维度。
     * 引擎在连接前按目标粒度处理一对多，避免订单被商品行/退款行放大。
     */
    record Agg(String measure, Expr expr, List<Filter> filter, List<String> by) implements Expr {}

    /** 四则运算：add|sub|mul|div */
    record Arith(String operator, Expr left, Expr right) implements Expr {}

    /** 币种换算：把 amount 从 from 换算到 to（显式，校验器据此允许相加） */
    record Convert(Expr expr, String fromCurrency, String toCurrency, double rate) implements Expr {}

    /** 引用其他已发布指标（按 metric_key，运行期固定到其已发布版本） */
    record Ref(String metric) implements Expr {}

    /**
     * 同店增长：以 base 指标在两个比较期（当前期 vs 对比期）上做队列比较。
     * compare=prev_year|prev_period；队列由引擎按营业状态与覆盖确定。
     */
    record Cohort(Expr base, String compare, String cohortKey) implements Expr {}

    /** 条件表达式 */
    record If(Expr cond, Expr then, Expr otherwise) implements Expr {}
}
