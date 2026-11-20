package com.semantic.platform.api;

import com.semantic.platform.distribution.DedupDecision;
import com.semantic.platform.distribution.DistCompile;
import com.semantic.platform.distribution.DistRevisionService;
import com.semantic.platform.distribution.DistService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 客单金额分布查询 API。
 * 编译（全量/局部）、分布查询（精确/近似/自动）、观察值与摘要检查、修订、发布快照。
 */
@RestController
@RequestMapping("/api/dist")
public class DistController {

    private final DistService distService;
    private final DistRevisionService revisionService;

    public DistController(DistService distService, DistRevisionService revisionService) {
        this.distService = distService;
        this.revisionService = revisionService;
    }

    /** 编译：body 可含 orderKeys（局部重建），缺省全量。 */
    @PostMapping("/compile")
    public Map<String, Object> compile(@RequestBody(required = false) Map<String, Object> body) {
        List<String> keys = null;
        if (body != null && body.get("orderKeys") instanceof List<?> list) {
            keys = list.stream().map(String::valueOf).toList();
        }
        return distService.compile("manual", body == null ? null : String.valueOf(body.getOrDefault("ref", "api")), keys);
    }

    /** 分布查询。 */
    @PostMapping("/query")
    public Map<String, Object> query(@RequestBody Map<String, Object> params) {
        return distService.query(params);
    }

    /** 当前观察值（待核清单 / 复算验证）。 */
    @GetMapping("/orders")
    public List<Map<String, Object>> orders(@RequestParam(required = false) String state,
                                            @RequestParam(required = false) String storeId,
                                            @RequestParam(required = false) String bizWeek) {
        return distService.listObs(state, storeId, bizWeek);
    }

    /** 近似摘要列表（含编译版本）。 */
    @GetMapping("/summaries")
    public List<Map<String, Object>> summaries() {
        return distService.listSummaries();
    }

    /** 编译版本历史。 */
    @GetMapping("/compiles")
    public List<DistCompile> compiles() {
        return distService.listCompiles();
    }

    // ---------- 修订（局部重建） ----------

    /** 迟到部分退款。 */
    @PostMapping("/revisions/late-refund")
    public Map<String, Object> lateRefund(@RequestBody Map<String, Object> body) {
        return revisionService.lateRefund(
                String.valueOf(body.get("refundId")),
                String.valueOf(body.get("orderId")),
                String.valueOf(body.get("storeId")),
                LocalDate.parse(String.valueOf(body.get("businessDate"))),
                new BigDecimal(String.valueOf(body.get("amount"))));
    }

    /** 订单更正（金额/状态）。 */
    @PostMapping("/revisions/correction")
    public Map<String, Object> correction(@RequestBody Map<String, Object> body) {
        return revisionService.correction(
                String.valueOf(body.get("orderId")),
                body.get("newAmount") == null ? null : new BigDecimal(String.valueOf(body.get("newAmount"))),
                body.get("newStatus") == null ? null : String.valueOf(body.get("newStatus")));
    }

    /** 订单去重决议。 */
    @PostMapping("/revisions/dedup")
    public Map<String, Object> dedup(@RequestBody Map<String, Object> body,
                                     @RequestAttribute(required = false) String user) {
        return revisionService.dedupDecision(
                String.valueOf(body.get("canonicalKey")),
                String.valueOf(body.get("duplicateKey")),
                user == null ? "unknown" : user);
    }

    @GetMapping("/revisions/dedup")
    public List<DedupDecision> dedupDecisions() {
        return revisionService.listDedupDecisions();
    }

    // ---------- 发布快照 ----------

    @PostMapping("/publications")
    public Map<String, Object> publish(@RequestBody Map<String, Object> body,
                                       @RequestAttribute(required = false) String user) {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = body.get("params") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        return distService.publish(String.valueOf(body.getOrDefault("name", "分布快照")), params, user);
    }

    @GetMapping("/publications")
    public List<Map<String, Object>> publications() {
        return distService.listPublications();
    }

    @GetMapping("/publications/{id}")
    public Map<String, Object> publication(@PathVariable Long id) {
        return distService.getPublication(id);
    }

    /** 发布快照 vs 当前重算对比（快照保持冻结）。 */
    @GetMapping("/publications/{id}/compare")
    public Map<String, Object> comparePublication(@PathVariable Long id) {
        return distService.comparePublication(id);
    }
}
