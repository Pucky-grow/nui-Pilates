<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="6"><el-card><div class="l">今日销售额</div><div class="v">¥{{ d.todayRevenue || 0 }}</div></el-card></el-col>
      <el-col :span="6"><el-card><div class="l">今日预约</div><div class="v">{{ d.todayBookings || 0 }}</div></el-card></el-col>
      <el-col :span="6"><el-card><div class="l">今日上课</div><div class="v">{{ d.todayFinished || 0 }}</div></el-card></el-col>
      <el-col :span="6"><el-card><div class="l">今日新增会员</div><div class="v">{{ d.todayNewMembers || 0 }}</div></el-card></el-col>
    </el-row>

    <el-card style="margin-top:16px">
      <template #header>学员搜索</template>
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="手机号/昵称/会员编号" style="width:300px" clearable @keyup.enter="search" />
        <el-button type="primary" @click="search">搜索</el-button>
      </div>
      <el-table :data="members" style="margin-top:16px">
        <el-table-column prop="member.memberNo" label="会员编号" width="150" />
        <el-table-column prop="nickname" label="昵称" />
        <el-table-column prop="phone" label="手机号" width="130" />
        <el-table-column prop="levelName" label="等级" width="100" />
        <el-table-column prop="member.totalSessions" label="累计上课" width="90" />
      </el-table>
    </el-card>
  </div>
</template>
<script setup>
import { ref, reactive, onMounted } from 'vue';
import http from '../utils/request';

const d = reactive({});
const keyword = ref('');
const members = ref([]);

onMounted(async () => { Object.assign(d, await http.get('/admin/statistics/dashboard')); });
const search = async () => {
  const r = await http.get('/admin/members', { params: { page: 1, size: 20, keyword: keyword.value } });
  members.value = r.list;
};
</script>
<style scoped>
.l { color: #999; font-size: 13px; }
.v { font-size: 26px; font-weight: 700; color: #5F7057; margin-top: 8px; }
.toolbar { display: flex; gap: 12px; }
</style>
