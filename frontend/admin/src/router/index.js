import { createRouter, createWebHistory } from 'vue-router';

const routes = [
  { path: '/login', component: () => import('../views/Login.vue') },
  {
    path: '/',
    component: () => import('../views/Layout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', component: () => import('../views/Dashboard.vue'), meta: { title: '数据看板' } },
      { path: 'staff-dashboard', component: () => import('../views/StaffDashboard.vue'), meta: { title: '前台工作台' } },
      { path: 'feedback', component: () => import('../views/Feedback.vue'), meta: { title: '意见反馈' } },
      { path: 'courses', component: () => import('../views/Courses.vue'), meta: { title: '课程管理' } },
      { path: 'course-proposals', component: () => import('../views/CourseProposals.vue'), meta: { title: '课程申请审核' } },
      { path: 'schedules', component: () => import('../views/Schedules.vue'), meta: { title: '排课管理' } },
      { path: 'coaches', component: () => import('../views/Coaches.vue'), meta: { title: '教练管理' } },
      { path: 'studio-ledger', component: () => import('../views/StudioLedger.vue'), meta: { title: '会员账本' } },
      { path: 'late-fees', component: () => import('../views/LateFees.vue'), meta: { title: '临时取消费' } },
      { path: 'members', component: () => import('../views/Members.vue'), meta: { title: '会员管理' } },
      { path: 'packages', component: () => import('../views/Packages.vue'), meta: { title: '课包管理' } },
      { path: 'orders', component: () => import('../views/Orders.vue'), meta: { title: '订单管理' } },
      { path: 'coupons', component: () => import('../views/Coupons.vue'), meta: { title: '优惠券' } },
      { path: 'articles', component: () => import('../views/Articles.vue'), meta: { title: '内容管理' } },
      { path: 'accounts', component: () => import('../views/Accounts.vue'), meta: { title: '账号与凭据' } },
      { path: 'system', component: () => import('../views/System.vue'), meta: { title: '系统设置' } }
    ]
  }
];

const router = createRouter({ history: createWebHistory(), routes });

router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('adminToken');
  if (to.path !== '/login' && !token) next('/login');
  else next();
});

export default router;
