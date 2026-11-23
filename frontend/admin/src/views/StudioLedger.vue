<template>
  <div v-if="auth.isAdmin()" v-loading="busy" class="ledger">
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <el-card v-if="memberId">
      <template #header>{{ overview.member?.nickname }} · {{ overview.member?.member_no }} · {{ overview.member?.binding === 'BOUND' ? '已绑定微信' : '待绑定微信' }}</template>
      <div class="accounts">
        <span v-for="account in overview.wallet?.accounts" :key="account.bucket">{{ label(account.bucket) }}　¥{{ money(account.balance_fen) }}</span>
      </div>
      <el-alert v-if="overview.wallet?.reconciliation?.some(row => row.balance_fen !== row.ledger_fen)" title="余额与流水不一致，请核对" type="error" />
      <div class="actions">
        <el-button @click="clearMember">返回待核实</el-button>
        <el-button @click="edit('booking')">代约</el-button>
        <el-button @click="edit('mode')">余额分配</el-button>
        <el-button @click="edit('import')">旧账切换</el-button>
        <el-button v-if="overview.member?.binding === 'UNBOUND'" @click="issue">生成邀请</el-button>
      </div>
      <el-input v-if="inviteCode" :model-value="inviteCode" readonly aria-label="一次性邀请码" />
    </el-card>
    <el-card v-else>
      <el-button type="primary" @click="edit('member')">新建线下会员</el-button>
      <el-button @click="router.push('/members')">查找已有会员</el-button>
      <el-button @click="edit('default')">门店默认余额方式</el-button>
      <el-button @click="router.push('/late-fees')">临时取消费</el-button>
    </el-card>
    <el-tabs v-model="tab" @tab-change="load">
      <el-tab-pane v-for="entry in tabs" :key="entry[0]" :name="entry[0]" :label="entry[1]" />
    </el-tabs>
    <div class="actions" v-if="tab !== 'review'">
      <el-button v-if="tab === 'lessons'" type="primary" @click="edit('lesson')">补录历史课</el-button>
      <el-button v-if="tab === 'receipts'" type="primary" @click="edit('receipt')">补录收退款</el-button>
      <el-button v-if="tab === 'sessions'" type="primary" @click="edit('session')">新增或关联场次</el-button>
      <el-button v-if="tab === 'rules'" type="primary" @click="edit('rule')">发布规则</el-button>
      <el-button v-if="tab === 'grants'" type="primary" @click="edit('grant')">添加授权</el-button>
    </div>
    <template v-if="tab === 'review'">
      <el-table :data="reviewRows" row-key="key" empty-text="暂无待核实">
        <el-table-column prop="member_no" label="会员编号" />
        <el-table-column prop="title" label="待处理事项" />
        <el-table-column prop="date" label="业务时间" />
        <el-table-column label="操作"><template #default="{ row }"><el-button link @click="openMember(row.member_id, row.tab)">核对</el-button></template></el-table-column>
      </el-table>
    </template>
    <template v-else>
      <el-table :data="rows" empty-text="暂无记录">
        <el-table-column label="编号" width="100"><template #default="{ row }">#{{ row.id || row.operator_id }}</template></el-table-column>
        <el-table-column label="记录"><template #default="{ row }">{{ titleOf(row) }}</template></el-table-column>
        <el-table-column label="时间"><template #default="{ row }">{{ displayTime(row.start_at || row.business_at || row.expires_at || row.effective_at || row.changed_at) }}</template></el-table-column>
        <el-table-column label="详情"><template #default="{ row }">{{ detailOf(row) }}</template></el-table-column>
        <el-table-column v-if="tab !== 'entries'" label="操作" min-width="190">
          <template #default="{ row }">
            <template v-if="tab === 'lessons' || tab === 'receipts'">
              <el-button v-if="row.state === 'PENDING_VERIFY'" link @click="review(row, 'verify')">核实入账</el-button>
              <el-button v-if="row.state === 'PENDING_VERIFY' || row.settlement === 'WAITING_BALANCE'" link @click="review(row, 'return')">退回</el-button>
              <el-button v-if="row.settlement === 'WAITING_BALANCE'" link @click="review(row, 'settle')">续结算</el-button>
              <el-button v-if="row.settlement === 'POSTED' || (tab === 'receipts' && ['VERIFIED', 'VERIFIED_COVERED'].includes(row.state))" link @click="review(row, 'reverse')">冲正</el-button>
              <el-button v-if="['PENDING_VERIFY', 'RETURNED', 'REVERSED'].includes(row.state)" link @click="edit(tab === 'lessons' ? 'lesson' : 'receipt', row)">编辑</el-button>
            </template>
            <template v-if="tab === 'invites'">
              <el-button v-if="row.state === 'REQUESTED'" link @click="edit('approve', row)">确认本人</el-button>
              <el-button v-if="['REQUESTED', 'ISSUED'].includes(row.state)" link @click="revoke(row)">撤销</el-button>
            </template>
            <el-button v-if="tab === 'sessions'" link @click="edit('session', row)">编辑</el-button>
            <el-button v-if="tab === 'grants'" link @click="edit('grant', row)">修改授权</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button v-if="more" @click="load(true)">加载更多</el-button>
    </template>
    <el-dialog v-model="dialog" :title="editorTitle" width="560px" :close-on-click-modal="false">
      <el-form label-width="170px">
        <el-form-item v-for="f in fields" :key="f.key" :label="f.label">
          <el-select v-if="f.options" v-model="form[f.key]" filterable>
            <el-option v-for="o in f.options" :key="o[0]" :label="o[1]" :value="o[0]" />
          </el-select>
          <el-date-picker v-else-if="f.type === 'datetime'" v-model="form[f.key]" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" />
          <el-date-picker v-else-if="f.type === 'date'" v-model="form[f.key]" type="date" value-format="YYYY-MM-DD" />
          <el-input v-else v-model="form[f.key]" :type="f.type || 'text'" />
        </el-form-item>
        <el-button v-if="editor === 'booking'" :disabled="!form.date || busy" @click="findSchedules">查询当日排课</el-button>
      </el-form>
      <template #footer>
        <el-button :disabled="busy" @click="dialog=false">返回</el-button>
        <el-button :loading="busy" type="primary" @click="save">确认</el-button>
      </template>
    </el-dialog>
  </div>
  <el-empty v-else description="请使用管理员账号" />
