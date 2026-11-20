package com.semantic.platform.api;

import com.semantic.platform.report.Report;
import com.semantic.platform.report.ReportItem;
import com.semantic.platform.report.ReportService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
    public List<Report> list() {
        return reportService.listAll();
    }

    @PostMapping
    public Report create(@RequestBody Map<String, String> body,
                         @RequestAttribute(name = "user", required = false) String user) {
        return reportService.create(body.get("name"), user == null ? "system" : user);
    }

    @GetMapping("/{id}/items")
    public List<ReportItem> items(@PathVariable Long id) {
        return reportService.items(id);
    }

    @PostMapping("/{id}/items")
    public ReportItem addItem(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return reportService.addItem(id,
                (String) body.get("metricKey"),
                Long.valueOf(body.get("metricVersionId").toString()),
                Long.valueOf(body.get("runId").toString()),
                body.get("display") == null ? null : body.get("display").toString());
    }

    @PostMapping("/{id}/publish")
    public Report publish(@PathVariable Long id) {
        return reportService.publish(id);
    }

    @PostMapping("/{id}/correct")
    public ReportItem correct(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return reportService.correctItem(id,
                (String) body.get("metricKey"),
                Long.valueOf(body.get("newRunId").toString()));
    }
}
