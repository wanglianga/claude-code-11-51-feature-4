import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'

import LoginView from './views/LoginView.vue'
import MetricsView from './views/MetricsView.vue'
import MetricEditorView from './views/MetricEditorView.vue'
import RunsView from './views/RunsView.vue'
import ReportsView from './views/ReportsView.vue'
import ApprovalsView from './views/ApprovalsView.vue'
import SourcesView from './views/SourcesView.vue'
import ImpactView from './views/ImpactView.vue'
import DistributionView from './views/DistributionView.vue'

const routes = [
  { path: '/login', name: 'login', component: LoginView },
  { path: '/', name: 'home', component: () => import('./views/HomeView.vue') },
  { path: '/metrics', name: 'metrics', component: MetricsView },
  { path: '/metrics/:key', name: 'metric-editor', component: MetricEditorView },
  { path: '/runs', name: 'runs', component: RunsView },
  { path: '/distribution', name: 'distribution', component: DistributionView },
  { path: '/reports', name: 'reports', component: ReportsView },
  { path: '/approvals', name: 'approvals', component: ApprovalsView },
  { path: '/sources', name: 'sources', component: SourcesView },
  { path: '/impact', name: 'impact', component: ImpactView }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫：未登录跳转登录页
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')
  if (!token && to.path !== '/login') {
    next('/login')
  } else if (token && to.path === '/login') {
    next('/')
  } else {
    next()
  }
})

const app = createApp(App)
app.use(ElementPlus)
app.use(router)
app.mount('#app')
