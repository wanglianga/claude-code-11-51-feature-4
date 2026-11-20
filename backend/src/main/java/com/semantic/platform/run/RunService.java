package com.semantic.platform.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semantic.platform.dsl.Expr;
import com.semantic.platform.engine.CellKey;
import com.semantic.platform.engine.CellValue;
import com.semantic.platform.engine.EvaluationContext;
import com.semantic.platform.engine.Evaluator;
import com.semantic.platform.engine.Series;
import com.semantic.platform.metric.MetricService;
import com.semantic.platform.metric.MetricVersion;
import com.semantic.platform.metric.MetricVersionRepository;
import com.semantic.platform.source.OrderFactRepository;
import com.semantic.platform.source.OrderLineRepository;
import com.semantic.platform.source.RefundFactRepository;
import com.semantic.platform.source.SourceBatch;
import com.semantic.platform.source.SourceBatchRepository;
import com.semantic.platform.source.StoreStatusRepository;
import com.semantic.platform.source.HolidayRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行服务：执行指标定义，固定引用版本，标记数据截止点与覆盖范围，持久化单元格与血缘。
 */
@Service
public class RunService {

    private final MetricService metricService;
    private final MetricVersionRepository versionRepo;
    private final RunRepository runRepo;
    private final RunCellRepository cellRepo;
    private final RunCellLineageRepository lineageRepo;
    private final OrderFactRepository orderRepo;
    private final OrderLineRepository orderLineRepo;
    private final RefundFactRepository refundRepo;
    private final StoreStatusRepository storeStatusRepo;
    private final HolidayRepository holidayRepo;
    private final SourceBatchRepository batchRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    public RunService(MetricService metricService, MetricVersionRepository versionRepo,
                      RunRepository runRepo, RunCellRepository cellRepo, RunCellLineageRepository lineageRepo,
                      OrderFactRepository orderRepo, OrderLineRepository orderLineRepo, RefundFactRepository refundRepo,
                      StoreStatusRepository storeStatusRepo, HolidayRepository holidayRepo,
                      SourceBatchRepository batchRepo) {
        this.metricService = metricService;
        this.versionRepo = versionRepo;
        this.runRepo = runRepo;
        this.cellRepo = cellRepo;
        this.lineageRepo = lineageRepo;
        this.orderRepo = orderRepo;
        this.orderLineRepo = orderLineRepo;
        this.refundRepo = refundRepo;
        this.storeStatusRepo = storeStatusRepo;
        this.holidayRepo = holidayRepo;
        this.batchRepo = batchRepo;
    }

    /** 执行一次运行。 */
    @Transactional
    public Run execute(String metricKey, Long metricVersionId, LocalDate from, LocalDate to, String grain, String scopeJson) {
        MetricVersion version = versionRepo.findById(metricVersionId)
                .orElseThrow(() -> new IllegalArgumentException("指标版本不存在: " + metricVersionId));
        Expr expr = metricService.parseExpr(version.getDefinitionJson());

        Run run = new Run();
        run.setMetricKey(metricKey);
        run.setMetricVersionId(metricVersionId);
        run.setGrain(grain);
        run.setPeriodFrom(from);
        run.setPeriodTo(to);
        run.setScopeJson(scopeJson);
        run.setResultKind(expr instanceof Expr.Cohort ? "cohort_growth" : "metric");

        // 加载源数据（放宽一天以覆盖跨营业日）
        EvaluationContext ctx = new EvaluationContext(from, to, grain);
        ctx.orders = orderRepo.findByBusinessDateBetween(from.minusDays(1), to.plusDays(1));
        ctx.orderLines = orderLineRepo.findByBusinessDateBetween(from.minusDays(1), to.plusDays(1));
        ctx.refunds = refundRepo.findByBusinessDateBetween(from.minusDays(1), to.plusDays(1));
        ctx.storeStatus = storeStatusRepo.findByBusinessDateBetween(from.minusDays(1), to.plusDays(1));
        ctx.holidays = holidayRepo.findByHolidayDateBetween(from.minusDays(1), to.plusDays(1));
        ctx.refResolver = k -> {
            MetricVersion pv = metricService.publishedVersion(k);
            return metricService.parseExpr(pv.getDefinitionJson());
        };
        ctx.refTypes = metricService.publishedTypes();

        // 覆盖范围：从批次表构建
        buildCoverage(ctx, from, to);

        // 求值
        Evaluator evaluator = new Evaluator(ctx);
        Series series;
        try {
            series = evaluator.eval(expr);
        } catch (Exception e) {
            run.setStatus("failed");
            String msg = e.getMessage();
            run.setError((msg == null ? e.getClass().getSimpleName() : msg)
                    + " [" + (e.getStackTrace().length > 0 ? e.getStackTrace()[0].toString() : "") + "]");
            run.setFinishedAt(java.time.OffsetDateTime.now());
            return runRepo.save(run);
        }

        // 数据截止点：所有必需来源的最晚完整覆盖日
        run.setDataCutoffJson(computeCutoff(ctx, from, to));
        run.setStatus("success");
        run.setFinishedAt(java.time.OffsetDateTime.now());
        Run saved = runRepo.save(run);

        // 持久化单元格与血缘
        for (Map.Entry<CellKey, CellValue> en : series.cells().entrySet()) {
            CellKey key = en.getKey();
            CellValue cv = en.getValue();
            RunCell cell = new RunCell();
            cell.setRunId(saved.getId());
            cell.setPeriodKey(key.periodKey());
            cell.setDimsJson(toJson(key.dims()));
            cell.setValueNum(cv.getNum());
            cell.setValueDen(cv.getDen());
            cell.setCellStatus(cv.getStatus().name().toLowerCase());
            cell.setMessage(cv.getMessage() != null ? cv.getMessage() : cv.getTrace());
            RunCell savedCell = cellRepo.save(cell);

            String cellKey = key.asString();
            for (CellValue.Lineage lin : cv.getLineage()) {
                RunCellLineage l = new RunCellLineage();
                l.setRunId(saved.getId());
                l.setCellKey(cellKey);
                l.setRecordType(lin.recordType());
                l.setRecordId(lin.recordId());
                l.setContribution(lin.contribution());
                l.setIncluded(lin.included());
                l.setReason(lin.reason());
                lineageRepo.save(l);
            }
        }
        return saved;
    }

