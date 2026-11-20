<template>
  <div class="sources-view">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>数据源与批次</span>
          <el-button type="primary" size="small" @click="importBatch">导入批次</el-button>
        </div>
      </template>

      <!-- 批次列表 -->
      <el-table :data="batches" stripe style="width: 100%">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="sourceType" label="来源类型" width="120" />
        <el-table-column prop="storeId" label="门店" width="100">
          <template #default="{ row }">
            {{ row.storeId || '全部' }}
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="periodStart" label="开始日期" width="120" />
        <el-table-column prop="periodEnd" label="结束日期" width="120" />
        <el-table-column prop="recordCount" label="记录数" width="100" />
        <el-table-column prop="importedAt" label="导入时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.importedAt) }}
          </template>
        </el-table-column>
      </el-table>

      <!-- 覆盖范围 -->
      <el-card class="coverage-card" shadow="never">
        <template #header>
          <span>覆盖范围</span>
        </template>
        <el-table :data="coverageData" stripe size="small">
          <el-table-column prop="source" label="来源" width="150" />
          <el-table-column prop="store" label="门店" width="100" />
          <el-table-column prop="coveredDays" label="覆盖天数" width="120" />
          <el-table-column prop="note" label="说明" />
        </el-table>
      </el-card>

      <!-- 迟到退款回放 -->
      <el-card class="replay-card" shadow="never">
        <template #header>
          <span>场景回放</span>
        </template>
        <el-form :model="replayForm" label-width="100px" class="replay-form">
          <el-form-item label="退款ID">
            <el-input v-model="replayForm.refundId" placeholder="如 R003" />
          </el-form-item>
          <el-form-item label="订单ID">
            <el-input v-model="replayForm.orderId" placeholder="如 O001" />
          </el-form-item>
          <el-form-item label="门店">
            <el-input v-model="replayForm.storeId" placeholder="如 S001" />
          </el-form-item>
          <el-form-item label="营业日期">
            <el-date-picker v-model="replayForm.businessDate" type="date" placeholder="选择日期" />
          </el-form-item>
          <el-form-item label="金额">
            <el-input-number v-model="replayForm.amount" :min="0" :precision="2" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="replayLateRefund">回放迟到退款</el-button>
          </el-form-item>
        </el-form>
        <el-alert v-if="replayResult" type="success" :closable="false" show-icon>
          <template #title>回放完成</template>
          <div>影响指标: {{ replayResult.impact?.affectedMetrics?.join(', ') }}</div>
          <div>生成候选: {{ replayResult.impact?.candidates?.length }} 个</div>
        </el-alert>
      </el-card>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api/client'

const batches = ref([])
const coverageData = ref([])
const replayForm = ref({
  refundId: '',
  orderId: '',
  storeId: '',
  businessDate: null,
  amount: 0
})
const replayResult = ref(null)

async function loadBatches() {
  try {
    const res = await api.get('/sources/batches')
    batches.value = res.data
  } catch (e) {
    ElMessage.error('加载批次失败: ' + (e.response?.data?.error || e.message))
  }
}

async function loadCoverage() {
  try {
    const res = await api.get('/sources/coverage', {
      params: { from: '2026-01-01', to: '2026-03-31' }
    })
    coverageData.value = Object.entries(res.data.coverage || {}).map(([key, days]) => {
      const [source, store] = key.split('|')
      return { source, store, coveredDays: days, note: '已覆盖' }
    })
  } catch (e) {
    ElMessage.error('加载覆盖范围失败: ' + (e.response?.data?.error || e.message))
  }
}

async function importBatch() {
  ElMessage.info('批次导入功能（演示）')
}

async function replayLateRefund() {
  try {
    const res = await api.post('/replay/late-refund', {
      refundId: replayForm.value.refundId,
      orderId: replayForm.value.orderId,
      storeId: replayForm.value.storeId,
      businessDate: replayForm.value.businessDate ? new Date(replayForm.value.businessDate).toISOString().split('T')[0] : null,
      amount: replayForm.value.amount
    })
    replayResult.value = res.data
    ElMessage.success('回放完成，已生成运行候选')
  } catch (e) {
    ElMessage.error('回放失败: ' + (e.response?.data?.error || e.message))
  }
}

function formatTime(time) {
  if (!time) return '-'
  return new Date(time).toLocaleString('zh-CN')
}

function statusType(status) {
  const map = { loaded: 'success', backfilling: 'warning' }
  return map[status] || 'info'
}

onMounted(() => {
  loadBatches()
  loadCoverage()
})
</script>

<style scoped>
.sources-view {
  padding: 20px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.coverage-card,
.replay-card {
  margin-top: 20px;
}
.replay-form {
  max-width: 500px;
}
</style>
