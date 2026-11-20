<template>
  <div class="runs-view">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>运行结果</span>
          <el-button type="primary" size="small" @click="runMetric">运行指标</el-button>
        </div>
      </template>

      <!-- 运行列表 -->
      <el-table :data="runs" stripe style="width: 100%" @row-click="selectRun">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="metricKey" label="指标" width="150" />
        <el-table-column prop="grain" label="粒度" width="100" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="startedAt" label="开始时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.startedAt) }}
          </template>
        </el-table-column>
        <el-table-column prop="dataCutoffJson" label="数据截止点" width="200">
          <template #default="{ row }">
            {{ formatCutoff(row.dataCutoffJson) }}
          </template>
        </el-table-column>
      </el-table>

      <!-- 运行详情与单元格 -->
      <el-card v-if="selectedRun" class="run-detail" shadow="never">
        <template #header>
          <span>运行详情 (ID: {{ selectedRun.id }})</span>
        </template>
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="指标">{{ selectedRun.metricKey }}</el-descriptions-item>
          <el-descriptions-item label="粒度">{{ selectedRun.grain }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ selectedRun.status }}</el-descriptions-item>
          <el-descriptions-item label="数据截止点" :span="3">
            {{ formatCutoff(selectedRun.dataCutoffJson) }}
          </el-descriptions-item>
        </el-descriptions>

        <!-- 单元格表格 -->
        <el-table :data="cells" stripe size="small" style="margin-top: 16px">
          <el-table-column prop="periodKey" label="期间" width="120" />
          <el-table-column prop="dimsJson" label="维度" width="150">
            <template #default="{ row }">
              {{ formatDims(row.dimsJson) }}
            </template>
          </el-table-column>
          <el-table-column prop="valueNum" label="分子" width="120" />
          <el-table-column prop="valueDen" label="分母" width="120" />
          <el-table-column prop="value" label="值" width="120">
            <template #default="{ row }">
              {{ formatCellValue(row) }}
            </template>
          </el-table-column>
          <el-table-column prop="cellStatus" label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="cellStatusType(row.cellStatus)" size="small">{{ row.cellStatus }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="message" label="说明" />
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button size="small" @click="drillDown(row)">下钻</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- 下钻对话框 -->
      <el-dialog v-model="drillVisible" title="单元格下钻" width="800px">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="期间">{{ drillCell?.periodKey }}</el-descriptions-item>
          <el-descriptions-item label="维度">{{ formatDims(drillCell?.dimsJson) }}</el-descriptions-item>
          <el-descriptions-item label="值">{{ drillCell?.valueNum }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ drillCell?.cellStatus }}</el-descriptions-item>
        </el-descriptions>
        <el-divider>参与记录</el-divider>
        <el-table :data="lineage" stripe size="small">
          <el-table-column prop="recordType" label="类型" width="100" />
          <el-table-column prop="recordId" label="记录ID" width="150" />
          <el-table-column prop="contribution" label="贡献值" width="120" />
          <el-table-column prop="included" label="是否计入" width="100">
            <template #default="{ row }">
              <el-tag :type="row.included ? 'success' : 'info'" size="small">
                {{ row.included ? '计入' : '排除' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="reason" label="原因" />
        </el-table>
        <el-divider>最终算式</el-divider>
        <div class="formula">{{ drillCell?.message || drillCell?.trace || '无算式信息' }}</div>
      </el-dialog>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api/client'

const runs = ref([])
const selectedRun = ref(null)
const cells = ref([])
const drillVisible = ref(false)
const drillCell = ref(null)
const lineage = ref([])

async function loadRuns() {
  try {
    const res = await api.get('/runs')
    runs.value = res.data
  } catch (e) {
    ElMessage.error('加载运行失败: ' + (e.response?.data?.error || e.message))
  }
}

async function selectRun(row) {
  selectedRun.value = row
  try {
    const res = await api.get(`/runs/${row.id}/cells`)
    cells.value = res.data
  } catch (e) {
    ElMessage.error('加载单元格失败: ' + (e.response?.data?.error || e.message))
  }
}

async function drillDown(cell) {
  drillCell.value = cell
  drillVisible.value = true
  try {
    const cellKey = cell.periodKey + '|' + (typeof cell.dimsJson === 'string' ? cell.dimsJson : JSON.stringify(cell.dimsJson))
    const res = await api.get(`/runs/${selectedRun.value.id}/lineage`, {
      params: { cellKey }
    })
    lineage.value = res.data
  } catch (e) {
    ElMessage.error('加载血缘失败: ' + (e.response?.data?.error || e.message))
  }
}

function runMetric() {
  // 简单运行：选择第一个指标运行
  ElMessage.info('请选择指标后运行')
}

function formatTime(time) {
  if (!time) return '-'
  return new Date(time).toLocaleString('zh-CN')
}

function formatCutoff(cutoffJson) {
  if (!cutoffJson) return '-'
  try {
    const obj = typeof cutoffJson === 'string' ? JSON.parse(cutoffJson) : cutoffJson
    return obj.note || JSON.stringify(obj)
  } catch {
    return cutoffJson
  }
}

function formatDims(dimsJson) {
  if (!dimsJson) return '-'
  if (typeof dimsJson === 'string') return dimsJson
  return JSON.stringify(dimsJson)
}

function formatCellValue(cell) {
  if (cell.valueNum === null || cell.valueNum === undefined) return '-'
  if (cell.valueDen !== null && cell.valueDen !== undefined && cell.valueDen !== 0) {
    return (cell.valueNum / cell.valueDen).toFixed(4)
  }
  return cell.valueNum
}

function cellStatusType(status) {
  const map = { ok: 'success', not_covered: 'warning', div_zero: 'danger', no_data: 'info', partial: 'warning', excluded: 'info' }
  return map[status] || 'info'
}

function statusType(status) {
  const map = { success: 'success', failed: 'danger', partial: 'warning' }
  return map[status] || 'info'
}

onMounted(() => {
  loadRuns()
})
</script>

<style scoped>
.runs-view {
  padding: 20px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.run-detail {
  margin-top: 20px;
}
.formula {
  padding: 12px;
  background: #f5f7fa;
  border-radius: 4px;
  font-family: monospace;
  font-size: 12px;
}
</style>
