package com.semantic.platform.api;

import com.semantic.platform.approval.Approval;
import com.semantic.platform.approval.ApprovalService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @GetMapping
    public List<Approval> list(@RequestParam(required = false) String status) {
        return approvalService.list(status);
    }

    @GetMapping("/{id}")
    public Approval get(@PathVariable Long id) {
        return approvalService.get(id);
    }

    @PostMapping
    public Approval submit(@RequestBody Map<String, Object> body,
                           @RequestAttribute(name = "user", required = false) String user) {
        return approvalService.submit(
                (String) body.get("metricKey"),
                Integer.valueOf(body.get("toVersion").toString()),
                (String) body.getOrDefault("changeType", "edit"),
                user == null ? "system" : user);
    }

    @PostMapping("/{id}/approve")
    public Approval approve(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body,
                            @RequestAttribute(name = "user", required = false) String user) {
        return approvalService.approve(id, user == null ? "system" : user,
                body == null ? null : body.get("note"));
    }

    @PostMapping("/{id}/reject")
    public Approval reject(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body,
                           @RequestAttribute(name = "user", required = false) String user) {
        return approvalService.reject(id, user == null ? "system" : user,
                body == null ? null : body.get("note"));
    }
}
