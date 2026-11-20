<template>
  <div class="metric-editor">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>指标编辑</span>
          <div>
            <el-button size="small" @click="validate">校验</el-button>
            <el-button size="small" type="primary" @click="saveDraft">保存草稿</el-button>
            <el-button size="small" type="success" @click="publish">发布</el-button>
          </div>
        </div>
      </template>

      <!-- 基础信息 -->
      <el-form :model="form" label-width="100px" class="metric-form">
        <el-row :gutter="20">
          <el-col :span="8">
            <el-form-item label="指标键">
              <el-input v-model="form.metricKey" :disabled="!!metricKey" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="名称">
              <el-input v-model="form.name" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="粒度">
              <el-select v-model="form.grain">
                <el-option label="日" value="day" />
                <el-option label="月" value="month" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="定义">
          <el-input v-model="form.definition" type="textarea" :rows="12" placeholder="指标定义 JSON" />
        </el-form-item>
      </el-form>

      <!-- 校验结果 -->
      <el-alert v-if="validation.ok" type="success" :closable="false" show-icon>
        <template #title>
          校验通过
          <span v-if="validation.inferredType">，推断类型: {{ validation.inferredType }}</span>
          <span v-if="validation.refs && validation.refs.length">，引用: {{ validation.refs.join(', ') }}</span>
        </template>
      </el-alert>
      <el-alert v-else-if="validation.errors && validation.errors.length" type="error" :closable="false" show-icon>
        <template #title>校验失败</template>
        <div v-for="(err, idx) in validation.errors" :key="idx" class="error-item">
          {{ err.path }}: {{ err.message }}
        </div>
      </el-alert>

      <!-- 预览结果 -->
      <el-card v-if="previewResult" class="preview-card" shadow="never">
        <template #header>
          <span>预览结果</span>
        </template>
        <el-table :data="previewResult" stripe size="small">
          <el-table-column prop="periodKey" label="期间" width="120" />
          <el-table-column prop="dims" label="维度" width="150">
            <template #default="{ row }">
              {{ formatDims(row.dims) }}
            </template>
          </el-table-column>
          <el-table-column prop="value" label="值" width="150">
            <template #default="{ row }">
              {{ formatValue(row) }}
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="message" label="说明" />
        </el-table>
      </el-card>

      <!-- 影响预览 -->
      <el-card v-if="impact" class="impact-card" shadow="never">
        <template #header>
          <span>影响预览</span>
        </template>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="下游指标">
            {{ impact.downstreamMetrics && impact.downstreamMetrics.length ? impact.downstreamMetrics.join(', ') : '无' }}
          </el-descriptions-item>
          <el-descriptions-item label="受影响报表">
            {{ impact.affectedReports && impact.affectedReports.length ? impact.affectedReports.length + ' 个' : '无' }}
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <!-- 版本历史 -->
      <el-card class="versions-card" shadow="never">
        <template #header>
          <span>版本历史</span>
        </template>
        <el-table :data="versions" stripe size="small">
          <el-table-column prop="version" label="版本" width="80" />
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="创建时间" width="180">
            <template #default="{ row }">
              {{ formatTime(row.createdAt) }}
            </template>
          </el-table-column>
          <el-table-column prop="publishedAt" label="发布时间" width="180">
            <template #default="{ row }">
              {{ row.publishedAt ? formatTime(row.publishedAt) : '-' }}
            </template>
          </el-table-column>
          <el-table-column label="操作">
            <template #default="{ row }">
              <el-button size="small" @click="loadVersion(row)">查看</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api/client'

const route = useRoute()
const metricKey = ref(route.params.key || null)
const form = ref({
  metricKey: '',
  name: '',
  grain: 'day',
  definition: ''
})
const validation = ref({ ok: false, errors: [] })
const versions = ref([])
const previewResult = ref(null)
const impact = ref(null)

// 加载指标
async function loadMetric() {
  if (!metricKey.value) return
  try {
    const res = await api.get(`/metrics/${metricKey.value}/versions`)
    versions.value = res.data
    if (res.data.length > 0) {
      const latest = res.data[0]
      form.value = {
        metricKey: latest.metricKey,
        name: latest.name || '',
        grain: latest.grain || 'day',
        definition: latest.definitionJson
      }
    }
  } catch (e) {
    ElMessage.error('加载指标失败: ' + (e.response?.data?.error || e.message))
  }
}

// 校验
async function validate() {
  try {
    const res = await api.post('/metrics/validate', {
      metricKey: form.value.metricKey,
      definition: form.value.definition,
      grain: form.value.grain
    })
    validation.value = res.data
    if (res.data.ok) {
      ElMessage.success('校验通过')
    } else {
      ElMessage.warning('校验失败，请查看错误信息')
    }
  } catch (e) {
    ElMessage.error('校验失败: ' + (e.response?.data?.error || e.message))
  }
}

// 保存草稿
async function saveDraft() {
  try {
    await api.post('/metrics', {
      metricKey: form.value.metricKey,
      name: form.value.name,
      grain: form.value.grain,
      definition: form.value.definition
    })
    ElMessage.success('草稿保存成功')
    loadMetric()
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.response?.data?.error || e.message))
  }
}

// 发布
async function publish() {
  try {
    await ElMessageBox.confirm('确定要发布该指标版本吗？发布后旧版本将下线。', '发布确认', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    const res = await api.post(`/metrics/${form.value.metricKey}/publish`, {
      version: form.value.version || 1
    })
    ElMessage.success('发布成功')
    loadMetric()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error('发布失败: ' + (e.response?.data?.error || e.message))
    }
  }
}

// 预览
async function preview() {
  try {
    const res = await api.post('/runs', {
      metricKey: form.value.metricKey,
      metricVersionId: form.value.versionId || 1,
      from: '2026-01-01',
      to: '2026-03-31',
      grain: form.value.grain
    })
    previewResult.value = res.data.cells || []
    ElMessage.success('预览完成')
  } catch (e) {
    ElMessage.error('预览失败: ' + (e.response?.data?.error || e.message))
  }
}

// 影响分析
async function loadImpact() {
  try {
    const res = await api.get(`/metrics/${form.value.metricKey}/impact`)
    impact.value = res.data
  } catch (e) {
    ElMessage.error('加载影响分析失败: ' + (e.response?.data?.error || e.message))
  }
}

// 加载版本
function loadVersion(row) {
  form.value.definition = row.definitionJson
}

// 格式化
function formatDims(dims) {
  if (!dims) return '-'
  if (typeof dims === 'string') return dims
  return JSON.stringify(dims)
}

function formatValue(row) {
  if (row.valueNum === null || row.valueNum === undefined) return '-'
  if (row.valueDen !== null && row.valueDen !== undefined && row.valueDen !== 0) {
    return (row.valueNum / row.valueDen).toFixed(4)
  }
  return row.valueNum
}

function statusTagType(status) {
  const map = { ok: 'success', not_covered: 'warning', div_zero: 'danger', no_data: 'info', partial: 'warning', excluded: 'info' }
  return map[status] || 'info'
}

function formatTime(time) {
  if (!time) return '-'
  return new Date(time).toLocaleString('zh-CN')
}

onMounted(() => {
  loadMetric()
  loadImpact()
})
</script>

<style scoped>
.metric-editor {
  padding: 20px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.metric-form {
  margin-bottom: 20px;
}
.preview-card,
.impact-card,
.versions-card {
  margin-top: 20px;
}
.error-item {
  margin: 4px 0;
  font-size: 12px;
}
</style>
