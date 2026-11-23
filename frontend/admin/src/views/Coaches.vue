<template>
  <div>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" />
    <el-button v-if="loadError" @click="load">重试</el-button>
    <el-card>
      <el-button type="primary" @click="openForm(null)">新增教练</el-button>
      <el-table v-loading="loading" :empty-text="loadError ? '加载失败' : '暂无数据'" :data="list" style="margin-top:16px">
        <el-table-column label="头像" width="86"><template #default="{ row }"><el-image v-if="row.avatar" :src="row.avatar" fit="cover" style="width:48px;height:48px;border-radius:50%"/></template></el-table-column>
        <el-table-column prop="name" label="姓名" width="120" />
        <el-table-column prop="title" label="职位" />
        <el-table-column prop="years" label="从业年限" width="100" />
        <el-table-column prop="specialties" label="专业方向" />
        <el-table-column prop="rating" label="评分" width="80" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }"><el-button size="small" @click="openForm(row)">编辑</el-button></template>
        </el-table-column>
      </el-table>
      <el-pagination :total="total" :page-size="10" v-model:current-page="page" @current-change="load" layout="total, prev, pager, next" style="margin-top:16px"/>
    </el-card>
    <el-dialog v-model="dialog" :title="form.id?'编辑教练':'新增教练'" width="480px" :before-close="closeDialog">
      <el-form :model="form" label-width="80px">
        <el-form-item label="头像"><el-image v-if="form.avatar" :src="form.avatar" fit="contain" style="width:96px;height:96px"/><el-upload :show-file-list="false" accept="image/png,image/jpeg,image/gif" :http-request="uploadAvatar" :disabled="uploading || saving"><el-button :loading="uploading">上传头像</el-button></el-upload></el-form-item>
        <el-form-item label="姓名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="职位"><el-input v-model="form.title" /></el-form-item>
        <el-form-item label="年限"><el-input-number v-model="form.years" :min="0" /></el-form-item>
        <el-form-item label="专长"><el-input v-model="form.specialties" /></el-form-item>
        <el-form-item label="简介"><el-input v-model="form.bio" type="textarea" /></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="uploading || saving" @click="dialog=false">取消</el-button><el-button type="primary" @click="save" :disabled="uploading || saving" :loading="saving">保存</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup>
import { ref, onMounted, onUnmounted } from 'vue';
import { ElMessage } from 'element-plus';
import http from '../utils/request';
const list = ref([]); const total = ref(0); const page = ref(1); const dialog = ref(false); const form = ref({});
const refreshOnFocus = () => { if (!dialog.value) load(); };
onMounted(() => { load(); window.addEventListener("focus", refreshOnFocus); });
onUnmounted(() => window.removeEventListener("focus", refreshOnFocus));
const uploading = ref(false); const saving = ref(false);
const closeDialog = (done) => { if (!uploading.value && !saving.value) done(); };
let loadSequence = 0;
const loading = ref(false);
const loadError = ref('');
const load = async () => {
  const sequence = ++loadSequence; loading.value = true; loadError.value = '';
  try { const d = await http.get('/admin/coaches', { params: { page: page.value, size: 10 } }); if (sequence !== loadSequence) return; list.value = d.list; total.value = d.total;
  } catch (error) { if (sequence === loadSequence) loadError.value = error?.message || '加载失败，请重试'; }
  finally { if (sequence === loadSequence) loading.value = false; }
};
const openForm = (row) => { form.value = row ? { ...row } : { years: 0 }; dialog.value = true; };
const uploadAvatar = async ({file,onSuccess,onError}) => {
  uploading.value = true;
  try {
    const data = new FormData(); data.append('file',file); data.append('purpose','PUBLIC_ASSET');
    const result = await http.post('/api/upload',data);
    const url = result.previewUrl || result.url;
    if (!url) throw new Error('上传服务未返回图片地址');
    form.value.avatar = url; onSuccess(result);
  } catch (error) { onError(error); }
  finally { uploading.value = false; }
};
const save = async () => {
  if (saving.value || uploading.value) return;
  saving.value = true;
  try {
    if (form.value.id) await http.put('/admin/coaches/' + form.value.id, form.value);
    else await http.post('/admin/coaches', form.value);
    dialog.value = false; await load(); ElMessage.success('保存成功');
  } catch (_) { /* API interceptor displays the error. */ }
  finally { saving.value = false; }
};
</script>
