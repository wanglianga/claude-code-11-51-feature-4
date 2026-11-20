package com.semantic.platform.distribution;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** 订单去重决议：人工裁定 duplicate_key 与 canonical_key 为同一真实订单。 */
@Entity
@Table(name = "dist_dedup_decision")
public class DedupDecision {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "duplicate_key", nullable = false, unique = true)
    private String duplicateKey;

    @Column(name = "canonical_key", nullable = false)
    private String canonicalKey;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at", nullable = false)
    private OffsetDateTime decidedAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDuplicateKey() { return duplicateKey; }
    public void setDuplicateKey(String duplicateKey) { this.duplicateKey = duplicateKey; }
    public String getCanonicalKey() { return canonicalKey; }
    public void setCanonicalKey(String canonicalKey) { this.canonicalKey = canonicalKey; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String decidedBy) { this.decidedBy = decidedBy; }
    public OffsetDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(OffsetDateTime decidedAt) { this.decidedAt = decidedAt; }
}
