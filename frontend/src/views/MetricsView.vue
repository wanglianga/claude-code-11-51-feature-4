<template>
  <div class="metrics-view">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>指标目录</span>
          <el-button type="primary" size="small" @click="createMetric">新建指标</el-button>
        </div>
      </template>
      <el-table :data="metrics" stripe style="width: 100%">
        <el-table-column prop="metricKey" label="指标键" width="180" />
        <el-table-column prop="name" label="名称" width="180" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="grain" label="粒度" width="100" />
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button size="small" @click="editMetric(row)">编辑</el-button>
            <el-button size="small" type="primary" @click="viewImpact(row)">影响</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建指标对话框 -->
    <el-dialog v-model="dialogVisible" title="新建指标" width="600px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="指标键">
          <el-input v-model="form.metricKey" placeholder="如 net_sales" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="如 净销售额" />
        </el-form-item>
        <el-form-item label="粒度">
          <el-select v-model="form.grain" placeholder="选择粒度">
            <el-option label="日" value="day" />
            <el-option label="月" value="month" />
          </el-select>
        </el-form-item>
        <el-form-item label="定义">
          <el-input v-model="form.definition" type="textarea" :rows="8" placeholder="指标定义 JSON" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveDraft">保存草稿</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api/client'

const router = useRouter()
const metrics = ref([])
const dialogVisible = ref(false)
const form = ref({
  metricKey: '',
  name: '',
  grain: 'day',
  definition: ''
})

async function loadMetrics() {
  try {
    const res = await api.get('/metrics')
    metrics.value = res.data
  } catch (e) {
    ElMessage.error('加载指标失败: ' + (e.response?.data?.error || e.message))
  }
}

function createMetric() {
  form.value = { metricKey: '', name: '', grain: 'day', definition: '' }
  dialogVisible.value = true
}

async function saveDraft() {
  try {
    await api.post('/metrics', {
      metricKey: form.value.metricKey,
      name: form.value.name,
      grain: form.value.grain,
      definition: form.value.definition
    })
    ElMessage.success('草稿保存成功')
    dialogVisible.value = false
    loadMetrics()
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.response?.data?.error || e.message))
  }
}

function editMetric(row) {
  router.push({ path: '/metrics/' + row.metricKey })
}

function viewImpact(row) {
  router.push({ path: '/impact', query: { metric: row.metricKey } })
}

function statusType(status) {
  const map = { draft: 'info', published: 'success', deprecated: 'warning', pending: 'warning', approved: 'success', rejected: 'danger' }
  return map[status] || 'info'
}

onMounted(() => {
  loadMetrics()
})
</script>

<style scoped>
.metrics-view {
  padding: 20px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
