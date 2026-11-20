-- ============================================================
-- 经营指标语义层与自助分析平台 — 核心数据模型
-- ============================================================

-- ---------- 源数据：批次 ----------
CREATE TABLE source_batch (
    id              BIGSERIAL PRIMARY KEY,
    source_type     VARCHAR(32) NOT NULL,          -- orders | refunds | store_hours | holidays
    store_id        VARCHAR(32),                    -- NULL 表示全部门店
    status          VARCHAR(16) NOT NULL DEFAULT 'loaded',  -- loaded | backfilling
    record_count    INTEGER NOT NULL DEFAULT 0,
    period_start    DATE,
    period_end      DATE,
    imported_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- 源数据：订单 ----------
CREATE TABLE order_fact (
    id              BIGSERIAL PRIMARY KEY,
    order_id        VARCHAR(64) NOT NULL,
    store_id        VARCHAR(32) NOT NULL,
    ordered_at      TIMESTAMPTZ NOT NULL,
    paid_at         TIMESTAMPTZ,
    business_date   DATE,                          -- 门店营业日归属（迟到补录算入对应营业日）
    amount          NUMERIC(16,2) NOT NULL,
    currency        VARCHAR(8) NOT NULL DEFAULT 'CNY',
    status          VARCHAR(16) NOT NULL,          -- completed | void | partial_refund | full_refund
    channel         VARCHAR(16),                   -- pos | online | delivery
    created_batch   BIGINT REFERENCES source_batch(id),
    UNIQUE (order_id)
);

-- ---------- 源数据：订单行（与订单一对多） ----------
CREATE TABLE order_line (
    id              BIGSERIAL PRIMARY KEY,
    line_id         VARCHAR(64) NOT NULL UNIQUE,
    order_id        VARCHAR(64) NOT NULL,
    store_id        VARCHAR(32) NOT NULL,
    business_date   DATE,
    sku             VARCHAR(32),
    quantity        INTEGER NOT NULL,
    unit_price      NUMERIC(16,2) NOT NULL,
    amount          NUMERIC(16,2) NOT NULL,        -- quantity * unit_price
    created_batch   BIGINT REFERENCES source_batch(id)
);

-- ---------- 源数据：退款 ----------
CREATE TABLE refund_fact (
    id              BIGSERIAL PRIMARY KEY,
    refund_id       VARCHAR(64) NOT NULL UNIQUE,
    order_id        VARCHAR(64) NOT NULL,
    store_id        VARCHAR(32) NOT NULL,
    refunded_at     TIMESTAMPTZ NOT NULL,
    business_date   DATE,
    amount          NUMERIC(16,2) NOT NULL,
    reason          VARCHAR(64),
    created_batch   BIGINT REFERENCES source_batch(id)
);

-- ---------- 源数据：门店营业状态 ----------
CREATE TABLE store_status (
    id              BIGSERIAL PRIMARY KEY,
    store_id        VARCHAR(32) NOT NULL,
    business_date   DATE NOT NULL,
    status          VARCHAR(16) NOT NULL,          -- open | closed | temp_closed
    open_hours      NUMERIC(5,2),
    UNIQUE (store_id, business_date)
);

-- ---------- 源数据：节假日 ----------
CREATE TABLE holiday (
    id              BIGSERIAL PRIMARY KEY,
    holiday_date    DATE NOT NULL,
    name            VARCHAR(64),
    type            VARCHAR(16)                    -- public_holiday | store_closure | promotion
);

-- ---------- 字段注册表（源字段血缘） ----------
CREATE TABLE source_field (
    id              BIGSERIAL PRIMARY KEY,
    source_type     VARCHAR(32) NOT NULL,
    field_name      VARCHAR(64) NOT NULL,
    field_type      VARCHAR(16) NOT NULL,          -- amount | quantity | ratio | timestamp | enum | text
    nullable        BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (source_type, field_name)
);

-- ---------- 指标注册表 ----------
CREATE TABLE metric_def (
    id              BIGSERIAL PRIMARY KEY,
    metric_key      VARCHAR(64) UNIQUE NOT NULL,   -- 业务键，如 net_sales
    name            VARCHAR(128) NOT NULL,         -- 显示名，如 净销售额
    description     TEXT,
    status          VARCHAR(16) NOT NULL DEFAULT 'draft',  -- draft | published | deprecated
    grain           VARCHAR(16) NOT NULL DEFAULT 'day',    -- day | month | quarter
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- 指标版本（定义快照） ----------
CREATE TABLE metric_version (
    id              BIGSERIAL PRIMARY KEY,
    metric_key      VARCHAR(64) NOT NULL,
    version         INTEGER NOT NULL,
    definition_json JSONB NOT NULL,                -- 表达式 AST
    grain           VARCHAR(16) NOT NULL DEFAULT 'day',  -- 该版本的声明粒度
    status          VARCHAR(16) NOT NULL DEFAULT 'draft',  -- draft | pending | approved | published | rejected | deprecated
    definition_hash VARCHAR(64),
    created_by      VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    UNIQUE (metric_key, version)
);

-- ---------- 指标依赖边（AST 中提取） ----------
CREATE TABLE metric_edge (
    id              BIGSERIAL PRIMARY KEY,
    from_metric     VARCHAR(64) NOT NULL,          -- 引用方
    to_metric       VARCHAR(64) NOT NULL,          -- 被引用方
    from_version    INTEGER NOT NULL,
    edge_type       VARCHAR(16) NOT NULL DEFAULT 'ref',  -- ref | cohort_base
    UNIQUE (from_metric, to_metric, from_version)
);

-- ---------- 运行（自助分析） ----------
CREATE TABLE run (
    id              BIGSERIAL PRIMARY KEY,
    metric_key      VARCHAR(64) NOT NULL,
    metric_version_id BIGINT NOT NULL,
    grain           VARCHAR(16) NOT NULL DEFAULT 'day',
    period_from     DATE,                           -- 运行周期
    period_to       DATE,
    scope_json      JSONB,                          -- 过滤：store_ids / region / channel
    status          VARCHAR(16) NOT NULL DEFAULT 'success',  -- success | partial | failed
    data_cutoff_json JSONB,                         -- 数据截止点与覆盖范围
    result_kind     VARCHAR(16) NOT NULL DEFAULT 'metric',   -- metric | cohort_growth
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    error           TEXT
);

-- ---------- 运行结果单元格 ----------
CREATE TABLE run_cell (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT NOT NULL REFERENCES run(id),
    period_key      VARCHAR(32) NOT NULL,           -- '2026-11' / '2026-11-18'
    dims_json       JSONB,                          -- 维度键，如 {"store_id":"S001"}
    value_num       NUMERIC(20,4),                  -- 分子（比率）或值
    value_den       NUMERIC(20,4),                  -- 分母（比率）
    cell_status     VARCHAR(16) NOT NULL DEFAULT 'ok',  -- ok | no_data | excluded | error
    message         VARCHAR(256)
);

-- ---------- 单元格血缘（下钻） ----------
CREATE TABLE run_cell_lineage (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT NOT NULL,
    cell_key        VARCHAR(256) NOT NULL,
    record_type     VARCHAR(16) NOT NULL,           -- order | refund | store_status
    record_id       VARCHAR(64) NOT NULL,
    contribution    NUMERIC(20,4),
    included        BOOLEAN NOT NULL DEFAULT TRUE,
    reason          VARCHAR(256)
);

-- ---------- 指标发布（版本锁定） ----------
CREATE TABLE metric_release (
    id              BIGSERIAL PRIMARY KEY,
    metric_key      VARCHAR(64) NOT NULL,
    version         INTEGER NOT NULL,
    released_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_by     VARCHAR(64),
    note            VARCHAR(256)
);

-- ---------- 报表 ----------
CREATE TABLE report (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'draft',  -- draft | published | archived
    created_by      VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);

CREATE TABLE report_item (
    id              BIGSERIAL PRIMARY KEY,
    report_id       BIGINT NOT NULL REFERENCES report(id),
    metric_key      VARCHAR(64) NOT NULL,
    metric_version_id BIGINT NOT NULL,              -- 锁定的版本
    run_id          BIGINT NOT NULL,                -- 锁定的运行
    display_json    JSONB,                          -- 展示配置
    UNIQUE (report_id, metric_key)
);

-- ---------- 审批 ----------
CREATE TABLE approval (
    id              BIGSERIAL PRIMARY KEY,
    metric_key      VARCHAR(64) NOT NULL,
    from_version    INTEGER,
    to_version      INTEGER NOT NULL,
    change_type     VARCHAR(32) NOT NULL,           -- create | edit | hierarchy_change | deprecate
    status          VARCHAR(16) NOT NULL DEFAULT 'pending',  -- pending | approved | rejected
    impact_json     JSONB,                          -- 依赖报表与层级差异
    submitted_by    VARCHAR(64),
    submitted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by      VARCHAR(64),
    decided_at      TIMESTAMPTZ,
    note            VARCHAR(512)
);

-- ---------- 运行与源数据的影响关系 ----------
CREATE TABLE run_impact (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT NOT NULL,
    source_type     VARCHAR(32) NOT NULL,
    source_batch_id BIGINT,
    impact_type     VARCHAR(32) NOT NULL,           -- late_refund | backfill | correction
    resolved        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- 指标候选（迟到退款触发的新运行） ----------
CREATE TABLE run_candidate (
    id              BIGSERIAL PRIMARY KEY,
    metric_key      VARCHAR(64) NOT NULL,
    trigger_type    VARCHAR(32) NOT NULL,           -- late_refund | backfill | manual
    trigger_ref     VARCHAR(64),
    status          VARCHAR(16) NOT NULL DEFAULT 'pending',  -- pending | adopted | rejected
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
