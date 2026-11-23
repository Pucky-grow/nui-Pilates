// utils/util.js
function formatDate(d) {
  if (!d) return '';
  return d.substring(0, 10);
}
function formatDateTime(dt) {
  if (!dt) return '';
  return dt.replace('T', ' ').substring(0, 16);
}
function weekday(dateStr) {
  const w = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
  return w[new Date(dateStr.substring(0, 10) + 'T00:00:00Z').getUTCDay()];
}
function studioDate(now = Date.now()) { return new Date(Number(now) + 8 * 3600000).toISOString().slice(0, 10); }
function localDate(offsetDays = 0, now = Date.now()) {
  return new Date(Date.parse(studioDate(now) + 'T00:00:00Z') + offsetDays * 86400000).toISOString().slice(0, 10);
}
function weekRange(next = false, now = Date.now()) {
  const day = new Date(studioDate(now) + 'T00:00:00Z').getUTCDay() || 7;
  const monday = 1 - day + (next ? 7 : 0);
  return { fromDate: localDate(monday, now), toDate: localDate(monday + 6, now) };
}
function attendanceSourceLabel(source) {
  return { ONSITE_QR: '现场扫码', USER_SCAN: '会员签到（旧记录）', COACH: '教练代签', STAFF: '前台代签', ADMIN: '管理员代签' }[source] || '';
}
module.exports = { formatDate, formatDateTime, weekday, localDate, studioDate, weekRange, attendanceSourceLabel };
