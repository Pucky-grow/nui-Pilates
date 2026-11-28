const { money, displayTime } = require('./studio-ledger');
const bucketNames = { GENERAL: '通用余额', GROUP: '小班余额', PRIVATE: '私教余额' };
const courseNames = { GROUP: '小班课', PRIVATE: '私教课' };

function walletAccounts(wallet) {
  if (!Array.isArray(wallet?.accounts) || wallet.accounts.length === 0) throw new Error('余额暂时无法读取');
  return wallet.accounts.map(({ bucket, balance_fen }) => ({
    bucket, name: bucketNames[bucket] || '可用余额', amount: '¥' + money(balance_fen)
  }));
}

function ledgerRows(rows, tab) {
  const groups = [];
  for (const row of rows) {
    const dateTime = (tab === 'entries' && row.posted_at) || row.start_at || row.business_at;
    const date = displayTime(dateTime).slice(0, 10) || '日期未记录';
    let group = groups[groups.length - 1];
    if (!group || group.date !== date) {
      group = { key: date + '-' + groups.length, date, rows: [] };
      groups.push(group);
    }
    group.rows.push(presentRow(row, tab));
  }
  return groups;
}

function presentRow(row, tab) {
  if (tab === 'entries') {
    const delta = Number(row.delta_fen);
    const titles = {
      TOPUP: '充值', OPENING: '转入原有余额', REFUND: '退款扣减余额',
      COURSE: '课程扣费', CANCELLATION: '取消费', TRANSFER: '余额调整',
      REVERSAL: delta > 0 ? '退回余额' : '撤销原入账'
    };
    const businessTime = displayTime(row.business_at);
    const postedTime = displayTime(row.posted_at);
    return {
      id: row.id,
      title: titles[row.kind] || '余额变动',
      detail: [bucketNames[row.bucket], '入账后余额 ¥' + money(row.after_fen)].filter(Boolean).join(' · '),
      note: row.reversed_by ? '此笔已撤销' : businessTime && businessTime.slice(0, 10) !== postedTime.slice(0, 10) ? '发生于 ' + businessTime : '',
      amount: Number.isFinite(delta) ? (delta < 0 ? '−' : delta > 0 ? '+' : '') + '¥' + money(Math.abs(delta)) : '',
      tone: delta > 0 ? 'credit' : ''
    };
  }

  const outcomes = { ATTENDED: '已上课', NO_SHOW: '未到课', TIMELY_CANCEL: '按时取消', LATE_CANCEL: '临时取消', STORE_CANCEL: '门店取消', UNKNOWN: '结果待确认' };
  const state = tab === 'history' ? row.settlement : row.state;
  const basis = { OPENING_COVERED: '已计入原有余额', PACKAGE: '已由课包抵扣', EXTERNAL: '已另付', RECORD_ONLY: '仅记录课程', BOOKING_TERMS: '见预约费用记录' };
  const states = {
    POSTED: '已扣费', WAITING_BALANCE: '待扣费', REVERSED: '已退回余额',
    WAIVED: '免收', NO_WALLET_DEBIT: basis[row.settlement_basis] || '未扣款'
  };
  const amount = tab === 'history' ? row.amount_fen : row.late_fen;
  const showsAmount = ['POSTED', 'WAITING_BALANCE', 'REVERSED'].includes(state) && Number(amount) > 0;
  return {
    id: row.id || row.booking_id,
    title: row.course_name || (tab === 'history' ? '门店补录课程' : '课程取消费'),
    detail: [displayTime(row.start_at).slice(11), row.coach_name, courseNames[row.course_type]].filter(Boolean).join(' · '),
    note: tab === 'history' ? outcomes[row.outcome] || '' : '',
    status: states[state] || '',
    amount: showsAmount ? '¥' + money(amount) : '',
    tone: state === 'WAITING_BALANCE' ? 'pending' : state === 'REVERSED' ? 'credit' : ''
  };
}

module.exports = { walletAccounts, ledgerRows };
