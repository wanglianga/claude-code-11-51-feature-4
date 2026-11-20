<template>
  <el-container class="app-container">
    <el-aside width="220px" class="sidebar">
      <div class="logo">
        <el-icon><DataAnalysis /></el-icon>
        <span>指标语义平台</span>
      </div>
      <el-menu :default-active="activeMenu" class="menu" router>
        <el-menu-item index="/">
          <el-icon><HomeFilled /></el-icon>
          <span>首页</span>
        </el-menu-item>
        <el-menu-item index="/metrics">
          <el-icon><Document /></el-icon>
          <span>指标目录</span>
        </el-menu-item>
        <el-menu-item index="/runs">
          <el-icon><VideoPlay /></el-icon>
          <span>运行结果</span>
        </el-menu-item>
        <el-menu-item index="/distribution">
          <el-icon><Histogram /></el-icon>
          <span>分布查询</span>
        </el-menu-item>
        <el-menu-item index="/reports">
          <el-icon><Grid /></el-icon>
          <span>报表中心</span>
        </el-menu-item>
        <el-menu-item index="/approvals">
          <el-icon><Stamp /></el-icon>
          <span>审批中心</span>
        </el-menu-item>
        <el-menu-item index="/sources">
          <el-icon><FolderOpened /></el-icon>
          <span>数据源</span>
        </el-menu-item>
        <el-menu-item index="/impact">
          <el-icon><Share /></el-icon>
          <span>影响分析</span>
        </el-menu-item>
      </el-menu>
      <div class="user-info">
        <el-avatar :size="32" icon="UserFilled" />
        <div class="user-text">
          <div class="username">{{ displayName }}</div>
          <div class="role">{{ roleText }}</div>
        </div>
      </div>
    </el-aside>
    <el-container>
      <el-header class="header" v-if="showHeader">
        <div class="breadcrumb">
          <el-breadcrumb separator="/">
            <el-breadcrumb-item v-for="item in breadcrumbs" :key="item.path" :to="item.path">
              {{ item.title }}
            </el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-actions">
          <el-button v-if="!isLoginPage" type="text" @click="logout">退出登录</el-button>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { DataAnalysis, HomeFilled, Document, VideoPlay, Grid, Stamp, FolderOpened, Share, UserFilled, Histogram } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()

const isLoginPage = computed(() => route.path === '/login')
const showHeader = computed(() => route.path !== '/login')

const breadcrumbs = computed(() => {
  const map = {
    '/': '首页',
    '/metrics': '指标目录',
    '/runs': '运行结果',
    '/distribution': '分布查询',
    '/reports': '报表中心',
    '/approvals': '审批中心',
    '/sources': '数据源',
    '/impact': '影响分析'
  }
  const items = []
  if (route.path !== '/') {
    items.push({ path: '/', title: '首页' })
  }
  items.push({ path: route.path, title: map[route.path] || '页面' })
  return items
})

const displayName = computed(() => {
  const name = localStorage.getItem('displayName')
  return name || '用户'
})

const roleText = computed(() => {
  const role = localStorage.getItem('role')
  return role === 'ADMIN' ? '指标管理员' : role === 'ANALYST' ? '分析员' : role === 'MANAGER' ? '经营负责人' : '访客'
})

function logout() {
  localStorage.removeItem('token')
  localStorage.removeItem('displayName')
  localStorage.removeItem('role')
  router.push('/login')
}
</script>

<style scoped>
.app-container {
  height: 100vh;
}
.sidebar {
  background: #001529;
  color: #fff;
}
.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 18px;
  font-weight: bold;
  border-bottom: 1px solid #1f3a5f;
}
.menu {
  border-right: none;
  background: #001529;
}
.menu :deep(.el-menu-item) {
  color: #a6adb4;
}
.menu :deep(.el-menu-item:hover) {
  background: #1f3a5f;
  color: #fff;
}
.menu :deep(.el-menu-item.is-active) {
  background: #1f3a5f;
  color: #fff;
}
.user-info {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  border-top: 1px solid #1f3a5f;
}
.user-text {
  flex: 1;
}
.username {
  color: #fff;
  font-size: 14px;
}
.role {
  color: #a6adb4;
  font-size: 12px;
}
.header {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
}
.breadcrumb {
  flex: 1;
}
.header-actions {
  display: flex;
  align-items: center;
}
.main {
  background: #f0f2f5;
  padding: 20px;
}
</style>