</template>

<script setup>
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useAuthStore } from '../store/auth';
import http from '../utils/request';
import { commandSender, displayTime, label, money, root } from '../utils/studioLedger';

const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const memberId = computed(() => route.query.memberId);
const base = computed(() => root + '/members/' + memberId.value);
const tabs = computed(() => memberId.value
  ? [['lessons', '课程'], ['receipts', '收退款'], ['entries', '余额流水'], ['invites', '认领']]
  : [['review', '待核实'], ['sessions', '场次'], ['rules', '收费规则'], ['grants', '授权']]);
const send = commandSender();
const tab = ref('review'), rows = ref([]), reviewRows = ref([]), overview = ref({}), defaultMode = ref('SHARED');
const busy = ref(false), error = ref(''), more = ref(false), inviteCode = ref('');
const dialog = ref(false), editor = ref(''), editorTitle = ref(''), form = ref({}), fields = ref([]);
const modes = [['SHARED', '小班与私教共用'], ['SPLIT', '按课型分开']];
const types = [['GROUP', '小班课'], ['PRIVATE', '私教课']];
const reasons = { key: 'reason', label: '原因或核对依据', type: 'textarea' };
const allocations = [input('group', '分配小班金额（元）'), input('privateAmount', '分配私教金额（元）')];
const feeKinds = [['NONE', '免收'], ['FIXED', '固定金额'], ['PERCENT', '按百分比']];
const settlementChoices = [['RULE', '按规则'], ['MANUAL', '手动核实金额'], ['WAIVE', '免收'], ['EXTERNAL', '已另付'], ['RECORD_ONLY', '仅补录']];
const outcomes = ['ATTENDED', 'NO_SHOW', 'TIMELY_CANCEL', 'LATE_CANCEL', 'STORE_CANCEL', 'UNKNOWN'].map(o => [o, label(o)]);
const duplicate = pick('duplicateConfirmed', '重复核对', [[false, '常规录入'], [true, '已核对，确属另一笔']]);

function input(key, label, type) { return { key, label, type }; }
function pick(key, label, options) { return { key, label, options }; }
function today() { return new Date(Date.now() + 8 * 3600000).toISOString().slice(0, 10); }
function numeric(value) { return value === '' || value == null ? null : Number(value); }
function units(kind, value) {
  if (kind === 'NONE') return 0;
  if (!/^\d{1,7}(\.\d{1,2})?$/.test(String(value ?? ''))) throw new Error('金额或比例最多两位小数');
  const [integer, decimal = ''] = String(value).split('.');
  return Number(integer) * 100 + Number(decimal.padEnd(2, '0'));
}
function cancellation(error) { return error === 'cancel' || error === 'close'; }

