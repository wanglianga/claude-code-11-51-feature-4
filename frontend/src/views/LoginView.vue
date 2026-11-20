<template>
  <div class="login-container">
    <el-card class="login-card" shadow="always">
      <template #header>
        <div class="login-title">
          <el-icon size="24"><DataAnalysis /></el-icon>
          <span>经营指标语义层与自助分析平台</span>
        </div>
      </template>
      <el-form :model="form" :rules="rules" ref="formRef" label-width="80px" @submit.prevent="login">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" prefix-icon="User" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" placeholder="请输入密码" prefix-icon="Lock" show-password />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" style="width: 100%" :loading="loading" @click="login">登录</el-button>
        </el-form-item>
      </el-form>
      <div class="login-tip">
        <p>测试账号：</p>
        <p>admin / admin123（指标管理员）</p>
        <p>analyst / analyst123（分析员）</p>
        <p>manager / manager123（经营负责人）</p>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api/client'

const router = useRouter()
const formRef = ref()
const loading = ref(false)

const form = reactive({
  username: '',
  password: ''
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function login() {
  try {
    await formRef.value.validate()
    loading.value = true
    const res = await api.post('/auth/login', {
      username: form.username,
      password: form.password
    })
    localStorage.setItem('token', res.data.token)
    localStorage.setItem('username', res.data.username)
    localStorage.setItem('role', res.data.role)
    localStorage.setItem('displayName', res.data.displayName)
    ElMessage.success('登录成功')
    router.push('/')
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-container {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f3a5f 0%, #0c1e3a 100%);
}
.login-card {
  width: 420px;
}
.login-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 18px;
  font-weight: bold;
}
.login-tip {
  margin-top: 16px;
  padding: 12px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 12px;
  color: #606266;
}
.login-tip p {
  margin: 4px 0;
}
</style>
