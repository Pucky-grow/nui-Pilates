// Admin credentials stay separate from member and coach credentials.
const transport = require('../utils/transport');
let pending = 0;
function begin() {
  if (pending++ === 0) wx.showLoading({ title: "加载中", mask: true });
}
function end() {
  pending = Math.max(0, pending - 1);
  if (!pending) wx.hideLoading();
}
function endpoint(path) {
  transport.settings();
  if (!wx.getStorageSync("adminToken")) throw { code: 40100, message: "请先登录管理员账号" };
  return path.startsWith("/admin/") ? "/api" + path : path;
}
function failure(err, quiet = false) {
  if (err && err.code === 40100) {
    require("../utils/request").clearAuth();
    wx.reLaunch({ url: "/pages/login/login" });
  }
  if (!quiet) wx.showToast({ title: err && err.message || "请求失败，请重试", icon: "none" });
  return err;
}
function network(err) {
  const detail = err && err.errMsg || "request:fail";
  const base = getApp().globalData.baseUrl || "";
  const local = /^https?:\/\/(127\.0\.0\.1|localhost)(:|\/|$)/.test(base);
  return { message: err && err.message || (local ? "本地服务连接失败，真机需配置可访问的测试地址" : "服务连接失败，请检查测试地址或网络"), errMsg: detail };
}
function request(method, path, data, requestKey, { quiet = false } = {}) {
  return new Promise((resolve, reject) => {
    let url;
    try {
      url = endpoint(path);
    } catch (err) {
      reject(failure(err, quiet));
      return;
    }
    const clean = method === "GET" && data ? Object.fromEntries(Object.entries(data).filter(([, v]) => v !== "" && v !== null && v !== void 0)) : data;
    if (!quiet) begin();
    transport.request({
      path: url,
      method,
      data: clean,
      timeout: 15e3,
      header: { "Content-Type": "application/json", Authorization: "Bearer " + wx.getStorageSync("adminToken"),
        ...(requestKey ? { "X-Studio-Command": requestKey } : {}) },
      success(res) {
        const body = res.data;
        if (body && body.code === 0) resolve(body.data);
        else reject(failure(body && typeof body === "object" ? body : { message: "服务响应异常（HTTP " + res.statusCode + "）" }, quiet));
      },
      fail(err) {
        reject(failure(network(err), quiet));
      },
      complete: () => { if (!quiet) end(); }
    });
  });
}
function upload(path, filePath, formData = {}) {
  return new Promise((resolve, reject) => {
    let url;
    try {
      url = endpoint(path);
    } catch (err) {
      reject(failure(err));
      return;
    }
    begin();
    transport.upload({
      path: url,
      filePath,
      name: "file",
      formData,
      header: { Authorization: "Bearer " + wx.getStorageSync("adminToken") },
      success(res) {
        let body;
        try {
          body = JSON.parse(res.data);
        } catch (_) {
          body = { message: "上传服务响应异常" };
        }
        if (body.code === 0) resolve(body.data.previewUrl || body.data.url);
        else reject(failure(body));
      },
      fail(err) {
        reject(failure(network(err)));
      },
      complete: end
    });
  });
}
module.exports = { get: (p, d, options) => request("GET", p, d, undefined, options), post: (p, d, key, options) => request("POST", p, d, key, options), put: (p, d) => request("PUT", p, d), del: (p, d) => request("DELETE", p, d), upload };