async function run(work) {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try { return await work(); }
  catch (err) { if (!cancellation(err)) error.value = err?.message || '操作失败，请重试'; }
  finally { busy.value = false; }
}

watch(() => [route.query.memberId, route.query.tab], () => {
  tab.value = tabs.value.some(t => t[0] === route.query.tab) ? route.query.tab : tabs.value[0][0];
  overview.value = {};
  inviteCode.value = '';
  rows.value = [];
  load();
}, { immediate: true });

function load(append) { if (auth.isAdmin()) return run(() => read(append === true)); }

async function read(append = false) {
  const viewTab = tab.value;
  if (memberId.value) overview.value = await http.get(base.value);
  if (viewTab === 'review') {
    const [queue, mode] = await Promise.all([http.get(root + '/review'), http.get(root + '/default-mode')]);
    defaultMode.value = mode;
    reviewRows.value = [
      ...queue.lessons.map(row => ({ ...row, key: 'lesson-' + row.id, tab: 'lessons', title: row.course_name + ' · ' + label(row.settlement), date: displayTime(row.start_at) })),
      ...queue.receipts.map(row => ({ ...row, key: 'receipt-' + row.id, tab: 'receipts', title: label(row.kind) + ' ¥' + money(row.amount_fen), date: displayTime(row.business_at) }))
    ];
    return;
  }
  const route = viewTab === 'grants' ? 'late-fee-grants' : viewTab;
  const url = (memberId.value ? base.value : root) + '/' + route;
  const data = await http.get(url, { params: append ? { before: rows.value.at(-1)?.id } : {} });
  rows.value = append ? [...rows.value, ...data] : data;
  more.value = data.length === 30 && viewTab !== 'grants' && viewTab !== 'invites';
}

function openMember(id, tab) { return router.push({ path: '/studio-ledger', query: { memberId: id, tab } }); }
function clearMember() { return router.push('/studio-ledger'); }

function titleOf(row) {
  if (tab.value === 'rules') return label(row.course_type);
  if (tab.value === 'grants') return row.name;
  return row.course_name || label(row.kind) || label(row.state);
}

function detailOf(row) {
  if (tab.value === 'rules') {
    const feeText = (kind, value) => kind === 'NONE' ? '免收' : money(value) + (kind === 'PERCENT' ? '%' : ' 元');
    return '免费窗口 ' + row.free_minutes + ' 分钟 · 临时取消 ' + feeText(row.late_kind, row.late_value) + ' · 爽约 ' + feeText(row.absent_kind, row.absent_value);
  }
  if (tab.value === 'grants') return row.enabled ? '已授权' : '已撤销';
  if (tab.value === 'entries') return '¥' + money(row.delta_fen) + ' · 入账后 ¥' + money(row.after_fen) + ' · ' + row.reason;
  if (tab.value === 'sessions') return label(row.course_type) + ' · ' + row.coach_name + ' · ¥' + money(row.price_fen);
  return [label(row.outcome), label(row.settlement || row.state), row.amount_fen == null ? '' : '¥' + money(row.amount_fen), row.reason].filter(Boolean).join(' · ');
}

