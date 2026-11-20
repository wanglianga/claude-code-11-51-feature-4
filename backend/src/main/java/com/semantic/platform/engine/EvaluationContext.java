package com.semantic.platform.engine;

import com.semantic.platform.source.Holiday;
import com.semantic.platform.source.OrderFact;
import com.semantic.platform.source.RefundFact;
import com.semantic.platform.source.StoreStatus;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 求值上下文：源数据 + 覆盖范围 + 运行参数 + 引用解析。 */
public class EvaluationContext {

    public final LocalDate from;
    public final LocalDate to;
    public final String grain; // day | month

    public List<OrderFact> orders = new ArrayList<>();
    public List<com.semantic.platform.source.OrderLine> orderLines = new ArrayList<>();
    public List<RefundFact> refunds = new ArrayList<>();
    public List<StoreStatus> storeStatus = new ArrayList<>();
    public List<Holiday> holidays = new ArrayList<>();

    /** source -> 已覆盖的 (storeId, date) 集合。null 的 store 用 "*" 代表全部门店。 */
    public final Map<String, Set<String>> coverage = new HashMap<>();

    /** 引用指标求值缓存：metricKey -> Series */
    public final Map<String, Series> refCache = new HashMap<>();

    /** 引用指标的定义解析器：metricKey -> Expr（已发布版本） */
    public java.util.function.Function<String, com.semantic.platform.dsl.Expr> refResolver;

    /** 引用指标的类型表 */
    public Map<String, com.semantic.platform.dsl.MetricType> refTypes = new HashMap<>();

    public EvaluationContext(LocalDate from, LocalDate to, String grain) {
        this.from = from;
        this.to = to;
        this.grain = grain;
    }

    public void addCoverage(String source, String storeId, LocalDate date) {
        coverage.computeIfAbsent(source, k -> new HashSet<>()).add(storeId + "@" + date);
    }

    public boolean isCovered(String source, String storeId, LocalDate date) {
        Set<String> set = coverage.get(source);
        if (set == null) return false;
        return set.contains(storeId + "@" + date) || set.contains("*@" + date);
    }

    /** 门店某日是否闭店（闭店视为已覆盖的合法零值）。 */
    public boolean isStoreClosed(String storeId, LocalDate date) {
        for (StoreStatus s : storeStatus) {
            if (s.getStoreId().equals(storeId) && s.getBusinessDate().equals(date)) {
                return "closed".equals(s.getStatus()) || "temp_closed".equals(s.getStatus());
            }
        }
        return false;
    }

    /** 门店某日是否营业。 */
    public boolean isStoreOpen(String storeId, LocalDate date) {
        for (StoreStatus s : storeStatus) {
            if (s.getStoreId().equals(storeId) && s.getBusinessDate().equals(date)) {
                return "open".equals(s.getStatus());
            }
        }
        return false;
    }

    /** 取范围内出现的所有门店。 */
    public Set<String> allStores() {
        Set<String> stores = new HashSet<>();
        for (StoreStatus s : storeStatus) stores.add(s.getStoreId());
        for (OrderFact o : orders) stores.add(o.getStoreId());
        return stores;
    }
}
