package com.semantic.platform.run;

import com.semantic.platform.source.RefundFact;
import com.semantic.platform.source.RefundFactRepository;
import com.semantic.platform.source.SourceBatch;
import com.semantic.platform.source.SourceBatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 回放服务：迟到退款场景。退款晚到后，影响计算生成新运行候选，
 * 已发布报表继续引用原运行，经营负责人决定是否发布更正。
 */
@Service
public class ReplayService {

    private final RefundFactRepository refundRepo;
    private final SourceBatchRepository batchRepo;
    private final ImpactService impactService;

    public ReplayService(RefundFactRepository refundRepo, SourceBatchRepository batchRepo,
                         ImpactService impactService) {
        this.refundRepo = refundRepo;
        this.batchRepo = batchRepo;
        this.impactService = impactService;
    }

    /** 迟到退款到达：登记退款记录 + 批次，触发影响计算。 */
    @Transactional
    public Map<String, Object> lateRefund(String refundId, String orderId, String storeId,
                                          LocalDate businessDate, BigDecimal amount) {
        // 幂等：若已存在同 id 退款则直接触发影响计算
        RefundFact existing = refundRepo.findAll().stream()
                .filter(r -> r.getRefundId().equals(refundId)).findFirst().orElse(null);
        if (existing == null) {
            RefundFact r = new RefundFact();
            r.setRefundId(refundId);
            r.setOrderId(orderId);
            r.setStoreId(storeId);
            r.setRefundedAt(OffsetDateTime.now());
            r.setBusinessDate(businessDate);
            r.setAmount(amount);
            r.setReason("迟到退款");
            refundRepo.save(r);
        }
        // 登记批次（迟到数据）
        SourceBatch batch = new SourceBatch();
        batch.setSourceType("refunds");
        batch.setStoreId(storeId);
        batch.setStatus("loaded");
        batch.setPeriodStart(businessDate);
        batch.setPeriodEnd(businessDate);
        batch.setRecordCount(1);
        SourceBatch savedBatch = batchRepo.save(batch);
        // 影响计算：生成新运行候选
        Map<String, Object> impact = impactService.onBatchImported(savedBatch);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("refundId", refundId);
        out.put("amount", amount);
        out.put("businessDate", businessDate);
        out.put("impact", impact);
        return out;
    }

    /** 退款金额变化（用于演示）：重新计算某订单退款总额。 */
    public BigDecimal refundTotalFor(String orderId) {
        return refundRepo.findAll().stream()
                .filter(r -> r.getOrderId().equals(orderId))
                .map(RefundFact::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
