const { clock, money } = require('../../utils/studio-ledger');

const option = (value, label) => ({ value, label });
const field = (key, label, type, hint) => ({ key, label, type, hint });
const pick = (key, label, options) => ({ key, label, type: 'select', options: options.map(([v, l]) => option(v, l)) });
const dayTime = (key, label) => [field(key + 'Date', label + '日期', 'date'), field(key + 'Time', label + '时间', 'time')];
const modes = [['SHARED', '小班和私教共用'], ['SPLIT', '按课型分开']];
const types = [['GROUP', '小班课'], ['PRIVATE', '私教课']];
const feeKinds = [['NONE', '免收'], ['FIXED', '固定金额'], ['PERCENT', '按比例']];
const reason = { ...field('reason', '原因或核实依据', 'textarea'), maxlength: 255 };
const duplicate = pick('duplicateConfirmed', '重复核对（收到提示时填写）', [[0, '常规录入'], [1, '已核对，确属另一笔']]);
const allocationFields = [field('group', '分配给小班（元）', 'decimal'), field('privateAmount', '分配给私教（元）', 'decimal')];
const receiptKinds = { recharge: 'TOPUP', opening: 'OPENING', refund: 'REFUND' };
const cancelledOutcomes = ['LATE_CANCEL', 'TIMELY_CANCEL', 'STORE_CANCEL'];
const recordedDecisions = ['WAIVE', 'RECORD_ONLY', 'EXTERNAL'];
const number = value => value === '' || value == null ? null : Number(value);
const allocation = form => ({ group: form.group || '', privateAmount: form.privateAmount || '' });
const feeValue = (kind, value) => {
  if (kind === 'NONE') return 0;
  if (!/^\d{1,7}(\.\d{1,2})?$/.test(value || '')) throw { message: '固定费或比例最多两位小数' };
  const [a, b = ''] = String(value).split('.');
  return Number(a) * 100 + Number(b.padEnd(2, '0'));
};
const dateFields = (key, value) => ({
  [key + 'Date']: value ? value.slice(0, 10) : '',
  [key + 'Time']: value ? value.slice(11, 16) : ''
});
const receiptDate = (date, fields = {}) => ({ businessDate: date, businessTime: '', ...fields });