function edit(kind, row) {
  return run(async () => {
    const wallet = overview.value.wallet || {};
    let list, values, title;
    switch (kind) {
      case 'member':
        title = '新建线下会员';
        list = [input('nickname', '会员称呼'), input('phone', '手机号（选填）'), input('sourceRef', '旧会员编号（选填）'), pick('mode', '余额方式', modes)];
        values = { mode: defaultMode.value };
        break;
      case 'receipt':
        title = '补录充值或退款';
        list = [pick('kind', '单据类型', [['TOPUP', '实收充值'], ['OPENING', '期初余额'], ['REFUND', '实际线下退款']]),
          input('amount', '总金额（元）'), ...(wallet.mode === 'SPLIT' ? allocations : []), input('channel', '渠道'),
          input('externalRef', '凭据编号（选填）'), input('originalId', '原充值编号（退款）', 'number'),
          input('businessAt', '业务时间', 'datetime'), duplicate, reasons];
        values = row ? { id: row.id, version: row.version, kind: row.kind, amount: money(row.amount_fen), group: money(row.group_fen),
          privateAmount: money(row.private_fen), channel: row.channel, externalRef: row.external_ref, originalId: row.original_id,
          businessAt: row.business_at, reason: row.reason } : { kind: 'TOPUP', businessAt: today() + 'T09:00:00' };
        break;
      case 'lesson': {
        title = '补录历史课程';
        const sessions = await http.get(root + '/sessions');
        list = [pick('sessionId', '选择场次', sessions.map(s => [s.id, '#' + s.id + ' ' + s.course_name + ' ' + displayTime(s.start_at)])),
          input('sessionOverride', '或填写场次编号', 'number'), input('bookingId', '原预约 ID（选填）', 'number'),
          pick('outcome', '课程结果', outcomes), input('cancelledAt', '实际取消时间', 'datetime'),
          pick('decision', '结算方式', settlementChoices), input('manualAmount', '手动金额（元）'), duplicate, reasons];
        values = row ? { id: row.id, version: row.version, sessionId: row.session_id, bookingId: row.booking_id,
          outcome: row.outcome, cancelledAt: row.cancelled_at, decision: row.decision,
          manualAmount: row.manual_fen == null ? '' : money(row.manual_fen), reason: row.reason } : { outcome: 'ATTENDED', decision: 'RULE' };
        break;
      }
      case 'session':
        title = '新增或关联场次';
        list = [input('scheduleId', '系统排课 ID（选填）', 'number'), input('sourceRef', '旧场次编号（选填）'),
          input('courseName', '线下课程名称'), input('coachName', '线下教练'), pick('courseType', '课型', types),
          input('startAt', '开课时间', 'datetime'),
          input('price', '单人标准价（元）'), input('ruleId', '历史规则编号（选填）', 'number')];
        values = row ? { id: row.id, version: row.version, scheduleId: row.schedule_id, sourceRef: row.source_ref,
          courseName: row.course_name, coachName: row.coach_name, courseType: row.course_type, startAt: row.start_at,
          price: money(row.price_fen), ruleId: row.rule_id } : { courseType: 'GROUP' };
        break;
      case 'rule':
        title = '发布收费规则';
        list = [pick('courseType', '课型', types), input('effectiveAt', '生效时间', 'datetime'),
          input('freeMinutes', '免费取消窗口（分钟）', 'number'), pick('lateKind', '临时取消', feeKinds),
          input('lateValue', '金额（元）或比例（%）'), pick('absentKind', '爽约', feeKinds), input('absentValue', '金额（元）或比例（%）')];
        values = { courseType: 'GROUP', effectiveAt: today() + 'T00:00:00', freeMinutes: 120, lateKind: 'NONE', absentKind: 'NONE' };
        break;
      case 'mode':
        title = '调整余额分配';
        list = [pick('mode', '新余额方式', modes), ...allocations, reasons];
        values = { mode: wallet.mode === 'SHARED' ? 'SPLIT' : 'SHARED' };
        break;
      case 'import':
        title = '设置旧账切换';
        list = [pick('mode', '旧账方式', [['REPLAY', '逐笔重录'], ['OPENING', '期初余额']]), input('cutoffAt', '切换时间', 'datetime'), reasons];
        values = { mode: wallet.import_mode || 'REPLAY', cutoffAt: wallet.cutoff_at };
        break;
      case 'default':
        title = '门店默认余额方式';
        list = [pick('mode', '默认方式', modes), reasons];
        values = { mode: defaultMode.value };
        break;
      case 'grant': {
        title = '临时取消费授权';
        const accounts = (await http.get('/api/admin/accounts', { params: { size: 100 } })).list;
        list = [pick('operatorId', '后台账号', accounts.filter(a => a.roles.some(role => ['COACH', 'STAFF', 'STORE_MANAGER'].includes(role)))
          .map(a => [a.id, a.name + ' · ' + a.username])), pick('enabled', '权限', [[true, '授予'], [false, '撤销']])];
        values = { operatorId: row?.operator_id, enabled: !row?.enabled };
        break;
      }
      case 'approve':
        title = '核对本人并绑定';
        list = [input('proof', '会员手机核对码'), reasons];
        values = { id: row.id };
        break;
      case 'booking':
        title = '为会员代约';
        list = [input('date', '排课日期', 'date'), pick('scheduleId', '可约课程', []), reasons];
        values = { date: today() };
        break;
    }
    editor.value = kind;
    editorTitle.value = title;
    form.value = values;
    fields.value = list;
    dialog.value = true;
  });
}

function findSchedules() {
  return run(async () => {
    const courses = await http.get('/api/course-schedules', { params: { date: form.value.date, days: 1 } });
    fields.value = fields.value.map(f => f.key !== 'scheduleId' ? f : {
      ...f, options: courses.filter(c => c.bookable).map(c => [c.id, c.startTime + ' ' + c.courseName + ' · ' + c.coachName])
    });
    form.value.scheduleId = null;
  });
}

