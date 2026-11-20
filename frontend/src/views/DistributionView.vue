<template>
  <div class="dist-view">
    <!-- 查询面板 -->
    <el-card>
      <template #header>
        <div class="card-header">
          <span>客单金额分布查询</span>
          <div>
            <el-button size="small" @click="recompile" :loading="compiling">重新编译</el-button>
            <el-button type="primary" size="small" @click="runQuery" :loading="loading">查询</el-button>
          </div>
        </div>
      </template>
      <el-form :inline="true" size="small">
        <el-form-item label="营业日期">
          <el-date-picker v-model="form.range" type="daterange" value-format="YYYY-MM-DD"
                          start-placeholder="开始" end-placeholder="结束" style="width: 240px" />
        </el-form-item>
        <el-form-item label="门店">
          <el-select v-model="form.storeIds" multiple collapse-tags style="width: 160px">
            <el-option v-for="s in ['S001','S002','S003','S004','S005']" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="业态维度">
          <el-select v-model="form.dims" multiple style="width: 130px">
            <el-option label="渠道" value="channel" />
          </el-select>
        </el-form-item>
        <el-form-item label="渠道过滤">
          <el-select v-model="form.channelFilter" clearable style="width: 110px">
            <el-option v-for="c in ['pos','online','delivery','groupon']" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="路线">
          <el-select v-model="form.route" style="width: 110px">
            <el-option label="自动" value="auto" />
            <el-option label="精确" value="exact" />
            <el-option label="近似" value="approx" />
          </el-select>
        </el-form-item>
        <el-form-item label="高额阈值">
          <el-input-number v-model="form.highThreshold" :min="1" style="width: 110px" />
        </el-form-item>
        <el-form-item label="最小样本">
          <el-input-number v-model="form.minSample" :min="1" style="width: 90px" />
        </el-form-item>
        <el-form-item label="误差上限">
          <el-input-number v-model="form.maxRankError" :min="0.0001" :max="1" :step="0.01" style="width: 100px" />
        </el-form-item>
      </el-form>
      <el-alert v-if="meta" type="info" :closable="false" class="meta-alert">
        <div>分位数定义：{{ meta.quantileDefinition }}</div>
        <div>零金额规则：{{ meta.zeroAmountRule }}</div>
        <div>退款状态截止点：{{ meta.refundCutoff || '-' }}　|　高额阈值：{{ meta.highThreshold }}　|　最小样本：{{ meta.minSample }}　|　误差上限：{{ meta.maxRankError }}</div>
        <div v-if="meta.approxUnsupportedReason" style="color: #e6a23c">{{ meta.approxUnsupportedReason }}</div>
      </el-alert>
    </el-card>

    <el-tabs v-model="tab" class="tabs">
      <!-- 分布结果 -->
      <el-tab-pane label="分布结果" name="result">
        <el-card shadow="never">
          <el-table :data="cells" stripe size="small" v-loading="loading">
            <el-table-column prop="storeId" label="门店" width="70" />
            <el-table-column prop="bizWeek" label="营业周" width="95" />
            <el-table-column label="业态" width="80">
              <template #default="{ row }">{{ row.dims?.channel || '全部' }}</template>
            </el-table-column>
            <el-table-column label="中位数" width="120">
              <template #default="{ row }"><MetricCell :m="row.median" /></template>
            </el-table-column>
            <el-table-column label="P90" width="120">
              <template #default="{ row }"><MetricCell :m="row.p90" /></template>
            </el-table-column>
            <el-table-column label="高额占比" width="120">
              <template #default="{ row }"><MetricCell :m="row.highRatio" ratio /></template>
            </el-table-column>
            <el-table-column label="样本/总体" width="90">
              <template #default="{ row }">{{ row.validSamples }}/{{ row.population }}</template>
            </el-table-column>
            <el-table-column prop="pendingCount" label="待核" width="60" />
            <el-table-column label="覆盖" width="80">
              <template #default="{ row }">
                <el-tag :type="covType(row.coverage?.status)" size="small">
                  {{ row.coverage?.coveredDays }}/{{ row.coverage?.totalDays }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="路线" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.route" :type="row.route === 'approx' ? 'warning' : 'success'" size="small">
                  {{ row.route === 'approx' ? '近似' : '精确' }}
                </el-tag>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="200">
              <template #default="{ row }">
                <el-tooltip :content="row.degradeReason || row.message || ''" :disabled="!(row.degradeReason || row.message)">
                  <el-tag :type="statusType(row)" size="small">{{ statusText(row) }}</el-tag>
                </el-tooltip>
              </template>
            </el-table-column>
          </el-table>
          <div class="actions">
            <el-input v-model="pubName" placeholder="快照名称" size="small" style="width: 200px" />
            <el-button type="primary" size="small" @click="publish" :disabled="!cells.length">发布快照</el-button>
          </div>
        </el-card>
      </el-tab-pane>

      <!-- 待核订单 -->
      <el-tab-pane label="待核订单" name="pending">
        <el-card shadow="never">
          <el-alert type="warning" :closable="false" title="无法确定最终状态或金额的订单，单列待核，不进入分位样本" />
          <el-table :data="pending" stripe size="small" style="margin-top: 12px">
            <el-table-column prop="orderKey" label="订单键" width="120" />
            <el-table-column prop="storeId" label="门店" width="70" />
            <el-table-column prop="bizWeek" label="营业周" width="95" />
            <el-table-column prop="grossAmount" label="毛额" width="100" />
            <el-table-column prop="stateReason" label="待核原因" />
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- 观察值明细 -->
      <el-tab-pane label="观察值明细" name="obs">
        <el-card shadow="never">
          <el-form :inline="true" size="small">
            <el-form-item label="状态">
              <el-select v-model="obsState" style="width: 130px" @change="loadObs">
                <el-option label="已完成" value="completed" />
                <el-option label="待核" value="pending" />
                <el-option label="已排除" value="excluded" />
              </el-select>
            </el-form-item>
          </el-form>
          <el-table :data="obs" stripe size="small" max-height="480">
            <el-table-column prop="orderKey" label="订单键" width="110" />
            <el-table-column prop="storeId" label="门店" width="65" />
            <el-table-column prop="bizWeek" label="营业周" width="90" />
            <el-table-column prop="channel" label="渠道" width="80" />
            <el-table-column prop="grossAmount" label="毛额" width="90" />
            <el-table-column prop="refundAmount" label="截止点内退款" width="110" />
            <el-table-column prop="netAmount" label="净额观察值" width="100" />
            <el-table-column prop="amountSource" label="金额口径" width="90" />
            <el-table-column prop="stateReason" label="说明" />
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- 发布快照 -->
      <el-tab-pane label="发布快照" name="pubs">
        <el-card shadow="never">
          <el-table :data="pubs" stripe size="small">
            <el-table-column prop="id" label="ID" width="60" />
            <el-table-column prop="name" label="名称" width="180" />
            <el-table-column prop="compileId" label="编译版本" width="90" />
            <el-table-column label="退款截止点" width="200">
              <template #default="{ row }">{{ row.refundCutoff || '-' }}</template>
            </el-table-column>
            <el-table-column prop="publishedBy" label="发布人" width="100" />
            <el-table-column label="操作" width="120">
              <template #default="{ row }">
                <el-button size="small" @click="compare(row)">对比当前</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-alert v-if="pubs.length" type="info" :closable="false" style="margin-top: 12px"
                    title="已发布结果保持发布时的样本与数值；迟到退款/更正/去重只重建受影响订单集合与摘要版本，不回写快照" />
        </el-card>
      </el-tab-pane>

      <!-- 数据修订 -->
      <el-tab-pane label="数据修订" name="rev">
        <el-card shadow="never">
          <el-row :gutter="16">
            <el-col :span="8">
              <h4>迟到部分退款</h4>
              <el-form size="small" label-width="80px">
                <el-form-item label="退款ID"><el-input v-model="rev.refundId" /></el-form-item>
                <el-form-item label="订单键"><el-input v-model="rev.orderId" /></el-form-item>
                <el-form-item label="门店"><el-input v-model="rev.storeId" /></el-form-item>
                <el-form-item label="营业日"><el-input v-model="rev.businessDate" placeholder="2026-02-20" /></el-form-item>
                <el-form-item label="金额"><el-input v-model="rev.amount" /></el-form-item>
                <el-button type="primary" size="small" @click="doLateRefund">提交</el-button>
              </el-form>
            </el-col>
            <el-col :span="8">
              <h4>订单更正</h4>
              <el-form size="small" label-width="80px">
                <el-form-item label="订单键"><el-input v-model="corr.orderId" /></el-form-item>
                <el-form-item label="新金额"><el-input v-model="corr.newAmount" placeholder="留空不改" /></el-form-item>
                <el-form-item label="新状态"><el-input v-model="corr.newStatus" placeholder="留空不改" /></el-form-item>
                <el-button type="primary" size="small" @click="doCorrection">提交</el-button>
              </el-form>
            </el-col>
            <el-col :span="8">
              <h4>订单去重决议</h4>
              <el-form size="small" label-width="80px">
                <el-form-item label="规范键"><el-input v-model="dedup.canonicalKey" placeholder="O-DUP" /></el-form-item>
                <el-form-item label="重复键"><el-input v-model="dedup.duplicateKey" placeholder="O-DUP2" /></el-form-item>
                <el-button type="primary" size="small" @click="doDedup">提交</el-button>
              </el-form>
            </el-col>
          </el-row>
          <el-alert v-if="revResult" type="success" :closable="false" style="margin-top: 12px">
            <pre>{{ JSON.stringify(revResult, null, 2) }}</pre>
          </el-alert>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 快照对比对话框 -->
    <el-dialog v-model="compareVisible" title="发布快照 vs 当前（快照保持冻结）" width="860px">
      <el-table :data="drift" stripe size="small">
        <el-table-column prop="cell" label="单元格" width="220" />
        <el-table-column prop="publishedMedian" label="发布中位数" width="110" />
        <el-table-column prop="currentMedian" label="当前中位数" width="110" />
        <el-table-column prop="publishedP90" label="发布P90" width="110" />
        <el-table-column prop="currentP90" label="当前P90" width="110" />
        <el-table-column prop="publishedSamples" label="发布样本" width="90" />
        <el-table-column prop="currentSamples" label="当前样本" width="90" />
        <el-table-column label="漂移" width="70">
          <template #default="{ row }">
            <el-tag :type="row.changed ? 'danger' : 'success'" size="small">{{ row.changed ? '有' : '无' }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, h, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api/client'

// 指标值单元格（含近似误差界）
const MetricCell = (props) => {
  if (!props.m) return h('span', '-')
  const v = props.m.value
  const text = v == null ? '-' : (props.ratio ? (v * 100).toFixed(2) + '%' : Number(v).toFixed(2))
  const bound = props.m.rankErrorBound
  const boundText = bound != null && bound > 0 ? ` ±${(bound * 100).toFixed(1)}%` : ''
  return h('span', [
    h('div', text),
    boundText ? h('div', { style: 'font-size:11px;color:#e6a23c' }, boundText) : null
  ])
}
MetricCell.props = { m: Object, ratio: Boolean }

const form = ref({
  range: ['2026-01-01', '2026-03-31'],
  storeIds: [],
  dims: [],
  channelFilter: null,
  route: 'auto',
  highThreshold: 1000,
  minSample: 10,
  maxRankError: 0.05
})
const tab = ref('result')
const loading = ref(false)
const compiling = ref(false)
const meta = ref(null)
const cells = ref([])
const pending = ref([])
const obs = ref([])
const obsState = ref('pending')
const pubs = ref([])
const pubName = ref('')
const rev = ref({ refundId: 'RF-DIST-1', orderId: 'O-GRP2', storeId: 'S001', businessDate: '2026-02-20', amount: '11500.00' })
const corr = ref({ orderId: '', newAmount: '', newStatus: '' })
const dedup = ref({ canonicalKey: 'O-DUP', duplicateKey: 'O-DUP2' })
const revResult = ref(null)
const compareVisible = ref(false)
const drift = ref([])

function params() {
  const p = {
    from: form.value.range?.[0],
    to: form.value.range?.[1],
    route: form.value.route,
    highThreshold: form.value.highThreshold,
    minSample: form.value.minSample,
    maxRankError: form.value.maxRankError
  }
  if (form.value.storeIds?.length) p.storeIds = form.value.storeIds
  if (form.value.dims?.length) p.dims = form.value.dims
  if (form.value.channelFilter) p.filters = { channel: form.value.channelFilter }
  return p
}

async function runQuery() {
  loading.value = true
  try {
    const res = await api.post('/dist/query', params())
    meta.value = res.data.meta
    cells.value = res.data.cells
  } catch (e) {
    ElMessage.error('查询失败: ' + (e.response?.data?.error || e.message))
  } finally {
    loading.value = false
  }
}

async function recompile() {
  compiling.value = true
  try {
    const res = await api.post('/dist/compile', {})
    ElMessage.success(`编译完成：${res.data.compiledOrders} 订单（完成 ${res.data.completed} / 待核 ${res.data.pending} / 排除 ${res.data.excluded}）`)
    runQuery()
  } catch (e) {
    ElMessage.error('编译失败: ' + (e.response?.data?.error || e.message))
  } finally {
    compiling.value = false
  }
}

async function loadPending() {
  const res = await api.get('/dist/orders', { params: { state: 'pending' } })
  pending.value = res.data
}

async function loadObs() {
  const res = await api.get('/dist/orders', { params: { state: obsState.value } })
  obs.value = res.data
}

async function loadPubs() {
  const res = await api.get('/dist/publications')
  pubs.value = res.data
}

async function publish() {
  try {
    const res = await api.post('/dist/publications', { name: pubName.value || '分布快照', params: params() })
    ElMessage.success(`已发布快照 #${res.data.id}（冻结当时样本与数值）`)
    pubName.value = ''
    loadPubs()
  } catch (e) {
    ElMessage.error('发布失败: ' + (e.response?.data?.error || e.message))
  }
}

async function compare(row) {
  try {
    const res = await api.get(`/dist/publications/${row.id}/compare`)
    drift.value = res.data.drift
    compareVisible.value = true
  } catch (e) {
    ElMessage.error('对比失败: ' + (e.response?.data?.error || e.message))
  }
}

async function doLateRefund() {
  try {
    const res = await api.post('/dist/revisions/late-refund', rev.value)
    revResult.value = res.data
    ElMessage.success('迟到退款已登记并局部重建')
    runQuery()
  } catch (e) {
    ElMessage.error('失败: ' + (e.response?.data?.error || e.message))
  }
}

async function doCorrection() {
  try {
    const body = { orderId: corr.value.orderId }
    if (corr.value.newAmount) body.newAmount = corr.value.newAmount
    if (corr.value.newStatus) body.newStatus = corr.value.newStatus
    const res = await api.post('/dist/revisions/correction', body)
    revResult.value = res.data
    ElMessage.success('更正已提交并局部重建')
    runQuery()
  } catch (e) {
    ElMessage.error('失败: ' + (e.response?.data?.error || e.message))
  }
}

async function doDedup() {
  try {
    const res = await api.post('/dist/revisions/dedup', dedup.value)
    revResult.value = res.data
    ElMessage.success('去重决议已生效并局部重建')
    runQuery()
  } catch (e) {
    ElMessage.error('失败: ' + (e.response?.data?.error || e.message))
  }
}

function covType(s) {
  return s === 'ok' ? 'success' : s === 'partial' ? 'warning' : 'info'
}

function statusType(row) {
  if (row.status !== 'ok') return 'info'
  if (row.degraded) return 'warning'
  return 'success'
}

function statusText(row) {
  if (row.status === 'ok') return row.degraded ? '降级精确' : '正常'
  if (row.status === 'unavailable_small_sample') return '样本不足'
  if (row.status === 'no_data') return '无有效样本'
  return row.status
}

onMounted(() => {
  runQuery()
  loadPending()
  loadObs()
  loadPubs()
})
</script>

<style scoped>
.dist-view { padding: 20px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.tabs { margin-top: 16px; }
.meta-alert { margin-top: 8px; font-size: 12px; }
.actions { margin-top: 12px; display: flex; gap: 8px; justify-content: flex-end; }
h4 { margin: 0 0 12px 0; }
pre { margin: 0; font-size: 12px; white-space: pre-wrap; }
</style>
