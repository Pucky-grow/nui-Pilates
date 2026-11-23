<template>
  <div>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" />
    <el-button v-if="loadError" @click="load">重试</el-button>
    <el-card>
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="会员/手机号/昵称" style="width:220px" clearable />
        <el-button type="primary" @click="load">查询</el-button>
      </div>
      <el-table v-loading="loading" :empty-text="loadError ? '加载失败' : '暂无数据'" :data="list" style="margin-top:16px">
        <el-table-column prop="member.memberNo" label="会员编号" width="150" />
        <el-table-column prop="nickname" label="昵称" />
        <el-table-column prop="phone" label="手机号" width="130" />
        <el-table-column prop="levelName" label="等级" width="100" />
        <el-table-column prop="member.totalSessions" label="累计上课" width="90" />
        <el-table-column prop="member.totalConsumption" label="累计消费" width="100" />
        <el-table-column prop="member.validUntil" label="有效期" width="110" />
        <el-table-column label="操作" width="120">
          <template #default="{ row }"><el-button size="small" @click="openDetail(row)">详情</el-button><el-button v-if="studioLedger" link @click="router.push({path:'/studio-ledger',query:{memberId:row.member.id}})">账本</el-button></template>
        </el-table-column>
      </el-table>
      <el-pagination :total="total" :page-size="10" v-model:current-page="page" @current-change="load" layout="total, prev, pager, next" style="margin-top:16px"/>
    </el-card>

    <el-dialog v-model="dialog" title="会员详情" width="520px">
      <el-form label-width="90px">
        <el-form-item label="等级"><el-select v-model="detail.levelId"><el-option v-for="l in detail.levels" :key="l.id" :label="l.name" :value="l.id"/></el-select></el-form-item>
        <el-form-item label="有效期"><el-date-picker v-model="detail.validUntil" type="date" value-format="YYYY-MM-DD" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" @click="saveDetail">保存</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import { useRouter } from 'vue-router';
import { useAuthStore } from '../store/auth';
import http from '../utils/request';

const list = ref([]); const total = ref(0); const page = ref(1); const keyword = ref('');
const dialog = ref(false); const detail = ref({ levels: [] });

const router = useRouter(), auth = useAuthStore();
const studioLedger = ref(false);
onMounted(() => {
  load();
  if (auth.isAdmin()) http.get('/api/capabilities').then(c => { studioLedger.value = c.studioLedger === true; }).catch(() => {});
});
const loading = ref(false);
const loadError = ref('');
const load = async () => {
  loading.value = true; loadError.value = '';
  try {
  const d = await http.get('/admin/members', { params: { page: page.value, size: 10, keyword: keyword.value } });
  list.value = d.list; total.value = d.total;

  } catch (error) { loadError.value = error?.message || '加载失败，请重试'; }
  finally { loading.value = false; }
};
const openDetail = async (row) => {
  const d = await http.get('/admin/members/' + row.member.userId);
  detail.value = { ...d, userId: row.member.userId, validUntil: d.member.validUntil, levelId: d.member.levelId };
  dialog.value = true;
};
const saveDetail = async () => {
  await http.put('/admin/members/' + detail.value.userId, { levelId: detail.value.levelId, validUntil: detail.value.validUntil });
  ElMessage.success('已保存'); dialog.value = false; load();
};
</script>
<style scoped>.toolbar { display: flex; gap: 12px; }</style>
