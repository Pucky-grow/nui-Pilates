const api = require("./api");
const labels = { BOOKED: "已预约", SIGNED_IN: "已签到", COMPLETED: "已完成", NO_SHOW: "未到课", CANCELLED: "已取消", REFUNDED: "已退款", SCHEDULED: "待开课", FINISHED: "已结束", ACTIVE: "启用", DISABLED: "停用", EXPIRED: "已到期", APPROVED: "已获资格", PENDING: "待处理", REJECTED: "已拒绝", ON_SHELF: "上架", OFF_SHELF: "下架" };
function isAdmin() {
  return (wx.getStorageSync("adminRoles") || []).includes("ADMIN");
}
function can(perm) {
  return isAdmin() || (wx.getStorageSync("adminPerms") || []).includes(perm);
}
function guard(perm) {
  if (!wx.getStorageSync("adminToken")) {
    wx.reLaunch({ url: "/pages/login/login" });
    return false;
  }
  if (perm && !can(perm)) {
    wx.showToast({ title: "当前账号无此权限", icon: "none" });
    return false;
  }
  return true;
}
async function run(page, work) {
  if (page.data.busy) {
    wx.stopPullDownRefresh();
    return;
  }
  page.setData({ busy: true, error: "" });
  try {
    return await work();
  } catch (err) {
    page.setData({ error: err && (err.message || err.errMsg) || "操作失败，请重试" });
  } finally {
    page.setData({ busy: false });
    wx.stopPullDownRefresh();
  }
}
function reason(title, content) {
  return new Promise((resolve) => wx.showModal({ title, content, editable: true, placeholderText: "填写操作原因（必填）", success: (r) => {
    if (!r.confirm) return resolve(null);
    const value = (r.content || "").trim();
    if (!value || value.length > 255) {
      wx.showToast({ title: "请填写1至255字原因", icon: "none" });
      return resolve(null);
    }
    resolve(value);
  }, fail: () => resolve(null) }));
}
function confirm(title, content) {
  return new Promise((resolve) => wx.showModal({ title, content, success: (r) => resolve(r.confirm), fail: () => resolve(false) }));
}
function success() {
  wx.showToast({ title: "已保存", icon: "success" });
}
function copyText(value) {
  if (typeof value !== "string" || !value) return;
  wx.setClipboardData({
    data: value,
    fail: () => wx.showToast({ title: "复制失败，请长按文字复制", icon: "none" })
  });
}
function status(value) {
  return labels[value] || value;
}
function date() {
  const d = new Date(Date.now() + 8 * 36e5);
  return d.toISOString().slice(0, 10);
}
const option = (value, label) => ({ value, label });
module.exports = { api, isAdmin, can, guard, run, reason, confirm, success, copyText, status, date, option };
