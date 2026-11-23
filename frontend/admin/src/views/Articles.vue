<template>
  <div>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" />
    <el-button v-if="loadError" @click="load">重试</el-button>
    <el-card>
      <el-button type="primary" @click="openForm(null)">新增文章</el-button>
      <el-table v-loading="loading" :empty-text="loadError ? '加载失败' : '暂无数据'" :data="list" style="margin-top:16px">
        <el-table-column prop="title" label="标题" />
        <el-table-column prop="author" label="作者" width="120" />
        <el-table-column prop="views" label="阅读" width="90" />
        <el-table-column prop="status" label="状态" width="90" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }"><el-button size="small" @click="openForm(row)">编辑</el-button></template>
        </el-table-column>
      </el-table>
      <el-pagination :total="total" :page-size="10" v-model:current-page="page" @current-change="load" layout="total, prev, pager, next" style="margin-top:16px"/>
    </el-card>
    <el-dialog v-model="dialog" :title="form.id?'编辑文章':'新增文章'" width="640px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="分类ID"><el-input-number v-model="form.categoryId" :min="1" /></el-form-item>
        <el-form-item label="标题"><el-input v-model="form.title" /></el-form-item>
        <el-form-item label="作者"><el-input v-model="form.author" /></el-form-item>
        <el-form-item label="封面"><el-input v-model="form.cover" /></el-form-item>
        <el-form-item label="正文"><el-input v-model="form.content" type="textarea" :rows="6" /></el-form-item>
        <el-form-item label="状态"><el-select v-model="form.status"><el-option label="草稿" value="DRAFT"/><el-option label="发布" value="PUBLISHED"/></el-select></el-form-item>
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
  try { const d = await http.get('/admin/articles', { params: { page: page.value, size: 10 } }); list.value = d.list; total.value = d.total; 
  } catch (error) { loadError.value = error?.message || '加载失败，请重试'; }
  finally { loading.value = false; }
};
const openForm = (row) => { form.value = row ? { ...row } : { status: 'DRAFT' }; dialog.value = true; };
const save = async () => { if (form.value.id) await http.put('/admin/articles/' + form.value.id, form.value); else await http.post('/admin/articles', form.value); ElMessage.success('保存成功'); dialog.value = false; load(); };
</script>
