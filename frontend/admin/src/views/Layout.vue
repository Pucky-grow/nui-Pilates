<template>
  <el-container style="height:100vh">
    <el-aside width="200px" class="aside">
      <div class="brand">优仪普拉提</div>
      <el-menu :default-active="$route.path" router background-color="#4A423A" text-color="#CBBFAF" active-text-color="#fff">
        <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">{{ m.label }}</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span>{{ $route.meta.title }}</span>
        <el-dropdown @command="logout">
          <span class="user">{{ auth.name }} ▾</span>
          <template #dropdown><el-dropdown-menu><el-dropdown-item command="logout">退出登录</el-dropdown-item></el-dropdown-menu></template>
        </el-dropdown>
      </el-header>
      <el-main><router-view /></el-main>
    </el-container>
  </el-container>
</template>
<script setup>
import { ref, computed, onMounted } from 'vue';
import http from '../utils/request';
import { useRouter } from 'vue-router';
import { useAuthStore } from '../store/auth';
const router = useRouter();
const auth = useAuthStore();

// 管理员: 全菜单; 店长: 业务菜单(无系统设置); 前台: 会员/订单/看板; 教练: 仅看板
const MENUS = {
  ADMIN: [
    { path: '/dashboard', label: '📊 数据看板' },
    { path: '/courses', label: '课程管理' },
    { path: '/course-proposals', label: '课程申请审核' },
    { path: '/schedules', label: '排课管理' },
    { path: '/coaches', label: '教练管理' },
    { path: '/members', label: '会员管理' },
    { path: '/packages', label: '课包管理' },
    { path: '/orders', label: '订单管理' },
    { path: '/coupons', label: '优惠券' },
    { path: '/articles', label: '内容管理' },
    { path: '/feedback', label: '意见反馈' },
    { path: '/system', label: '系统设置' }
  ],
  STORE_MANAGER: [
    { path: '/dashboard', label: '📊 数据看板' },
    { path: '/courses', label: '课程管理' },
    { path: '/course-proposals', label: '课程申请审核' },
    { path: '/schedules', label: '排课管理' },
    { path: '/coaches', label: '教练管理' },
    { path: '/members', label: '会员管理' },
    { path: '/packages', label: '课包管理' },
    { path: '/orders', label: '订单管理' },
    { path: '/coupons', label: '优惠券' },
    { path: '/articles', label: '内容管理' },
    { path: '/feedback', label: '意见反馈' }
  ],
  STAFF: [
    { path: '/staff-dashboard', label: '📊 前台工作台' },
    { path: '/members', label: '会员管理' },
    { path: '/orders', label: '订单查询' }
  ],
  COACH: [
    { path: '/dashboard', label: '📊 数据看板' }
  ]
};

const studioLedger = ref(false), lateFees = ref(false);
onMounted(async () => {
  try {
    const c = await http.get('/api/capabilities');
    studioLedger.value = auth.isAdmin() && c.studioLedger === true;
    if (c.studioLedger) lateFees.value = await http.get('/api/studio/late-fees/access');
  } catch (_) { /* keep workspace usable */ }
});
const menus = computed(() => [
  ...(MENUS[auth.role()] || MENUS.COACH),
  ...(studioLedger.value ? [{ path: '/studio-ledger', label: '会员账本' }] : []),
  ...(lateFees.value ? [{ path: '/late-fees', label: '临时取消费' }] : []),
  { path: '/accounts', label: '账号与凭据' }
]);
const logout = () => { auth.logout(); router.push('/login'); };
</script>
<style scoped>
.aside { background: #4A423A; }
.brand { color: #fff; font-weight: 700; padding: 20px; font-size: 18px; }
.header { background: #fff; display: flex; align-items: center; justify-content: space-between; box-shadow: 0 1px 4px rgba(0,0,0,0.08); }
.user { cursor: pointer; }
</style>
