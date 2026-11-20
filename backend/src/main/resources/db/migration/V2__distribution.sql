-- ============================================================
-- V2：客单金额分布查询
-- 订单金额编译（稳定订单键 → 单一金额观察值）+ 可合并近似分位摘要 + 发布快照
-- ============================================================

-- ---------- 源数据：支付流水（一张订单可拆分多个支付渠道） ----------
CREATE TABLE payment_fact (
    id              BIGSERIAL PRIMARY KEY,
    payment_id      VARCHAR(64) NOT NULL UNIQUE,
    order_id        VARCHAR(64) NOT NULL,
    store_id        VARCHAR(32) NOT NULL,
    channel         VARCHAR(16) NOT NULL,           -- cash | card | wechat | alipay | groupon
    amount          NUMERIC(16,2) NOT NULL,
    paid_at         TIMESTAMPTZ NOT NULL,
    business_date   DATE,
    created_batch   BIGINT
);
CREATE INDEX idx_payment_order ON payment_fact(order_id);

-- ---------- 源数据：订单状态回执（同一 receipt_id 可重复到达，编译时去重） ----------
CREATE TABLE order_receipt (
    id              BIGSERIAL PRIMARY KEY,
    receipt_id      VARCHAR(64) NOT NULL,           -- 回执幂等键：重复回执同一 receipt_id
    order_id        VARCHAR(64) NOT NULL,
    store_id        VARCHAR(32) NOT NULL,
    status          VARCHAR(16) NOT NULL,           -- paid | completed | void | cancelled | unpaid
    amount          NUMERIC(16,2),
    receipt_at      TIMESTAMPTZ NOT NULL,           -- 回执业务时间（取最新定状态）
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),  -- 到达时间（重复回执靠它分行）
    created_batch   BIGINT
);
CREATE INDEX idx_receipt_order ON order_receipt(order_id);

-- ---------- 编译版本（每次全量编译或局部重建一行，携带退款状态截止点） ----------
CREATE TABLE dist_compile (
    id              BIGSERIAL PRIMARY KEY,
    trigger_type    VARCHAR(32) NOT NULL,           -- full | late_refund | correction | dedup | manual
    trigger_ref     VARCHAR(128),
    refund_cutoff   TIMESTAMPTZ NOT NULL,           -- 退款状态截止点：仅纳入 refunded_at <= cutoff 的退款
    affected_orders INTEGER NOT NULL DEFAULT 0,     -- 本次重建的订单键数
    notes           VARCHAR(512),
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ
);

-- ---------- 订单金额观察值（每个稳定订单键 × 每个编译版本一行；查询取每键最新版本） ----------
CREATE TABLE order_amount_obs (
    id              BIGSERIAL PRIMARY KEY,
    order_key       VARCHAR(64) NOT NULL,           -- 稳定订单键
    store_id        VARCHAR(32) NOT NULL,
    business_date   DATE NOT NULL,
    biz_week        VARCHAR(8) NOT NULL,            -- ISO 营业周，如 2026-W06
    channel         VARCHAR(16),                    -- 业态/渠道维度（订单主渠道）
    currency        VARCHAR(8) NOT NULL DEFAULT 'CNY',
    gross_amount    NUMERIC(16,2),                  -- 毛额（行合计 > 订单头 > 支付合计）
    refund_amount   NUMERIC(16,2) NOT NULL DEFAULT 0,  -- 截止点内退款合计
    net_amount      NUMERIC(16,2),                  -- 净额 = 毛额 - 截止点内退款
    state           VARCHAR(16) NOT NULL,           -- completed | pending | excluded
    state_reason    VARCHAR(256),                   -- 撤销/未支付/回执冲突/金额不一致/外币/超额退款...
    amount_source   VARCHAR(16),                    -- lines | header | payments
    compile_id      BIGINT NOT NULL REFERENCES dist_compile(id),
    UNIQUE (order_key, compile_id)
);
CREATE INDEX idx_obs_store_week ON order_amount_obs(store_id, biz_week);

-- ---------- 近似分位摘要（t-digest 质心，粒度 门店×营业周×渠道，查询时向上合并） ----------
CREATE TABLE dist_summary (
    id              BIGSERIAL PRIMARY KEY,
    store_id        VARCHAR(32) NOT NULL,
    biz_week        VARCHAR(8) NOT NULL,
    dim_channel     VARCHAR(16) NOT NULL,           -- 最细粒度：渠道；合并得到全渠道
    compile_id      BIGINT NOT NULL REFERENCES dist_compile(id),
    sample_count    INTEGER NOT NULL,               -- 有效样本数（completed 观察值）
    sum_amount      NUMERIC(20,2) NOT NULL,
    min_amount      NUMERIC(16,2),
    max_amount      NUMERIC(16,2),
    sketch_json     TEXT NOT NULL,                  -- t-digest 质心序列 [[mean,weight],...]
    built_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_summary_scope ON dist_summary(store_id, biz_week, dim_channel, compile_id);

-- ---------- 订单去重决议（人工裁定两个订单键为同一真实订单；编译时应用，全量/局部均生效） ----------
CREATE TABLE dist_dedup_decision (
    id              BIGSERIAL PRIMARY KEY,
    duplicate_key   VARCHAR(64) NOT NULL UNIQUE,    -- 被裁定为重复的订单键
    canonical_key   VARCHAR(64) NOT NULL,           -- 保留的规范订单键
    decided_by      VARCHAR(64),
    decided_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- 分布查询发布快照（冻结发布时的样本与数值，后续重建不回写） ----------
CREATE TABLE dist_publication (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    params_json     JSONB NOT NULL,                 -- 查询参数（范围/维度/过滤/阈值/路由）
    result_json     JSONB NOT NULL,                 -- 冻结结果（每格分位数/样本/截止点/误差界）
    compile_id      BIGINT NOT NULL,                -- 发布时的编译版本
    refund_cutoff   TIMESTAMPTZ,                    -- 发布时的退款状态截止点
    published_by    VARCHAR(64),
    published_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
