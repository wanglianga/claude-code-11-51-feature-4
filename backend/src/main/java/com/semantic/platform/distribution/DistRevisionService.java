package com.semantic.platform.distribution;

import com.semantic.platform.run.ReplayService;
import com.semantic.platform.source.OrderFact;
import com.semantic.platform.source.OrderFactRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分布修订服务：晚到退款、金额/状态更正、订单去重决议。
 * 每种修订只重建受影响的订单集合与摘要版本（局部重建），
 * 已发布快照保持当时的样本与数值（见 {@link DistService#comparePublication}）。
 */
@Service
public class DistRevisionService {

    private final DistService distService;
    private final ReplayService replayService;
    private final OrderFactRepository orderRepo;
    private final DedupDecisionRepository dedupRepo;

    public DistRevisionService(DistService distService, ReplayService replayService,
                               OrderFactRepository orderRepo, DedupDecisionRepository dedupRepo) {
        this.distService = distService;
        this.replayService = replayService;
        this.orderRepo = orderRepo;
        this.dedupRepo = dedupRepo;
    }

    /**
     * 迟到部分退款：登记退款（复用指标平台的回放通道，同源同截止点），
     * 然后只重建该订单的观察值与其所在（门店 × 营业周）摘要。
     */
    @Transactional
    public Map<String, Object> lateRefund(String refundId, String orderId, String storeId,
                                          LocalDate businessDate, BigDecimal amount) {
        Map<String, Object> replay = replayService.lateRefund(refundId, orderId, storeId, businessDate, amount);
        Map<String, Object> compile = distService.compile("late_refund", refundId, List.of(orderId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("refundId", refundId);
        out.put("orderId", orderId);
        out.put("amount", amount);
        out.put("replay", replay.get("impact"));
        out.put("rebuild", compile);
        out.put("note", "仅重建受影响订单集合与摘要版本；已发布快照保持当时样本与数值");
        return out;
    }

    /**
     * 订单更正：修正订单金额或状态（数据源更正），只重建该订单与其所在摘要。
     */
    @Transactional
    public Map<String, Object> correction(String orderId, BigDecimal newAmount, String newStatus) {
        OrderFact order = orderRepo.findAll().stream()
                .filter(o -> o.getOrderId().equals(orderId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + orderId));
        if (newAmount != null) order.setAmount(newAmount);
        if (newStatus != null) order.setStatus(newStatus);
        orderRepo.save(order);
        Map<String, Object> compile = distService.compile("correction", orderId, List.of(orderId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("newAmount", newAmount);
        out.put("newStatus", newStatus);
        out.put("rebuild", compile);
        return out;
    }

    /**
     * 订单去重决议：裁定 duplicateKey 与 canonicalKey 为同一真实订单。
     * 决议持久化（对后续全量/局部编译均生效），本次只重建两个订单键及其摘要。
     */
    @Transactional
    public Map<String, Object> dedupDecision(String canonicalKey, String duplicateKey, String decidedBy) {
        if (canonicalKey.equals(duplicateKey)) {
            throw new IllegalArgumentException("规范订单键与重复订单键不能相同");
        }
        DedupDecision existing = dedupRepo.findByDuplicateKey(duplicateKey);
        if (existing == null) {
            DedupDecision d = new DedupDecision();
            d.setCanonicalKey(canonicalKey);
            d.setDuplicateKey(duplicateKey);
            d.setDecidedBy(decidedBy);
            dedupRepo.save(d);
        }
        Map<String, Object> compile = distService.compile("dedup",
                canonicalKey + "<-" + duplicateKey, List.of(canonicalKey, duplicateKey));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("canonicalKey", canonicalKey);
        out.put("duplicateKey", duplicateKey);
        out.put("decisionId", existing != null ? existing.getId() : dedupRepo.findByDuplicateKey(duplicateKey).getId());
        out.put("rebuild", compile);
        out.put("note", "重复订单键已排除（不复制订单）；仅重建受影响订单集合与摘要版本");
        return out;
    }

    public List<DedupDecision> listDedupDecisions() {
        return dedupRepo.findAll();
    }
}
