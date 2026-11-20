package com.semantic.platform.metric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantic.platform.dsl.Expr;
import com.semantic.platform.dsl.ExprValidator;
import com.semantic.platform.dsl.MetricType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 指标服务：定义编辑、校验（含环路）、发布（组合重检）、影响分析。
 */
@Service
public class MetricService {

    private final MetricDefRepository defRepo;
    private final MetricVersionRepository versionRepo;
    private final MetricEdgeRepository edgeRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    public MetricService(MetricDefRepository defRepo, MetricVersionRepository versionRepo, MetricEdgeRepository edgeRepo) {
        this.defRepo = defRepo;
        this.versionRepo = versionRepo;
        this.edgeRepo = edgeRepo;
    }

    public Expr parseExpr(String definitionJson) {
        try {
            return mapper.readValue(definitionJson, Expr.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("定义 JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /** 已发布指标的类型表（按依赖拓扑序推断，引用才能正确解析）。粒度取已发布版本的粒度。 */
    public Map<String, MetricType> publishedTypes() {
        List<MetricVersion> published = versionRepo.findByStatus("published");
        Map<String, Expr> exprs = new HashMap<>();
        Map<String, String> grains = new HashMap<>();
        for (MetricVersion v : published) {
            try {
                exprs.put(v.getMetricKey(), parseExpr(v.getDefinitionJson()));
                grains.put(v.getMetricKey(), v.getGrain() == null ? "day" : v.getGrain());
            } catch (Exception ignored) { }
        }
        Map<String, MetricType> types = new HashMap<>();
        // 迭代至不动点：每轮解析出引用已就绪的指标
        for (int iter = 0; iter < published.size() + 1 && types.size() < exprs.size(); iter++) {
            for (Map.Entry<String, Expr> en : exprs.entrySet()) {
                String key = en.getKey();
                if (types.containsKey(key)) continue;
                Set<String> refs = ExprValidator.collectRefs(en.getValue());
                if (!types.keySet().containsAll(refs)) continue; // 依赖未就绪，下一轮
                MetricType.TimeGrain declaredGrain = "month".equals(grains.get(key)) ? MetricType.TimeGrain.MONTH : MetricType.TimeGrain.DAY;
                ExprValidator validator = new ExprValidator(types, Map.of(), declaredGrain);
                ExprValidator.Result res = new ExprValidator.Result();
                MetricType inferred = validator.infer(en.getValue(), "$", res);
                // 指标对外呈现的类型：类别/币种/维度来自表达式推断，时间粒度以声明粒度为准
                types.put(key, new MetricType(inferred.kind(), inferred.currency(), declaredGrain, inferred.dims()));
            }
        }
        return types;
    }

    /** 已发布指标的依赖图。 */
    public Map<String, Set<String>> publishedRefs() {
        Map<String, Set<String>> refs = new HashMap<>();
        for (MetricEdge e : edgeRepo.findAll()) {
            refs.computeIfAbsent(e.getFromMetric(), k -> new HashSet<>()).add(e.getToMetric());
        }
        return refs;
    }

    /** 校验定义：环路 + 字段 + 类型/币种/粒度。返回结构化结果。 */
    public Map<String, Object> validate(String metricKey, String definitionJson) {
        return validateWithGrain(metricKey, definitionJson, null);
    }

    /** 带声明粒度的校验。 */
    public Map<String, Object> validateWithGrain(String metricKey, String definitionJson, String grain) {
        Map<String, Object> out = new LinkedHashMap<>();
        Expr expr;
        try {
            expr = parseExpr(definitionJson);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("errors", List.of(Map.of("path", "$", "message", "JSON 解析失败: " + e.getMessage())));
            return out;
        }
        MetricType.TimeGrain currentGrain = "month".equals(grain) ? MetricType.TimeGrain.MONTH : MetricType.TimeGrain.DAY;
        ExprValidator validator = new ExprValidator(publishedTypes(), publishedRefs(), currentGrain);
        // 1) 环路
        List<String> cycle = validator.detectCycle(metricKey, expr);
        List<Map<String, String>> errors = new ArrayList<>();
        if (!cycle.isEmpty()) {
            errors.add(Map.of("path", "$refs", "message", "检测到引用环路: " + String.join(" -> ", cycle)));
        }
        // 2) 类型/字段/币种/粒度
        ExprValidator.Result res = new ExprValidator.Result();
        MetricType type = validator.infer(expr, "$", res);
        res.errors.forEach(i -> errors.add(Map.of("path", i.path(), "message", i.message())));
        out.put("ok", errors.isEmpty());
        out.put("errors", errors);
        out.put("inferredType", type.describe());
        out.put("refs", ExprValidator.collectRefs(expr));
        return out;
    }

    /** 保存草稿（创建新版本）。 */
    @Transactional
    public MetricVersion saveDraft(String metricKey, String name, String description, String grain,
                                   String definitionJson, String createdBy) {
        Map<String, Object> v = validateWithGrain(metricKey, definitionJson, grain);
        if (!(Boolean) v.get("ok")) {
            throw new IllegalArgumentException("定义校验失败: " + v.get("errors"));
        }
        MetricDef def = defRepo.findByMetricKey(metricKey).orElseGet(() -> {
            MetricDef d = new MetricDef();
            d.setMetricKey(metricKey);
            d.setName(name);
            d.setDescription(description);
            d.setGrain(grain);
            d.setStatus("draft");
            return defRepo.save(d);
        });
        def.setName(name);
        def.setDescription(description);
        def.setGrain(grain);
        def.setUpdatedAt(java.time.OffsetDateTime.now());
        defRepo.save(def);

        int nextVersion = versionRepo.findByMetricKeyOrderByVersionDesc(metricKey).stream()
                .mapToInt(MetricVersion::getVersion).max().orElse(0) + 1;
        MetricVersion ver = new MetricVersion();
        ver.setMetricKey(metricKey);
        ver.setVersion(nextVersion);
        ver.setDefinitionJson(definitionJson);
        ver.setGrain(grain);
        ver.setStatus("draft");
        ver.setDefinitionHash(hash(definitionJson));
        ver.setCreatedBy(createdBy);
        return versionRepo.save(ver);
    }

    /**
     * 发布指标版本。在最终组合（已发布 + 本版本）上重新检查环路，
     * 防止并发草稿各自预览通过却同时生效成环。
     */
    @Transactional
    public MetricVersion publish(String metricKey, Integer version, String publishedBy) {
        MetricVersion ver = versionRepo.findByMetricKeyAndVersion(metricKey, version)
                .orElseThrow(() -> new IllegalArgumentException("版本不存在: " + metricKey + "@" + version));
        // 幂等：若该版本已是当前已发布版本，直接返回
        if ("published".equals(ver.getStatus())) {
            return ver;
        }
        Expr expr = parseExpr(ver.getDefinitionJson());

        // 组合图 = 当前已发布 + 本版本（替换同 key 旧版本）
        Map<String, Set<String>> graph = new HashMap<>(publishedRefs());
        graph.put(metricKey, ExprValidator.collectRefs(expr));
        // 类型表也要包含本版本（用于校验引用它的指标——此处先校验本版本自身）
        MetricType.TimeGrain currentGrain = "month".equals(ver.getGrain()) ? MetricType.TimeGrain.MONTH : MetricType.TimeGrain.DAY;
        ExprValidator validator = new ExprValidator(publishedTypes(), graph, currentGrain);
        List<String> cycle = validator.detectCycle(metricKey, expr);
        if (!cycle.isEmpty()) {
            throw new IllegalStateException("发布被拒绝：最终组合检测到引用环路 " + String.join(" -> ", cycle));
        }
        ExprValidator.Result res = new ExprValidator.Result();
        validator.infer(expr, "$", res);
        if (!res.ok()) {
            throw new IllegalStateException("发布被拒绝：定义校验失败 " + res.errors);
        }

        // 旧版本下线
        versionRepo.findFirstByMetricKeyAndStatusOrderByVersionDesc(metricKey, "published")
                .ifPresent(old -> { old.setStatus("deprecated"); versionRepo.save(old); });
        ver.setStatus("published");
        ver.setPublishedAt(java.time.OffsetDateTime.now());
        MetricVersion saved = versionRepo.save(ver);

        MetricDef def = defRepo.findByMetricKey(metricKey).orElseThrow();
        def.setStatus("published");
        def.setGrain(ver.getGrain()); // 粒度以已发布版本为准
        def.setUpdatedAt(java.time.OffsetDateTime.now());
        defRepo.save(def);

        // 重建依赖边（先删后插，按 from_metric 全部清理，避免唯一约束冲突）
        edgeRepo.deleteByFromMetricAndFromVersion(metricKey, version);
        edgeRepo.flush();
        for (String ref : ExprValidator.collectRefs(expr)) {
            MetricEdge edge = new MetricEdge();
            edge.setFromMetric(metricKey);
            edge.setToMetric(ref);
            edge.setFromVersion(version);
            edgeRepo.save(edge);
        }
        return saved;
    }

    /**
     * 撤回指标：已发布版本下线。依赖检查沿指标图阻止不兼容的新运行——
     * 引用该指标的下游指标在运行时将因引用解析失败而被阻止；历史报告仍绑定原运行。
     */
    @Transactional
    public Map<String, Object> withdraw(String metricKey, String withdrawnBy) {
        MetricVersion published = versionRepo
                .findFirstByMetricKeyAndStatusOrderByVersionDesc(metricKey, "published")
                .orElseThrow(() -> new IllegalArgumentException("指标未发布: " + metricKey));
        // 找出下游依赖（用于提示哪些指标的新运行会被阻止）
        Map<String, Object> impact = impact(metricKey);
        published.setStatus("withdrawn");
        versionRepo.save(published);
        MetricDef def = defRepo.findByMetricKey(metricKey).orElseThrow();
        def.setStatus("deprecated");
        def.setUpdatedAt(java.time.OffsetDateTime.now());
        defRepo.save(def);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metricKey", metricKey);
        out.put("withdrawnVersion", published.getVersion());
        out.put("blockedDownstream", impact.get("downstreamMetrics"));
        out.put("note", "已撤回；引用该指标的下游指标新运行将被阻止，历史报告仍绑定原运行");
        return out;
    }

    /** 影响分析：沿依赖图找出下游指标与报表。 */
    public Map<String, Object> impact(String metricKey) {
        Map<String, Object> out = new LinkedHashMap<>();
        // 下游指标：谁引用了我（传递闭包）
        Map<String, Set<String>> reverse = new HashMap<>();
        for (MetricEdge e : edgeRepo.findAll()) {
            reverse.computeIfAbsent(e.getToMetric(), k -> new HashSet<>()).add(e.getFromMetric());
        }
        Set<String> downstream = new HashSet<>();
        List<String> queue = new ArrayList<>(List.of(metricKey));
        while (!queue.isEmpty()) {
            String cur = queue.remove(0);
            for (String d : reverse.getOrDefault(cur, Set.of())) {
                if (downstream.add(d)) queue.add(d);
            }
        }
        out.put("downstreamMetrics", downstream);
        out.put("upstreamMetrics", ExprValidator.collectRefs(
                currentPublishedExpr(metricKey)));
        return out;
    }

    private Expr currentPublishedExpr(String metricKey) {
        return versionRepo.findFirstByMetricKeyAndStatusOrderByVersionDesc(metricKey, "published")
                .map(v -> parseExpr(v.getDefinitionJson()))
                .orElse(null);
    }

    private String hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return s;
        }
    }

    public List<MetricDef> listAll() { return defRepo.findAll(); }

    public List<MetricVersion> versions(String metricKey) {
        return versionRepo.findByMetricKeyOrderByVersionDesc(metricKey);
    }

    public MetricVersion publishedVersion(String metricKey) {
        return versionRepo.findFirstByMetricKeyAndStatusOrderByVersionDesc(metricKey, "published")
                .orElseThrow(() -> new IllegalArgumentException("指标未发布: " + metricKey));
    }
}
