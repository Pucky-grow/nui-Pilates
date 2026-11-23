<template>
  <div>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" />
    <el-button v-if="loadError" @click="load">重试</el-button>
    <el-card>
      <div class="toolbar">
        <el-radio-group v-model="status" @change="load">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="PENDING">待处理</el-radio-button>
          <el-radio-button value="PROCESSING">处理中</el-radio-button>
          <el-radio-button value="DONE">已完成</el-radio-button>
        </el-radio-group>
      </div>
      <el-table v-loading="loading" :empty-text="loadError ? '加载失败' : '暂无数据'" :data="list" style="margin-top:16px">
        <el-table-column prop="userId" label="用户ID" width="80" />
        <el-table-column prop="type" label="类型" width="100" />
        <el-table-column prop="content" label="内容" />
        <el-table-column prop="contact" label="联系方式" width="130" />
        <el-table-column prop="status" label="状态" width="100" />
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button v-if="row.status!='DONE'" size="small" type="primary" @click="handle(row)">处理</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import http from '../utils/request';

const list = ref([]);
const status = ref('');

onMounted(() => load());
const loading = ref(false);
const loadError = ref('');
const load = async () => {
  loading.value = true; loadError.value = '';
  try {
  list.value = await http.get('/admin/feedback', { params: { status: status.value } });

  } catch (error) { loadError.value = error?.message || '加载失败，请重试'; }
  finally { loading.value = false; }
};
const handle = async (row) => {
  const { value } = await ElMessageBox.prompt('处理回复', '处理反馈');
  await http.post('/admin/feedback/' + row.id + '/handle', { status: 'DONE', reply: value });
  ElMessage.success('已处理');
  load();
};
</script>
<style scoped>.toolbar { display: flex; gap: 12px; }</style>