function definition(kind, data, row, sessions) {
  const { wallet = {}, today } = data;
  switch (kind) {
    case 'member': return {
      title: '新建线下会员', fields: [field('nickname', '会员称呼'), field('phone', '手机号（选填）'), field('sourceRef', '旧会员编号（选填）'), pick('mode', '余额方式', modes)],
      form: { mode: data.defaultMode }
    };
    case 'recharge':
    case 'opening':
    case 'refund':
    case 'receipt': {
      const type = row?.kind || receiptKinds[kind] || 'TOPUP';
      const isOpening = type === 'OPENING';
      const isRefund = type === 'REFUND';
      const cutoff = wallet.import_mode === 'OPENING' ? wallet.cutoff_at : null;
      const base = isOpening ? '期初余额' : isRefund ? '实退金额' : '实收充值';
      return {
        title: row ? '更正' + base : isOpening ? '录入期初余额' : isRefund ? '登记退款' : '补录充值',
        hint: isOpening ? '填写旧账核对后的剩余金额和对应时点，再到「充值与退款」核实单据。'
          : isRefund ? '填写实际退给会员的金额和原充值记录。'
            : cutoff ? '填写实际收到的金额和日期。' + cutoff.replace('T', ' ').slice(0, 16) + ' 及之前的充值由期初余额覆盖。'
              : '填写原来实际收到的金额和日期。核实入账后，金额会计入当前账户。',
        fields: [field('amount', base + '（元）', 'decimal', wallet.mode === 'SPLIT' ? '小班和私教的分配合计需等于此金额' : ''),
          ...(wallet.mode === 'SPLIT' ? allocationFields : []),
          ...dayTime('business', isOpening ? '余额核对' : isRefund ? '退款' : '收款'),
          ...(isOpening ? [] : [field('channel', isRefund ? '退款渠道' : '收款渠道', 'text', '如现金、转账或原台账'),
            field('externalRef', '凭据编号（选填）'), ...(isRefund ? [field('originalId', '原充值记录编号', 'number')] : []),
            { ...duplicate, when: 'duplicate' }]), reason],
        form: row ? { id: row.id, version: row.version, kind: type, amount: money(row.amount_fen),
          group: money(row.group_fen), privateAmount: money(row.private_fen), channel: row.channel || (isOpening ? '原台账' : ''),
          externalRef: row.external_ref, originalId: row.original_id, reason: row.reason, ...dateFields('business', row.business_at) }
          : receiptDate(today, { kind: type, ...(isOpening ? { channel: '原台账', ...dateFields('business', cutoff) } : {}) })
      };
    }
    case 'lesson': return {
      title: row ? '更正历史课程' : '补录历史课程',
      hint: '选择历史场次和结算方式后，系统会在保存前核对能否扣费。',
      fields: [pick('sessionId', '选择场次', sessions.map(s => [s.id, '#' + s.id + ' ' + s.course_name + ' ' + s.timeText])),
        field('sessionOverride', '或填写场次编号', 'number'), field('bookingId', '原预约 ID（可自动关联）', 'number'),
        pick('outcome', '课程结果', [['ATTENDED', '已上课'], ['NO_SHOW', '爽约'], ['LATE_CANCEL', '临时取消'], ['TIMELY_CANCEL', '按时取消'], ['STORE_CANCEL', '门店取消'], ['UNKNOWN', '待确认']]),
        ...dayTime('cancelled', '实际取消').map(item => ({ ...item, when: 'cancelled' })),
        pick('decision', '结算方式', [['RULE', '按规则结算'], ['MANUAL', '填实际扣费'], ['WAIVE', '免收'], ['EXTERNAL', '已另付'], ['RECORD_ONLY', '仅补录']]),
        { ...field('manualAmount', '实际扣费（元）', 'decimal', '按旧账填写，上限为本节确认价'), when: 'manual' },
        { ...field('manualAmount', '原费用（选填，元）', 'decimal'), when: 'recordedAmount' },
        { ...duplicate, when: 'duplicate' }, reason],
      form: row ? { id: row.id, version: row.version, sessionId: row.session_id, bookingId: row.booking_id,
        outcome: row.outcome, decision: row.decision, manualAmount: row.manual_fen == null ? '' : money(row.manual_fen),
        reason: row.reason, ...dateFields('cancelled', row.cancelled_at) } : { outcome: 'ATTENDED', decision: 'MANUAL' }
    };
    case 'session': return {
      title: row ? '调整场次' : '新增或关联场次',
      fields: [field('scheduleId', '系统排课 ID（选填）', 'number', '关联排课时以系统课程、教练和时间为准'),
        field('sourceRef', '旧场次来源编号（选填）'), field('courseName', '线下课程名称'), field('coachName', '线下授课教练'),
        pick('courseType', '课型', types), ...dayTime('start', '开课'),
        field('price', '单人标准价（元）', 'decimal'), field('ruleId', '指定历史规则编号（选填）', 'number')],
      form: row ? { id: row.id, version: row.version, scheduleId: row.schedule_id, sourceRef: row.source_ref,
        courseName: row.course_name, coachName: row.coach_name, courseType: row.course_type, price: money(row.price_fen),
        ruleId: row.rule_id, startAt: row.start_at, ...dateFields('start', row.start_at) }
        : { courseType: 'GROUP', startDate: today }
    };
    case 'rule': return {
      title: '发布收费规则',
      fields: [pick('courseType', '课型', types), ...dayTime('effective', '生效'),
        field('freeMinutes', '提前多少分钟可免费取消', 'number'), pick('lateKind', '临时取消', feeKinds),
        field('lateValue', '临时取消金额（元）或百分比（%）', 'decimal'),
        pick('absentKind', '爽约', feeKinds), field('absentValue', '爽约金额（元）或百分比（%）', 'decimal')],
      form: { courseType: 'GROUP', effectiveDate: today, effectiveTime: '00:00', freeMinutes: 120, lateKind: 'NONE', absentKind: 'NONE' }
    };
    case 'mode': return {
      title: '更改余额使用范围',
      hint: '当前已入账余额 ¥' + money(wallet.balance_fen) + '。切换为按课型分开时，请把当前余额分配到小班和私教。',
      fields: [pick('mode', '余额使用范围', modes), ...(wallet.mode === 'SHARED' ? allocationFields.map(item => ({ ...item, when: 'split' })) : []), reason],
      form: { mode: wallet.mode }
    };
    case 'default': return {
      title: '设置新会员默认余额范围', fields: [pick('mode', '余额使用范围', modes), reason], form: { mode: data.defaultMode }
    };
    case 'import': return {
      title: '设置逐笔补录旧账',
      hint: '适合有逐笔收款和扣费凭据的会员。请在核实入账前设置截止时点。',
      fields: [...dayTime('cutoff', '旧账截止'), reason],
      form: { mode: 'REPLAY', ...dateFields('cutoff', wallet.import_mode === 'REPLAY' ? wallet.cutoff_at : null) }
    };
    case 'grant': return {
      title: '设置临时取消费授权',
      fields: [field('operatorId', '后台账号编号', 'number'), pick('enabled', '处理权限', [[1, '授予'], [0, '撤销']])],
      form: { operatorId: row?.operator_id || '', enabled: row?.enabled ? 0 : 1 }
    };
    default: throw new Error('表单类型无效');
  }
}