function save() {
  return run(async () => {
    const kind = editor.value;
    const data = { ...form.value };
    const path = base.value;
    const wallet = overview.value.wallet || {};
    const routes = {
      member: root + '/members', receipt: path + '/receipts', lesson: path + '/lessons', session: root + '/sessions',
      rule: root + '/rules', mode: path + '/mode', import: path + '/import', default: root + '/default-mode',
      grant: root + '/late-fee-grants/' + data.operatorId, approve: path + '/invites/' + data.id + '/approve',
      booking: path + '/bookings'
    };
    if (kind === 'receipt') {
      data.originalId = numeric(data.originalId);
      if (wallet.mode === 'SPLIT') data.allocation = { group: data.group, privateAmount: data.privateAmount };
    }
    if (kind === 'lesson') {
      data.sessionId = numeric(data.sessionOverride || data.sessionId);
      data.bookingId = numeric(data.bookingId);
    }
    if (kind === 'session') {
      data.scheduleId = numeric(data.scheduleId);
      data.ruleId = numeric(data.ruleId);
    }
    if (kind === 'rule') {
      data.freeMinutes = numeric(data.freeMinutes);
      data.lateValue = units(data.lateKind, data.lateValue);
      data.absentValue = units(data.absentKind, data.absentValue);
    }
    if (kind === 'mode') {
      data.version = wallet.version;
      if (data.mode === 'SPLIT') data.allocation = { group: data.group, privateAmount: data.privateAmount };
      await ElMessageBox.confirm('请核对当前余额 ¥' + money(wallet.balance_fen) + ' 与分配金额。', editorTitle.value);
    }
    if (kind === 'booking') {
      if (!data.scheduleId) throw new Error('请选择可约课程');
      const terms = await http.get(root + '/booking-terms/' + data.scheduleId);
      if (terms) {
        await ElMessageBox.confirm('请先与会员确认课程价 ¥' + money(terms.priceFen) + '，' +
          displayTime(terms.cutoffAt) + ' 后临时取消费 ¥' + money(terms.lateFen) + '。', '确认代约');
        data.quote = terms.quote;
      }
    }
    const result = await send(routes[kind], data);
    dialog.value = false;
    if (kind === 'member') {
      await openMember(result.id);
      await read();
    } else {
      await read();
    }
    ElMessage.success(kind === 'booking' ? '已预约 #' + result : '已保存');
  });
}

function review(row, action) {
  return run(async () => {
    const url = base.value + '/' + tab.value + '/' + row.id;
    const quote = action === 'verify' && tab.value === 'lessons' ? await http.get(url + '/quote') : null;
    const text = quote ? label(quote.bucket) + ' · 应扣 ¥' + money(quote.amountFen) + ' · 可用 ¥' + money(quote.balanceFen) : titleOf(row) + ' · ' + detailOf(row);
    const title = { verify: '核实并入账', settle: '续结算', return: '退回', reverse: '冲正' }[action];
    const { value: reason } = await ElMessageBox.prompt(text, title, { inputPlaceholder: '请填写原因', inputValidator: v => !!v?.trim() || '请填写原因' });
    let destination;
    if (action === 'reverse' && overview.value.wallet?.accounts?.length) {
      const { value } = await ElMessageBox.prompt('原账户已停用时，填写当前承接账户 GENERAL、GROUP 或 PRIVATE。原账户仍可用时可留空。', '冲正账户');
      destination = value?.trim();
    }
    await send(url + '/' + action, { reason, version: row.version, quote: quote?.token, destination });
    await read();
    ElMessage.success('处理成功');
  });
}

function issue() {
  return run(async () => {
    await ElMessageBox.confirm('邀请码有效 24 小时。生成新邀请会撤销之前的未使用邀请。', '生成认领邀请');
    inviteCode.value = (await http.post(base.value + '/invites', {})).code;
    tab.value = 'invites';
    await read();
  });
}

function revoke(row) {
  return run(async () => {
    await ElMessageBox.confirm('确定撤销这个邀请？', '撤销邀请');
    await send(base.value + '/invites/' + row.id + '/revoke', {});
    await read();
  });
}
</script>

<style scoped>
.ledger { display: grid; gap: 12px; }
.actions, .accounts { display: flex; flex-wrap: wrap; gap: 8px; margin: 8px 0; }
</style>
