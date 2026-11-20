<template>
  <div class="home-view">
    <el-row :gutter="20">
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-card">
            <div class="stat-value">{{ stats.metrics }}</div>
            <div class="stat-label">已发布指标</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-card">
            <div class="stat-value">{{ stats.runs }}</div>
            <div class="stat-label">运行次数</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-card">
            <div class="stat-value">{{ stats.reports }}</div>
            <div class="stat-label">已发布报表</div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-card">
            <div class="stat-value">{{ stats.pendingApprovals }}</div>
            <div class="stat-label">待审批</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="12">
        <el-card>
          <template #header>
            <span>快速开始</span>
          </template>
          <el-steps :active="3" align-center>
            <el-step title="定义指标" description="用 DSL 定义指标口径" />
            <el-step title="校验发布" description="校验类型/币种/粒度" />
            <el-step title="运行分析" description="自助运行与下钻" />
            <el-step title="发布报表" description="锁定版本供经营会引用" />
          </el-steps>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header>
            <span>平台能力</span>
          </template>
          <ul class="feature-list">
            <li>✓ 集中语义定义，每次运行固定引用版本</li>
            <li>✓ 引用环路、币种、粒度自动校验</li>
            <li>✓ 数据截止点与覆盖范围标记</li>
            <li>✓ 同店队列自动确定与排除说明</li>
            <li>✓ 迟到退款影响计算与报表更正</li>
            <li>✓ 单元格级血缘下钻</li>
          </ul>
        </el-card>
      </el-col>
    </el-row>

    <el-card style="margin-top: 20px">
      <template #header>
        <span>预置指标</span>
      </template>
      <el-table :data="metrics" stripe size="small">
        <el-table-column prop="metricKey" label="指标键" width="180" />
        <el-table-column prop="name" label="名称" width="180" />
        <el-table-column prop="grain" label="粒度" width="100" />
        <el-table-column prop="description" label="说明" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import api from '../api/client'

const stats = ref({
  metrics: 0,
  runs: 0,
  reports: 0,
  pendingApprovals: 0
})
const metrics = ref([])

async function loadStats() {
  try {
    const [metricsRes, runsRes, reportsRes, approvalsRes] = await Promise.all([
      api.get('/metrics'),
      api.get('/runs'),
      api.get('/reports'),
      api.get('/approvals?status=pending')
    ])
    stats.value = {
      metrics: metricsRes.data.filter(m => m.status === 'published').length,
      runs: runsRes.data.length,
      reports: reportsRes.data.filter(r => r.status === 'published').length,
      pendingApprovals: approvalsRes.data.length
    }
    metrics.value = metricsRes.data
  } catch (e) {
    console.error('加载统计失败', e)
  }
}

onMounted(() => {
  loadStats()
})
</script>

<style scoped>
.home-view {
  padding: 20px;
}
.stat-card {
  text-align: center;
  padding: 20px 0;
}
.stat-value {
  font-size: 36px;
  font-weight: bold;
  color: #409eff;
}
.stat-label {
  font-size: 14px;
  color: #606266;
  margin-top: 8px;
}
.feature-list {
  list-style: none;
  padding: 0;
}
.feature-list li {
  padding: 8px 0;
  border-bottom: 1px solid #e4e7ed;
}
.feature-list li:last-child {
  border-bottom: none;
}
</style>
