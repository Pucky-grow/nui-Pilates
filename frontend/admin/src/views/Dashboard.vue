<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="6" v-for="c in cards" :key="c.label">
        <el-card><div class="metric-label">{{ c.label }}</div><div class="metric-value">{{ c.value }}</div></el-card>
      </el-col>
    </el-row>
    <el-card v-if="canReadStatistics" style="margin-top:16px">
      <template #header>近14日营业额趋势</template>
      <div ref="chart" style="height:320px"></div>
    </el-card>
    <el-row v-if="canReadStatistics" :gutter="16" style="margin-top:16px">
      <el-col :span="12"><el-card><template #header>热门课程</template>
        <el-table :data="topCourses"><el-table-column prop="courseName" label="课程"/><el-table-column prop="count" label="预约数"/></el-table>
      </el-card></el-col>
    </el-row>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue';
import * as echarts from 'echarts';
import http from '../utils/request';
import { hasAdminPermission } from '../utils/adminSession';

const canReadStatistics = hasAdminPermission('statistics:view');
const cards = ref([]);
const topCourses = ref([]);
const chart = ref(null);

onMounted(async () => {
  const d = await http.get('/admin/statistics/dashboard');
  cards.value = [
    ...(d.todayRevenue!==undefined?[{ label: '今日营业额', value: '¥' + d.todayRevenue }]:[]),
    { label: '今日预约', value: d.todayBookings },
    { label: '今日上课', value: d.todayFinished },
    { label: '今日新增会员', value: d.todayNewMembers },
    ...(d.monthRevenue!==undefined?[{ label: '本月营业额', value: '¥' + d.monthRevenue }]:[]),
    { label: '本月新增会员', value: d.monthNewMembers },
    { label: '本月预约', value: d.monthBookings },
    { label: '总会员', value: d.totalMembers }
  ];
  if (!canReadStatistics) return;
  topCourses.value = await http.get('/admin/statistics/top-courses?limit=10');
  const t = await http.get('/admin/statistics/revenue-trend?days=14');
  const c = echarts.init(chart.value);
  c.setOption({
    xAxis: { type: 'category', data: t.days },
    yAxis: { type: 'value' },
    series: [{ type: 'line', smooth: true, data: t.values, areaStyle: { opacity: 0.2 } }]
  });
});
</script>
<style scoped>
.metric-label { color: #999; font-size: 13px; }
.metric-value { font-size: 26px; font-weight: 700; color: #5F7057; margin-top: 8px; }
</style>
