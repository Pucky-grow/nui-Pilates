<template>
  <div>
    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" />
    <el-button v-if="loadError" @click="load">重试</el-button>
    <el-card>
      <div class="toolbar">
        <el-radio-group v-model="status" @change="load">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="PENDING_REVIEW">待审核</el-radio-button>
          <el-radio-button value="APPROVED">已通过</el-radio-button>
          <el-radio-button value="REJECTED">已驳回</el-radio-button>
        </el-radio-group>
      </div>
      <el-table v-loading="loading" :empty-text="loadError ? '加载失败' : '暂无数据'" :data="list" style="margin-top:16px">
        <el-table-column prop="proposalNo" label="申请编号" width="150" />
        <el-table-column prop="courseName" label="课程名称" />
        <el-table-column prop="coachId" label="教练ID" width="80" />
        <el-table-column prop="courseType" label="类型" width="80" />
        <el-table-column prop="difficulty" label="难度" width="80" />
        <el-table-column prop="suggestedCapacity" label="建议人数" width="90" />
        <el-table-column prop="status" label="状态" width="100" />
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <template v-if="row.status=='PENDING_REVIEW'">
              <el-button size="small" type="success" @click="approve(row)">通过</el-button>
              <el-button size="small" type="danger" @click="reject(row)">驳回</el-button>
            </template>
            <span v-else-if="row.status=='REJECTED'" style="color:#c08572">{{ row.rejectReason }}</span>
            <span v-else>—</span>
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
  list.value = await http.get('/admin/course-proposals', { params: { status: status.value } });

  } catch (error) { loadError.value = error?.message || '加载失败，请重试'; }
  finally { loading.value = false; }
};
const approve = async (row) => {
  await http.post('/admin/course-proposals/' + row.id + '/approve');
  ElMessage.success('已通过, 已生成正式课程并绑定教练');
  load();
};
const reject = async (row) => {
  const { value } = await ElMessageBox.prompt('驳回原因', '驳回课程申请', { inputValidator: v => !!v || '请填写驳回原因' });
  await http.post('/admin/course-proposals/' + row.id + '/reject?reason=' + encodeURIComponent(value));
  ElMessage.success('已驳回');
  load();
};
</script>
<style scoped>.toolbar { display: flex; gap: 12px; }</style>