    /** 从批次表构建覆盖范围：(source, store, date) 是否已加载完成。 */
    private void buildCoverage(EvaluationContext ctx, LocalDate from, LocalDate to) {
        List<SourceBatch> batches = batchRepo.findAll();
        for (SourceBatch b : batches) {
            if (!"loaded".equals(b.getStatus())) continue; // backfilling 不算覆盖
            String source = mapSourceType(b.getSourceType());
            String storeScope = b.getStoreId() == null ? "*" : b.getStoreId();
            LocalDate start = b.getPeriodStart() == null ? from : b.getPeriodStart();
            LocalDate end = b.getPeriodEnd() == null ? to : b.getPeriodEnd();
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                ctx.addCoverage(source, storeScope, d);
            }
        }
    }

    private String mapSourceType(String batchType) {
        return switch (batchType) {
            case "orders" -> "orders";
            case "refunds" -> "refunds";
            case "store_hours" -> "store_status";
            case "holidays" -> "holidays";
            default -> batchType;
        };
    }

    /** 计算数据截止点：每个来源的最晚完整覆盖日，取最小值。 */
    private String computeCutoff(EvaluationContext ctx, LocalDate from, LocalDate to) {
        Map<String, Object> info = new LinkedHashMap<>();
        Map<String, String> cutoffBySource = new LinkedHashMap<>();
        for (String source : List.of("orders", "refunds", "store_status")) {
            LocalDate cutoff = null;
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                final LocalDate day = d;
                boolean covered = ctx.coverage.getOrDefault(source, java.util.Set.of()).stream()
                        .anyMatch(k -> k.endsWith("@" + day));
                if (covered) cutoff = d; else break;
            }
            cutoffBySource.put(source, cutoff == null ? null : cutoff.toString());
        }
        info.put("cutoffBySource", cutoffBySource);
        info.put("note", "截止点之后的数据视为未覆盖，不计入零值");
        try {
            return mapper.writeValueAsString(info);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String toJson(Object o) {
        try { return mapper.writeValueAsString(o); } catch (Exception e) { return "{}"; }
    }

    public List<Run> listRuns(String metricKey) {
        return metricKey == null ? runRepo.findAll() : runRepo.findByMetricKeyOrderByStartedAtDesc(metricKey);
    }

    public List<RunCell> cells(Long runId) { return cellRepo.findByRunId(runId); }

    public List<RunCellLineage> lineage(Long runId, String cellKey) {
        return lineageRepo.findByRunIdAndCellKey(runId, cellKey);
    }

    public Run getRun(Long runId) { return runRepo.findById(runId).orElseThrow(); }
}
