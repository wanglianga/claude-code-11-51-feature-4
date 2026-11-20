package com.semantic.platform.distribution;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** 编译版本：每次全量编译或局部重建一行，携带退款状态截止点。 */
@Entity
@Table(name = "dist_compile")
public class DistCompile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trigger_type", nullable = false)
    private String triggerType;

    @Column(name = "trigger_ref")
    private String triggerRef;

    @Column(name = "refund_cutoff", nullable = false)
    private OffsetDateTime refundCutoff;

    @Column(name = "affected_orders", nullable = false)
    private int affectedOrders;

    private String notes;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt = OffsetDateTime.now();

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public String getTriggerRef() { return triggerRef; }
    public void setTriggerRef(String triggerRef) { this.triggerRef = triggerRef; }
    public OffsetDateTime getRefundCutoff() { return refundCutoff; }
    public void setRefundCutoff(OffsetDateTime refundCutoff) { this.refundCutoff = refundCutoff; }
    public int getAffectedOrders() { return affectedOrders; }
    public void setAffectedOrders(int affectedOrders) { this.affectedOrders = affectedOrders; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(OffsetDateTime finishedAt) { this.finishedAt = finishedAt; }
}
