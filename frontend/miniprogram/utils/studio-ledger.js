const labels = {
  SHARED: '共用余额', SPLIT: '分开余额', GENERAL: '通用余额', GROUP: '小班课', PRIVATE: '私教课',
  ATTENDED: '已上课', NO_SHOW: '爽约', TIMELY_CANCEL: '按时取消', LATE_CANCEL: '临时取消', STORE_CANCEL: '门店取消', UNKNOWN: '结果待确认',
  PENDING_VERIFY: '待核实', VERIFIED: '已核实', VERIFIED_COVERED: '期初已覆盖', RETURNED: '已退回',
  UNPOSTED: '未入账', POSTED: '已扣费', NO_WALLET_DEBIT: '无余额扣费', WAITING_BALANCE: '已核实，待结算', REVERSED: '已冲正',
  ELIGIBLE: '待决定', WAIVED: '已免收', OPENING_COVERED: '期初已覆盖', PACKAGE: '已由课包处理',
  WAIVE: '免收', EXTERNAL: '已另付', RECORD_ONLY: '仅补录', BOOKING_TERMS: '由原预约处理',
  TOPUP: '充值', OPENING: '期初余额', REFUND: '线下退款', COURSE: '课程扣费', CANCELLATION: '临时取消费',
  REVERSAL: '冲正', TRANSFER: '账户划转', ISSUED: '待申请', REQUESTED: '待核对', CLAIMED: '已绑定', REVOKED: '已撤销', EXPIRED: '已过期'
};
const label = value => labels[value] || value || '';
const money = fen => (Number(fen || 0) / 100).toFixed(2);
const displayTime = value => String(value || '').replace('T', ' ').slice(0, 16);
const clock = (day, time) => day && time ? day + 'T' + time + ':00' : null;
const key = () => 'ledger_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2) + Math.random().toString(36).slice(2);

function commands(api) {
  const attempts = new Map();
  return (url, body) => {
    const fingerprint = url + JSON.stringify(body);
    if (!attempts.has(fingerprint)) attempts.set(fingerprint, key());
    return api.post(url, body, attempts.get(fingerprint)).then(result => {
      attempts.delete(fingerprint);
      return result;
    });
  };
}

function present(row) {
  return {
    ...row, typeText: label(row.course_type || row.bucket), outcomeText: label(row.outcome),
    stateText: label(row.settlement || row.state), kindText: label(row.kind),
    basisText: row.settlement === 'NO_WALLET_DEBIT' ? label(row.settlement_basis) : '',
    timeText: displayTime(row.start_at || row.business_at || row.expires_at),
    amountText: money(row.amount_fen ?? row.delta_fen ?? row.late_fen ?? row.price_fen),
    afterText: money(row.after_fen), reportedText: row.reported_fen == null ? '' : money(row.reported_fen)
  };
}

function cancellationNotice(booking) {
  const terms = booking?.cancellationTerms;
  if (!terms?.lateFeeFen) return '';
  return ' ' + displayTime(terms.cutoffAt) + ' 后取消，按约定可能扣 ¥' + money(terms.lateFeeFen) + '。';
}

module.exports = { label, money, displayTime, clock, commands, present, cancellationNotice };
