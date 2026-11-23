<template>
  <div>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" />
    <el-button v-if="loadError" @click="load">重试</el-button>
    <el-card>
      <el-button type="primary" @click="openForm(null)">新增课包</el-button>
      <el-table v-loading="loading" :empty-text="loadError ? '加载失败' : '暂无数据'" :data="list" style="margin-top:16px">
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="type" label="类型" width="90" />
        <el-table-column prop="salePrice" label="售价" width="90" />
        <el-table-column prop="totalCredits" label="课时" width="80" />
        <el-table-column prop="validDays" label="有效期(天)" width="100" />
        <el-table-column prop="status" label="状态" width="90" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }"><el-button size="small" @click="openForm(row)">编辑</el-button></template>
        </el-table-column>
      </el-table>
      <el-pagination :total="total" :page-size="10" v-model:current-page="page" @current-change="load" layout="total, prev, pager, next" style="margin-top:16px"/>
    </el-card>
    <el-dialog v-model="dialog" :title="form.id?'编辑课包':'新增课包'" width="480px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="类型"><el-select v-model="form.type"><el-option label="次卡" value="TIMES"/><el-option label="期限卡" value="PERIOD"/><el-option label="私教课包" value="PRIVATE"/></el-select></el-form-item>
        <el-form-item label="原价"><el-input-number v-model="form.originalPrice" :precision="2" :min="0" /></el-form-item>
        <el-form-item label="售价"><el-input-number v-model="form.salePrice" :precision="2" :min="0" /></el-form-item>
        <el-form-item label="课时"><el-input-number v-model="form.totalCredits" :min="0" /></el-form-item>
        <el-form-item label="有效期(天)"><el-input-number v-model="form.validDays" :min="1" /></el-form-item>
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
  try { const d = await http.get('/admin/packages', { params: { page: page.value, size: 10 } }); list.value = d.list; total.value = d.total; 
  } catch (error) { loadError.value = error?.message || '加载失败，请重试'; }
  finally { loading.value = false; }
};
const openForm = (row) => { form.value = row ? { ...row } : { type: 'TIMES', totalCredits: 10, validDays: 180, status: 'ON_SHELF' }; dialog.value = true; };
const save = async () => { if (form.value.id) await http.put('/admin/packages/' + form.value.id, form.value); else await http.post('/admin/packages', form.value); ElMessage.success('保存成功'); dialog.value = false; load(); };
</script>
