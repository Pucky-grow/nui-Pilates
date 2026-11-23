import http from './request';

export const root = '/api/admin/studio-ledger';
export const money = value => (Number(value || 0) / 100).toFixed(2);
export const displayTime = value => String(value || '').replace('T', ' ').slice(0, 16);
const labels = {
  SHARED: '共用余额', SPLIT: '分开余额', GENERAL: '通用余额', GROUP: '小班课', PRIVATE: '私教课',
  TOPUP: '实收充值', OPENING: '期初余额', REFUND: '实际线下退款', COURSE: '课程扣费', CANCELLATION: '临时取消费',
  TRANSFER: '账户划转', REVERSAL: '冲正', ATTENDED: '已上课', NO_SHOW: '爽约', LATE_CANCEL: '临时取消',
  TIMELY_CANCEL: '按时取消', STORE_CANCEL: '门店取消', UNKNOWN: '结果待确认', PENDING_VERIFY: '待核实',
  VERIFIED: '已核实', VERIFIED_COVERED: '期初已覆盖', RETURNED: '已退回', UNPOSTED: '未入账', POSTED: '已扣费',
  NO_WALLET_DEBIT: '无余额扣费', WAITING_BALANCE: '已核实，待结算', REVERSED: '已冲正', WAIVED: '已免收',
  ELIGIBLE: '待决定', ISSUED: '待申请', REQUESTED: '待核对', CLAIMED: '已绑定', REVOKED: '已撤销', EXPIRED: '已过期'
};
export const label = value => labels[value] || value || '';

export function commandSender() {
  const pending = new Map();
  return async (url, body) => {
    const fingerprint = url + JSON.stringify(body);
    if (!pending.has(fingerprint)) {
      pending.set(fingerprint, globalThis.crypto?.randomUUID?.() ||
        'ledger_' + Date.now().toString(36) + '_' + Math.random().toString(36).slice(2));
    }
    const result = await http.post(url, body, { headers: { 'X-Studio-Command': pending.get(fingerprint) } });
    pending.delete(fingerprint);
    return result;
  };
}