function payload(kind, form, wallet) {
  const timing = key => clock(form[key + 'Date'], form[key + 'Time']);
  switch (kind) {
    case 'recharge':
    case 'opening':
    case 'refund':
    case 'receipt': return { id: form.id, version: form.version, kind: form.kind, amount: form.amount, channel: form.channel,
      externalRef: form.externalRef, originalId: number(form.originalId), businessAt: timing('business'), reason: form.reason,
      duplicateConfirmed: Number(form.duplicateConfirmed) === 1, allocation: wallet.mode === 'SPLIT' ? allocation(form) : null };
    case 'lesson': return { id: form.id, version: form.version, sessionId: number(form.sessionOverride || form.sessionId),
      bookingId: number(form.bookingId), outcome: form.outcome, cancelledAt: cancelledOutcomes.includes(form.outcome) ? timing('cancelled') : null,
      decision: form.decision, manualAmount: form.decision === 'MANUAL' || recordedDecisions.includes(form.decision) ? form.manualAmount : null,
      reason: form.reason, duplicateConfirmed: Number(form.duplicateConfirmed) === 1 };
    case 'session': {
      const entered = timing('start');
      const original = form.startAt?.replace(' ', 'T');
      const startAt = original && entered && original.slice(0, 16) === entered.slice(0, 16) ? original : entered;
      return { id: form.id, version: form.version, scheduleId: number(form.scheduleId), sourceRef: form.sourceRef,
        courseName: form.courseName, coachName: form.coachName, courseType: form.courseType, price: form.price,
        ruleId: number(form.ruleId), startAt };
    }
    case 'rule': return { ...form, effectiveAt: timing('effective'), freeMinutes: number(form.freeMinutes),
      lateValue: feeValue(form.lateKind, form.lateValue), absentValue: feeValue(form.absentKind, form.absentValue) };
    case 'mode': return { mode: form.mode, version: wallet.version, allocation: form.mode === 'SPLIT' ? allocation(form) : null, reason: form.reason };
    case 'import': return { mode: form.mode, cutoffAt: timing('cutoff'), reason: form.reason };
    case 'grant': return { enabled: Number(form.enabled) === 1 };
    default: return form;
  }
}

function visibleFields(fields, form, duplicate) {
  const show = {
    split: form.mode === 'SPLIT',
    cancelled: cancelledOutcomes.includes(form.outcome),
    manual: form.decision === 'MANUAL',
    recordedAmount: recordedDecisions.includes(form.decision),
    duplicate
  };
  return fields.filter(item => !item.when || show[item.when]);
}

function validateOpening(body) {
  const valid = amount => /^[0-9]{1,7}(\.[0-9]{1,2})?$/.test(amount);
  if (!valid(body.amount)) throw new Error('请填写期初余额，最多保留两位小数');
  if (Number(body.amount) > 1_000_000) throw new Error('期初余额超过单笔上限');
  if (!body.businessAt) throw new Error('请选择余额核对的日期和时间');
  const reason = body.reason?.trim();
  if (!reason || reason.length > 255) throw new Error('请填写 1 至 255 字的余额核实依据');
  if (body.allocation) {
    const { group, privateAmount } = body.allocation;
    const fen = amount => Math.round(Number(amount) * 100);
    if ([group, privateAmount].some(value => value && !valid(value))
        || fen(group) + fen(privateAmount) !== fen(body.amount)) {
      throw new Error('小班和私教分配金额之和须等于期初余额');
    }
  }
}

module.exports = { definition, payload, visibleFields, validateOpening };
