<template>
  <div class="approvals-view">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>审批中心</span>
          <el-button size="small" @click="loadApprovals">刷新</el-button>
        </div>
      </template>

      <el-table :data="approvals" stripe style="width: 100%">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="metricKey" label="指标" width="150" />
        <el-table-column prop="fromVersion" label="从版本" width="100" />
        <el-table-column prop="toVersion" label="到版本" width="100" />
        <el-table-column prop="changeType" label="变更类型" width="120" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="submittedAt" label="提交时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.submittedAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button v-if="row.status === 'pending'" size="small" type="success" @click="approve(row)">批准</el-button>
            <el-button v-if="row.status === 'pending'" size="small" type="danger" @click="reject(row)">拒绝</el-button>
            <el-button size="small" @click="viewImpact(row)">影响</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 影响详情 -->
      <el-card v-if="selectedApproval" class="impact-detail" shadow="never">
        <template #header>
          <span>审批影响详情 (ID: {{ selectedApproval.id }})</span>
        </template>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="指标">{{ selectedApproval.metricKey }}</el-descriptions-item>
          <el-descriptions-item label="变更类型">{{ selectedApproval.changeType }}</el-descriptions-item>
          <el-descriptions-item label="从版本">{{ selectedApproval.fromVersion }}</el-descriptions-item>
          <el-descriptions-item label="到版本">{{ selectedApproval.toVersion }}</el-descriptions-item>
        </el-descriptions>
        <el-divider>影响分析</el-divider>
        <div v-if="impactData">
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="下游指标">
              {{ impactData.downstreamMetrics && impactData.downstreamMetrics.length ? impactData.downstreamMetrics.join(', ') : '无' }}
            </el-descriptions-item>
            <el-descriptions-item label="受影响报表">
              {{ impactData.affectedReports && impactData.affectedReports.length ? impactData.affectedReports.length + ' 个' : '无' }}
            </el-descriptions-item>
            <el-descriptions-item label="层级差异">
              {{ impactData.hierarchyDiff ? '存在层级差异，需核对展示粒度' : '无层级差异' }}
            </el-descriptions-item>
            <el-descriptions-item label="并发待审批">
              {{ impactData.concurrentPending && impactData.concurrentPending.length ? impactData.concurrentPending.length + ' 个' : '无' }}
            </el-descriptions-item>
          </el-descriptions>
        </div>
      </el-card>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api/client'

const approvals = ref([])
const selectedApproval = ref(null)
const impactData = ref(null)

async function loadApprovals() {
  try {
    const res = await api.get('/approvals')
    approvals.value = res.data
  } catch (e) {
    ElMessage.error('加载审批失败: ' + (e.response?.data?.error || e.message))
  }
}

async function approve(row) {
  try {
    await ElMessageBox.confirm('确定要批准该审批吗？批准后将发布新版本。', '批准确认', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await api.post(`/approvals/${row.id}/approve`)
    ElMessage.success('批准成功')
    loadApprovals()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('批准失败: ' + (e.response?.data?.error || e.message))
    }
  }
}

async function reject(row) {
  try {
    const { value: note } = await ElMessageBox.prompt('请输入拒绝原因', '拒绝确认', {
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    })
    await api.post(`/approvals/${row.id}/reject`, { note })
    ElMessage.success('已拒绝')
    loadApprovals()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('拒绝失败: ' + (e.response?.data?.error || e.message))
    }
  }
}

async function viewImpact(row) {
  selectedApproval.value = row
  try {
    const res = await api.get(`/approvals/${row.id}`)
    impactData.value = JSON.parse(res.data.impactJson || '{}')
  } catch (e) {
    ElMessage.error('加载影响详情失败: ' + (e.response?.data?.error || e.message))
  }
}

function formatTime(time) {
  if (!time) return '-'
  return new Date(time).toLocaleString('zh-CN')
}

function statusType(status) {
  const map = { pending: 'warning', approved: 'success', rejected: 'danger' }
  return map[status] || 'info'
}

onMounted(() => {
  loadApprovals()
})
</script>

<style scoped>
.approvals-view {
  padding: 20px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.impact-detail {
  margin-top: 20px;
}
</style>
