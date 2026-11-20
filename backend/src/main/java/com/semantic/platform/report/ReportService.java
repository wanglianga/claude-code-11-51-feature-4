package com.semantic.platform.report;

import com.semantic.platform.metric.MetricVersion;
import com.semantic.platform.metric.MetricVersionRepository;
import com.semantic.platform.run.Run;
import com.semantic.platform.run.RunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 报表服务：报表组装、粒度一致性校验、发布锁定、更正。
 */
@Service
public class ReportService {

    private final ReportRepository reportRepo;
    private final ReportItemRepository itemRepo;
    private final MetricVersionRepository versionRepo;
    private final RunRepository runRepo;

    public ReportService(ReportRepository reportRepo, ReportItemRepository itemRepo,
                         MetricVersionRepository versionRepo, RunRepository runRepo) {
        this.reportRepo = reportRepo;
        this.itemRepo = itemRepo;
        this.versionRepo = versionRepo;
        this.runRepo = runRepo;
    }

    @Transactional
    public Report create(String name, String createdBy) {
        Report r = new Report();
        r.setName(name);
        r.setCreatedBy(createdBy);
        r.setStatus("draft");
        return reportRepo.save(r);
    }

    /**
     * 添加报表项。校验：同一报表内所有指标的展示粒度必须一致，
     * 禁止门店颗粒度与城市颗粒度混排。
     */
    @Transactional
    public ReportItem addItem(Long reportId, String metricKey, Long metricVersionId, Long runId, String displayJson) {
        Report report = reportRepo.findById(reportId).orElseThrow();
        if (!"draft".equals(report.getStatus())) {
            throw new IllegalStateException("报表已发布，不能修改；如需更正请走更正流程");
        }
        MetricVersion version = versionRepo.findById(metricVersionId).orElseThrow();
        Run run = runRepo.findById(runId).orElseThrow();
        // 粒度一致性：与已有项比较 grain
        List<ReportItem> existing = itemRepo.findByReportId(reportId);
        for (ReportItem item : existing) {
            Run otherRun = runRepo.findById(item.getRunId()).orElseThrow();
            if (!otherRun.getGrain().equals(run.getGrain())) {
                throw new IllegalStateException(
                        "粒度不一致：报表内已有 " + otherRun.getGrain() + " 粒度指标，不能加入 " + run.getGrain()
                                + " 粒度指标（禁止门店颗粒度与城市/月颗粒度混排）");
            }
        }
        ReportItem item = new ReportItem();
        item.setReportId(reportId);
        item.setMetricKey(metricKey);
        item.setMetricVersionId(metricVersionId);
        item.setRunId(runId);
        item.setDisplayJson(displayJson);
        return itemRepo.save(item);
    }

    /** 发布报表：锁定所有引用的运行版本。 */
    @Transactional
    public Report publish(Long reportId) {
        Report report = reportRepo.findById(reportId).orElseThrow();
        List<ReportItem> items = itemRepo.findByReportId(reportId);
        if (items.isEmpty()) throw new IllegalStateException("空报表不能发布");
        report.setStatus("published");
        report.setPublishedAt(java.time.OffsetDateTime.now());
        return reportRepo.save(report);
    }

    /**
     * 更正报表项：用新的运行替换原运行（迟到退款/补数后）。
     * 已发布报表继续引用原运行，直到经营负责人发布更正。
     */
    @Transactional
    public ReportItem correctItem(Long reportId, String metricKey, Long newRunId) {
        Report report = reportRepo.findById(reportId).orElseThrow();
        if (!"published".equals(report.getStatus())) {
            throw new IllegalStateException("仅已发布报表可更正");
        }
        ReportItem item = itemRepo.findByReportId(reportId).stream()
                .filter(i -> i.getMetricKey().equals(metricKey)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("报表不含指标 " + metricKey));
        Run newRun = runRepo.findById(newRunId).orElseThrow();
        // 粒度一致性
        Run oldRun = runRepo.findById(item.getRunId()).orElseThrow();
        if (!oldRun.getGrain().equals(newRun.getGrain())) {
            throw new IllegalStateException("更正运行的粒度与原运行不一致");
        }
        item.setRunId(newRunId);
        item.setMetricVersionId(newRun.getMetricVersionId());
        return itemRepo.save(item);
    }

    public List<Report> listAll() { return reportRepo.findAll(); }
    public List<ReportItem> items(Long reportId) { return itemRepo.findByReportId(reportId); }
    public Report get(Long id) { return reportRepo.findById(id).orElseThrow(); }
}
