<template>
  <div>
    <el-card>
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="课程名称" style="width:220px" clearable @change="load" />
        <el-button type="primary" @click="openForm(null)">新增课程</el-button>
      </div>
      <el-table :data="list" style="margin-top:16px">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="课程名称" />
        <el-table-column prop="categoryName" label="分类" width="120" />
        <el-table-column prop="type" label="类型" width="100" />
        <el-table-column prop="difficulty" label="难度" width="90" />
        <el-table-column prop="creditsCost" label="课时" width="70" />
        <el-table-column prop="price" label="价格" width="90" />
        <el-table-column prop="status" label="状态" width="90" />
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button size="small" @click="openForm(row)">编辑</el-button>
            <el-button size="small" type="warning" @click="toggleShelf(row)">{{ row.status=='ON_SHELF'?'下架':'上架' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination :total="total" :page-size="size" v-model:current-page="page" @current-change="load" layout="total, prev, pager, next" style="margin-top:16px"/>
    </el-card>

    <el-dialog v-model="dialog" :title="form.id ? '编辑课程' : '新增课程'" width="560px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="分类"><el-select v-model="form.categoryId"><el-option v-for="c in cats" :key="c.id" :label="c.name" :value="c.id"/></el-select></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="类型"><el-select v-model="form.type"><el-option label="团课" value="GROUP"/><el-option label="私教" value="PRIVATE"/><el-option label="体验课" value="EXPERIENCE"/></el-select></el-form-item>
        <el-form-item label="难度"><el-select v-model="form.difficulty"><el-option label="入门" value="BEGINNER"/><el-option label="初级" value="JUNIOR"/><el-option label="中级" value="INTERMEDIATE"/><el-option label="进阶" value="ADVANCED"/></el-select></el-form-item>
        <el-form-item label="时长(分)"><el-input-number v-model="form.durationMinutes" :min="15" /></el-form-item>
        <el-form-item label="课时"><el-input-number v-model="form.creditsCost" :min="1" /></el-form-item>
        <el-form-item label="价格"><el-input-number v-model="form.price" :min="0" :precision="2" /></el-form-item>
        <el-form-item label="介绍"><el-input v-model="form.description" type="textarea" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue';
import { ElMessage } from 'element-plus';
import http from '../utils/request';

const list = ref([]); const total = ref(0); const page = ref(1); const size = ref(10);
const keyword = ref(''); const cats = ref([]); const dialog = ref(false); const form = ref({});

onMounted(async () => { cats.value = await http.get('/api/course-categories'); load(); });
const load = async () => {
  const d = await http.get('/admin/courses', { params: { page: page.value, size: size.value, keyword: keyword.value } });
  list.value = d.list; total.value = d.total;
};
const openForm = (row) => { form.value = row ? { ...row } : { categoryId: cats.value[0]?.id, type: 'GROUP', difficulty: 'BEGINNER', durationMinutes: 60, creditsCost: 1, price: 0, status: 'ON_SHELF' }; dialog.value = true; };
const save = async () => {
  if (form.value.id) await http.put('/admin/courses/' + form.value.id, form.value);
  else await http.post('/admin/courses', form.value);
  ElMessage.success('保存成功'); dialog.value = false; load();
};
const toggleShelf = async (row) => {
  await http.put('/admin/courses/' + row.id + '/shelf?onShelf=' + (row.status !== 'ON_SHELF'));
  load();
};
</script>
<style scoped>
.toolbar { display: flex; gap: 12px; }
</style>
