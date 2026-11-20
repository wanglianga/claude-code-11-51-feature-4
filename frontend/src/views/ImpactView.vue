<template>
  <div class="impact-view">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>影响分析</span>
          <el-button size="small" @click="loadImpact">分析</el-button>
        </div>
      </template>

      <!-- 指标选择 -->
      <el-form :model="form" label-width="100px" class="impact-form">
        <el-form-item label="指标">
          <el-select v-model="form.metricKey" placeholder="选择指标" @change="loadImpact">
            <el-option v-for="m in metrics" :key="m.metricKey" :label="m.name" :value="m.metricKey" />
          </el-select>
        </el-form-item>
      </el-form>

      <!-- 影响结果 -->
      <el-card v-if="impact" class="impact-result" shadow="never">
        <template #header>
          <span>影响范围</span>
        </template>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="下游指标（依赖此指标）">
            <el-tag v-for="m in impact.downstreamMetrics" :key="m" size="small" style="margin-right: 8px">
              {{ m }}
            </el-tag>
            <span v-if="!impact.downstreamMetrics || impact.downstreamMetrics.length === 0">无</span>
          </el-descriptions-item>
          <el-descriptions-item label="上游指标（此指标依赖）">
            <el-tag v-for="m in impact.upstreamMetrics" :key="m" size="small" type="info" style="margin-right: 8px">
              {{ m }}
            </el-tag>
            <span v-if="!impact.upstreamMetrics || impact.upstreamMetrics.length === 0">无</span>
          </el-descriptions-item>
        </el-descriptions>
      </el-card>

      <!-- 依赖图 -->
      <el-card v-if="impact" class="graph-card" shadow="never">
        <template #header>
          <span>依赖关系图</span>
        </template>
        <div class="graph-container">
          <div class="graph-node current">{{ currentMetric }}</div>
          <div class="graph-section">
            <div class="graph-title">上游（依赖）</div>
            <div class="graph-nodes">
              <div v-for="m in impact.upstreamMetrics" :key="m" class="graph-node upstream">
                {{ m }}
              </div>
            </div>
          </div>
          <div class="graph-section">
            <div class="graph-title">下游（被依赖）</div>
            <div class="graph-nodes">
              <div v-for="m in impact.downstreamMetrics" :key="m" class="graph-node downstream">
                {{ m }}
              </div>
            </div>
          </div>
        </div>
      </el-card>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api/client'

const metrics = ref([])
const form = ref({
  metricKey: ''
})
const impact = ref(null)
const currentMetric = ref('')

async function loadMetrics() {
  try {
    const res = await api.get('/metrics')
    metrics.value = res.data
  } catch (e) {
    ElMessage.error('加载指标失败: ' + (e.response?.data?.error || e.message))
  }
}

async function loadImpact() {
  if (!form.value.metricKey) {
    ElMessage.warning('请选择指标')
    return
  }
  try {
    const res = await api.get(`/metrics/${form.value.metricKey}/impact`)
    impact.value = res.data
    currentMetric.value = form.value.metricKey
  } catch (e) {
    ElMessage.error('加载影响分析失败: ' + (e.response?.data?.error || e.message))
  }
}

onMounted(() => {
  loadMetrics()
})
</script>

<style scoped>
.impact-view {
  padding: 20px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.impact-form {
  max-width: 500px;
}
.impact-result {
  margin-top: 20px;
}
.graph-card {
  margin-top: 20px;
}
.graph-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 20px;
}
.graph-node {
  padding: 12px 24px;
  border-radius: 4px;
  background: #409eff;
  color: #fff;
  font-weight: bold;
}
.graph-node.upstream {
  background: #67c23a;
}
.graph-node.downstream {
  background: #e6a23c;
}
.graph-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}
.graph-title {
  font-size: 14px;
  color: #606266;
  font-weight: bold;
}
.graph-nodes {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  justify-content: center;
}
.graph-node {
  padding: 8px 16px;
  border-radius: 4px;
  font-size: 12px;
}
</style>
