<template>
  <div class="login">
    <el-card class="box">
      <h2>优仪普拉提 · 管理后台</h2>
      <el-form @submit.prevent>
        <el-form-item><el-input v-model="username" placeholder="账号" /></el-form-item>
        <el-form-item><el-input v-model="password" type="password" placeholder="密码" show-password /></el-form-item>
        <el-button type="primary" style="width:100%" @click="submit">登录</el-button>
      </el-form>
    </el-card>
  </div>
</template>
<script setup>
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '../store/auth';
const router = useRouter();
const auth = useAuthStore();
const username = ref('');
const password = ref('');
const submit = async () => { await auth.login(username.value, password.value); router.push('/dashboard'); };
</script>
<style scoped>
.login { height: 100vh; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #FAF7F2, #EFF3EC); }
.box { width: 380px; }
h2 { text-align: center; color: #5F7057; margin-bottom: 24px; }
</style>
