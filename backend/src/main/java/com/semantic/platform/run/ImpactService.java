package com.semantic.platform.run;

import com.semantic.platform.metric.MetricEdgeRepository;
import com.semantic.platform.metric.MetricVersion;
import com.semantic.platform.metric.MetricVersionRepository;
import com.semantic.platform.report.ReportItem;
import com.semantic.platform.report.ReportItemRepository;
import com.semantic.platform.source.SourceBatch;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 影响计算：新批次（尤其迟到退款）到达后，找出受影响的已发布运行，
 * 生成新运行候选；已发布报表继续引用原运行，直到负责人发布更正。
 */
@Service
public class ImpactService {

    private final MetricVersionRepository versionRepo;
    private final MetricEdgeRepository edgeRepo;
    private final ReportItemRepository reportItemRepo;
    private final RunRepository runRepo;
    private final RunService runService;

    public ImpactService(MetricVersionRepository versionRepo, MetricEdgeRepository edgeRepo,
                         ReportItemRepository reportItemRepo, RunRepository runRepo, RunService runService) {
        this.versionRepo = versionRepo;
        this.edgeRepo = edgeRepo;
        this.reportItemRepo = reportItemRepo;
        this.runRepo = runRepo;
        this.runService = runService;
    }

    /**
     * 批次到达后的影响计算。返回受影响的报表项与新生成的运行候选。
     */
    @Transactional
    public Map<String, Object> onBatchImported(SourceBatch batch) {
        Map<String, Object> out = new LinkedHashMap<>();
        String sourceType = batch.getSourceType();
        // 1) 找出依赖该来源的指标（通过 AST 中的 source 引用）
        List<String> affectedMetrics = new ArrayList<>();
        for (MetricVersion v : versionRepo.findByStatus("published")) {
            if (v.getDefinitionJson() != null && v.getDefinitionJson().contains("\"" + sourceType + "\"")) {
                affectedMetrics.add(v.getMetricKey());
            }
        }
        // 传递闭包：引用了受影响指标的指标也受影响
        affectedMetrics = transitiveDependents(affectedMetrics);
        out.put("affectedMetrics", affectedMetrics);

        // 2) 找出这些指标的已发布报表项（运行与报表绑定）
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (String metricKey : affectedMetrics) {
            for (ReportItem item : reportItemRepo.findByMetricKey(metricKey)) {
                Run oldRun = runRepo.findById(item.getRunId()).orElse(null);
                if (oldRun == null || oldRun.getPeriodFrom() == null) continue;
                // 3) 生成新运行候选（用当前数据、同版本同周期重跑）
                Run newRun = runService.execute(metricKey, item.getMetricVersionId(),
                        oldRun.getPeriodFrom(), oldRun.getPeriodTo(),
                        oldRun.getGrain(), oldRun.getScopeJson());
                Map<String, Object> cand = new LinkedHashMap<>();
                cand.put("reportItemId", item.getId());
                cand.put("metricKey", metricKey);
                cand.put("oldRunId", oldRun.getId());
                cand.put("newRunId", newRun.getId());
                cand.put("period", oldRun.getPeriodFrom() + " ~ " + oldRun.getPeriodTo());
                cand.put("note", "迟到数据触发的新运行候选；已发布报表仍引用原运行，待负责人发布更正");
                candidates.add(cand);
            }
        }
        out.put("candidates", candidates);
        out.put("message", "已生成 " + candidates.size() + " 个运行候选；已发布报表继续引用原运行");
        return out;
    }

    /** 传递闭包：找出所有（传递）依赖受影响指标的指标。 */
    private List<String> transitiveDependents(List<String> seeds) {
        Map<String, List<String>> reverse = new LinkedHashMap<>();
        edgeRepo.findAll().forEach(e -> reverse.computeIfAbsent(e.getToMetric(), k -> new ArrayList<>()).add(e.getFromMetric()));
        java.util.Set<String> seen = new java.util.HashSet<>(seeds);
        List<String> queue = new ArrayList<>(seeds);
        while (!queue.isEmpty()) {
            String cur = queue.remove(0);
            for (String d : reverse.getOrDefault(cur, List.of())) {
                if (seen.add(d)) queue.add(d);
            }
        }
        return new ArrayList<>(seen);
    }
}
