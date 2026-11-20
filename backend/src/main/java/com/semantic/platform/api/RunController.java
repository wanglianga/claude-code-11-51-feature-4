package com.semantic.platform.api;

import com.semantic.platform.run.Run;
import com.semantic.platform.run.RunCell;
import com.semantic.platform.run.RunCellLineage;
import com.semantic.platform.run.RunService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunService runService;

    public RunController(RunService runService) {
        this.runService = runService;
    }

    @PostMapping
    public Run execute(@RequestBody Map<String, Object> body) {
        String metricKey = (String) body.get("metricKey");
        Long versionId = Long.valueOf(body.get("metricVersionId").toString());
        LocalDate from = LocalDate.parse((String) body.get("from"));
        LocalDate to = LocalDate.parse((String) body.get("to"));
        String grain = (String) body.getOrDefault("grain", "day");
        String scope = body.get("scope") == null ? null : body.get("scope").toString();
        return runService.execute(metricKey, versionId, from, to, grain, scope);
    }

    @GetMapping
    public List<Run> list(@RequestParam(required = false) String metricKey) {
        return runService.listRuns(metricKey);
    }

    @GetMapping("/{id}")
    public Run get(@PathVariable Long id) {
        return runService.getRun(id);
    }

    @GetMapping("/{id}/cells")
    public List<RunCell> cells(@PathVariable Long id) {
        return runService.cells(id);
    }

    @GetMapping("/{id}/lineage")
    public List<RunCellLineage> lineage(@PathVariable Long id, @RequestParam String cellKey) {
        return runService.lineage(id, cellKey);
    }
}
