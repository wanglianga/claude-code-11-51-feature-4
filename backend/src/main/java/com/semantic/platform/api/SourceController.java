package com.semantic.platform.api;

import com.semantic.platform.run.ImpactService;
import com.semantic.platform.source.SourceBatch;
import com.semantic.platform.source.SourceService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sources")
public class SourceController {

    private final SourceService sourceService;
    private final ImpactService impactService;

    public SourceController(SourceService sourceService, ImpactService impactService) {
        this.sourceService = sourceService;
        this.impactService = impactService;
    }

    @GetMapping("/batches")
    public List<SourceBatch> batches() {
        return sourceService.listBatches();
    }

    @GetMapping("/coverage")
    public Map<String, Object> coverage(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return sourceService.coverageView(from, to);
    }

    /** 导入批次（迟到退款/补数），并触发影响计算生成运行候选。 */
    @PostMapping("/batches")
    public Map<String, Object> importBatch(@RequestBody Map<String, Object> body) {
        SourceBatch batch = sourceService.createBatch(
                (String) body.get("sourceType"),
                (String) body.get("storeId"),
                (String) body.getOrDefault("status", "loaded"),
                LocalDate.parse((String) body.get("periodStart")),
                LocalDate.parse((String) body.get("periodEnd")),
                body.get("recordCount") == null ? 0 : Integer.parseInt(body.get("recordCount").toString()));
        Map<String, Object> impact = impactService.onBatchImported(batch);
        return Map.of("batch", batch, "impact", impact);
    }
}
