<template>
  <div>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" />
    <el-button v-if="loadError" @click="load">重试</el-button>
    <el-card>
      <el-table v-loading="loading" :empty-text="loadError ? '加载失败' : '暂无数据'" :data="list">
        <el-table-column prop="orderNo" label="订单号" width="200" />
        <el-table-column prop="userId" label="用户ID" width="90" />
        <el-table-column v-if="canReadAmounts" prop="payAmount" label="实付" width="100" />
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column prop="createdAt" label="创建时间" />
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button v-if="canRefund && row.status=='PAID'" size="small" type="danger" @click="refund(row)">退款</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination :total="total" :page-size="10" v-model:current-page="page" @current-change="load" layout="total, prev, pager, next" style="margin-top:16px"/>
    </el-card>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import http from '../utils/request';

const roles=JSON.parse(localStorage.getItem('adminRoles')||'[]');
const permissions=JSON.parse(localStorage.getItem('adminPerms')||'[]');
const canReadAmounts=roles.includes('ADMIN')||roles.includes('STORE_MANAGER');
const canRefund=false; // Online refunds are closed for this FREE release; historical queries remain.
const list = ref([]); const total = ref(0); const page = ref(1);
onMounted(() => load());
const loading = ref(false);
const loadError = ref('');
const load = async () => {
  loading.value = true; loadError.value = '';
  try {
  const d = await http.get('/admin/orders', { params: { page: page.value, size: 10 } });
  list.value = d.list; total.value = d.total;

  } catch (error) { loadError.value = error?.message || '加载失败，请重试'; }
  finally { loading.value = false; }
};
const refund = async (row) => {
  const { value } = await ElMessageBox.prompt('退款原因', '确认退款');
  await http.post('/admin/orders/' + row.id + '/refund?reason=' + encodeURIComponent(value));
  ElMessage.success('退款成功'); load();
};
</script>
