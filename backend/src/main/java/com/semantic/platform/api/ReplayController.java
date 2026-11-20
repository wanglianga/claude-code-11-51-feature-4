package com.semantic.platform.api;

import com.semantic.platform.run.ReplayService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/replay")
public class ReplayController {

    private final ReplayService replayService;

    public ReplayController(ReplayService replayService) {
        this.replayService = replayService;
    }

    /** 迟到退款到达（回放场景）。 */
    @PostMapping("/late-refund")
    public Map<String, Object> lateRefund(@RequestBody Map<String, Object> body) {
        String refundId = (String) body.get("refundId");
        String orderId = (String) body.get("orderId");
        String storeId = (String) body.get("storeId");
        LocalDate businessDate = LocalDate.parse((String) body.get("businessDate"));
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        return replayService.lateRefund(
                refundId == null ? "R-LATE-" + System.currentTimeMillis() : refundId,
                orderId, storeId, businessDate, amount);
    }
}
