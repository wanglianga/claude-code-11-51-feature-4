<template>
  <div class="reports-view">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>报表中心</span>
          <el-button type="primary" size="small" @click="createReport">新建报表</el-button>
        </div>
      </template>

      <el-table :data="reports" stripe style="width: 100%">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="name" label="报表名称" width="200" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="250">
          <template #default="{ row }">
            <el-button size="small" @click="viewReport(row)">查看</el-button>
            <el-button v-if="row.status === 'draft'" size="small" type="primary" @click="publishReport(row)">发布</el-button>
            <el-button v-if="row.status === 'published'" size="small" type="warning" @click="correctReport(row)">更正</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 报表详情 -->
      <el-card v-if="selectedReport" class="report-detail" shadow="never">
        <template #header>
          <span>报表详情: {{ selectedReport.name }}</span>
        </template>
        <el-table :data="reportItems" stripe size="small">
          <el-table-column prop="metricKey" label="指标" width="150" />
          <el-table-column prop="metricVersionId" label="版本ID" width="100" />
          <el-table-column prop="runId" label="运行ID" width="100" />
          <el-table-column label="值" width="150">
            <template #default="{ row }">
              {{ getMetricValue(row) }}
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api/client'

const reports = ref([])
const selectedReport = ref(null)
const reportItems = ref([])

async function loadReports() {
  try {
    const res = await api.get('/reports')
    reports.value = res.data
  } catch (e) {
    ElMessage.error('加载报表失败: ' + (e.response?.data?.error || e.message))
  }
}

async function createReport() {
  try {
    const { value: name } = await ElMessageBox.prompt('请输入报表名称', '新建报表', {
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    })
    await api.post('/reports', { name })
    ElMessage.success('报表创建成功')
    loadReports()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('创建失败: ' + (e.response?.data?.error || e.message))
    }
  }
}

async function viewReport(row) {
  selectedReport.value = row
  try {
    const res = await api.get(`/reports/${row.id}/items`)
    reportItems.value = res.data
  } catch (e) {
    ElMessage.error('加载报表项失败: ' + (e.response?.data?.error || e.message))
  }
}

async function publishReport(row) {
  try {
    await ElMessageBox.confirm('确定要发布该报表吗？发布后将锁定所有引用的运行版本。', '发布确认', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await api.post(`/reports/${row.id}/publish`)
    ElMessage.success('报表发布成功')
    loadReports()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('发布失败: ' + (e.response?.data?.error || e.message))
    }
  }
}

async function correctReport(row) {
  try {
    const { value: newRunId } = await ElMessageBox.prompt('请输入新的运行ID（用于更正）', '报表更正', {
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    })
    await api.post(`/reports/${row.id}/correct`, {
      metricKey: row.metricKey || 'net_sales',
      newRunId: parseInt(newRunId)
    })
    ElMessage.success('更正成功')
    loadReports()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('更正失败: ' + (e.response?.data?.error || e.message))
    }
  }
}

function getMetricValue(item) {
  // 简化：显示运行ID，实际应从运行结果中获取
  return `运行 ${item.runId}`
}

function formatTime(time) {
  if (!time) return '-'
  return new Date(time).toLocaleString('zh-CN')
}

function statusType(status) {
  const map = { draft: 'info', published: 'success', archived: 'warning' }
  return map[status] || 'info'
}

onMounted(() => {
  loadReports()
})
</script>

<style scoped>
.reports-view {
  padding: 20px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.report-detail {
  margin-top: 20px;
}
</style>
