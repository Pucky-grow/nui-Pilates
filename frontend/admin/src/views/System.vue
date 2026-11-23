<template><el-card><template #header>当前预约规则</template><p>{{ mode }} · {{ timezone }} · {{ window }}</p><el-form v-loading="busy" label-width="220px"><el-form-item v-for="(value,key) in values" :key="key" :label="labels[key] || key"><el-input-number v-model="values[key]" :min="0" /></el-form-item><el-button type="primary" :disabled="busy || !!error" @click="save">保存并生效</el-button><el-alert v-if="error" :title="error" type="error" /></el-form></el-card><el-card style="margin-top:16px">当前免费预约；新购课、Mock 支付、短信和微信订阅提醒关闭。门店资料请通过小程序管理员工作台维护；缺项须在正式发布前审核。</el-card></template>
<script setup>
import { ref, onMounted } from 'vue';import http from '../utils/request';import { ElMessage } from 'element-plus';
const values=ref({}),mode=ref(''),timezone=ref(''),window=ref(''),busy=ref(false),error=ref('');
const labels={'booking.advance_days':'提前预约天数','booking.deadline_minutes':'预约截止（开课前分钟）','booking.max_per_day':'每人每日上限','booking.cancel_free_hours':'免费取消提前小时数','booking.cancel_late_deduct':'历史扣课模式迟取消参数'};
async function load(){busy.value=true;error.value='';try{const d=await http.get('/admin/workspace/rules');values.value=d.values;mode.value=d.bookingMode;timezone.value=d.timezone;window.value=d.checkinWindow;}catch(e){error.value=e?.message||'加载失败，请刷新';}finally{busy.value=false;}}
async function save(){busy.value=true;try{await http.put('/admin/workspace/rules',values.value);ElMessage.success('已保存');}finally{busy.value=false;}await load();}onMounted(load);
</script>
