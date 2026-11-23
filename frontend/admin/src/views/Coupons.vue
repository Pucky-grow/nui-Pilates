<template>
  <div>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" />
    <el-button v-if="loadError" @click="load">重试</el-button>
    <el-card>
      <el-button type="primary" @click="openForm(null)">新增优惠券</el-button>
      <el-table v-loading="loading" :empty-text="loadError ? '加载失败' : '暂无数据'" :data="list" style="margin-top:16px">
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="type" label="类型" width="120" />
        <el-table-column prop="discountAmount" label="金额" width="90" />
        <el-table-column prop="minAmount" label="门槛" width="90" />
        <el-table-column prop="issuedCount" label="已发/总量" width="100">
          <template #default="{ row }">{{ row.issuedCount }}/{{ row.totalCount || '不限' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }"><el-button size="small" @click="openForm(row)">编辑</el-button></template>
        </el-table-column>
      </el-table>
    </el-card>
    <el-dialog v-model="dialog" :title="form.id?'编辑优惠券':'新增优惠券'" width="480px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="类型"><el-select v-model="form.type"><el-option label="新人券" value="NEW_USER"/><el-option label="满减券" value="FULL_REDUCE"/><el-option label="折扣券" value="DISCOUNT"/><el-option label="私教券" value="PRIVATE"/></el-select></el-form-item>
        <el-form-item label="优惠金额"><el-input-number v-model="form.discountAmount" :min="0" :precision="2" /></el-form-item>
        <el-form-item label="最低消费"><el-input-number v-model="form.minAmount" :min="0" :precision="2" /></el-form-item>
        <el-form-item label="发放总量"><el-input-number v-model="form.totalCount" :min="0" /></el-form-item>
        <el-form-item label="有效期至"><el-date-picker v-model="form.validUntil" type="datetime" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import http from '../utils/request';
const list = ref([]); const total = ref(0); const page = ref(1); const dialog = ref(false); const form = ref({});
onMounted(() => load());
const loading = ref(false);
const loadError = ref('');
const load = async () => {
  loading.value = true; loadError.value = '';
  try { const d = await http.get('/admin/coupons', { params: { page: page.value, size: 10 } }); list.value = d.list; total.value = d.total; 
  } catch (error) { loadError.value = error?.message || '加载失败，请重试'; }
  finally { loading.value = false; }
};
const openForm = (row) => { form.value = row ? { ...row } : { type: 'FULL_REDUCE', minAmount: 0, totalCount: 100 }; dialog.value = true; };
const save = async () => { if (form.value.id) await http.put('/admin/coupons/' + form.value.id, form.value); else await http.post('/admin/coupons', form.value); ElMessage.success('保存成功'); dialog.value = false; load(); };
</script>
