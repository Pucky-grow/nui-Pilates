<template>
  <el-card v-loading="busy">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-button @click="load">刷新</el-button>
    <el-table :data="rows" empty-text="暂无临时取消费记录">
      <el-table-column prop="booking_id" label="预约编号" width="100" />
      <el-table-column prop="nickname" label="会员" />
      <el-table-column prop="course_name" label="课程" />
      <el-table-column label="实际取消时间"><template #default="{ row }">{{ displayTime(row.cancelled_at) }}</template></el-table-column>
      <el-table-column label="规则金额"><template #default="{ row }">¥{{ money(row.late_fen) }}</template></el-table-column>
      <el-table-column label="处理状态"><template #default="{ row }">{{ label(row.state) }}</template></el-table-column>
      <el-table-column label="操作" min-width="180">
        <template #default="{ row }">
          <span v-if="row.state === 'ELIGIBLE' && !row.ready">原定开课后可处理</span>
          <template v-if="row.ready">
            <el-button link @click="act(row, 'CHARGE')">按规则收取</el-button>
            <el-button link @click="act(row, 'WAIVE')">免收</el-button>
          </template>
          <template v-if="auth.isAdmin() && row.state === 'WAITING_BALANCE'">
            <el-button link @click="act(row, 'settle')">续结算</el-button>
            <el-button link @click="act(row, 'waive')">免收</el-button>
          </template>
          <el-button v-if="auth.isAdmin() && row.state === 'POSTED'" link @click="act(row, 'reverse')">冲正</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-button v-if="more" @click="load(true)">加载更多</el-button>
  </el-card>
</template>

<script setup>
import { onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import http from '../utils/request';
import { commandSender, displayTime, label, money, root } from '../utils/studioLedger';
import { useAuthStore } from '../store/auth';
const auth = useAuthStore();
const send = commandSender();
const rows = ref([]), more = ref(false), busy = ref(false), error = ref('');
const path = '/api/studio/late-fees';
onMounted(load);

async function read(append) {
  const records = await http.get(path, { params: append ? { before: rows.value.at(-1)?.booking_id } : {} });
  rows.value = append ? [...rows.value, ...records] : records;
  more.value = records.length === 30;
}
async function run(work) {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try { await work(); }
  catch (err) { if (err !== 'cancel' && err !== 'close') error.value = err?.message || '处理失败，请重试'; }
  finally { busy.value = false; }
}
function load(append) { return run(() => read(append === true)); }
function act(row, action) {
  return run(async () => {
    const label = { CHARGE: '按规则收取 ¥' + money(row.late_fen), WAIVE: '免收', settle: '续结算', waive: '免收待结算费用', reverse: '冲正' }[action];
    const { value: reason } = await ElMessageBox.prompt(row.course_name + ' · 预约 #' + row.booking_id, label, {
      inputPlaceholder: '请填写处理原因', inputValidator: v => !!v?.trim() || '请填写原因'
    });
    if (action === 'CHARGE' || action === 'WAIVE') {
      await send(path + '/' + row.booking_id + '/decision', { decision: action, expectedFen: row.late_fen, reason });
    } else {
      let destination;
      if (action === 'reverse') {
        const { value } = await ElMessageBox.prompt('原账户已停用时，填写承接账户 GENERAL、GROUP 或 PRIVATE。原账户仍可用时可留空。', '冲正账户');
        destination = value?.trim();
      }
      await send(root + '/late-fees/' + row.booking_id + '/' + action, { reason, destination });
    }
    await read(false);
    ElMessage.success('处理成功');
  });
}
</script>
