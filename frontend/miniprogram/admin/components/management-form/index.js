function imageContext() {
  const g = getApp().globalData;
  return JSON.stringify([g.authEpoch || 0, g.environment || '', g.transport || 'http', g.baseUrl || '', g.cloudEnvironment || '', g.cloudService || '']);
}
async function prepareNativeImage(file) {
  const limit = 500 * 1024;
  const size = path => new Promise((resolve, reject) => {
    const fs = typeof wx.getFileSystemManager === 'function' && wx.getFileSystemManager();
    if (!fs || typeof fs.stat !== 'function') { reject({clientImagePreparationError: true}); return; }
    fs.stat({path, success: result => resolve(result.stats && result.stats.size),
      fail: () => reject({clientImagePreparationError: true})});
  });
  let path = file.tempFilePath;
  for (const width of [1024, 720, 480]) {
    const bytes = await size(path);
    if (Number.isSafeInteger(bytes) && bytes > 0 && bytes <= limit) return path;
    path = await new Promise((resolve, reject) => {
      const failed = () => reject({clientImagePreparationError: true});
      if (typeof wx.compressImage !== 'function') { failed(); return; }
      wx.compressImage({src: file.tempFilePath, quality: 60, compressedWidth: width,
        success: image => image.tempFilePath ? resolve(image.tempFilePath) : failed(), fail: failed});
    });
  }
  const bytes = await size(path);
  if (Number.isSafeInteger(bytes) && bytes > 0 && bytes <= limit) return path;
  throw {clientImagePreparationError: true};
}
Component({
  properties: { fields: { type: Array, value: [] }, value: { type: Object, value: {} }, disabled: { type: Boolean, value: false } },
  data: { rows: [], uploading: false, imageErrors: {} },
  observers: { "fields,value": function(fields, value) {
    this.setData({ rows: (fields || []).map((f) => {
      const v = value[f.key];
      return { ...f, current: v === void 0 || v === null ? "" : v, index: Math.max(0, (f.options || []).findIndex((o) => String(o.value) === String(v))), selected: (f.options || []).find((o) => String(o.value) === String(v))?.label || (v === undefined || v === null || v === '' ? '请选择' : '当前值不在可选范围') };
    }) });
  } },
  methods: {
    input(e) {
      this.triggerEvent("change", { key: e.currentTarget.dataset.key, value: e.detail.value });
    },
    select(e) {
      const field = this.data.rows.find((f) => f.key === e.currentTarget.dataset.key);
      this.triggerEvent("change", { key: field.key, value: field.options[Number(e.detail.value)].value });
    },
    image(e) {
      if (this.data.disabled) return;
      const context = imageContext();
      const key = e.currentTarget.dataset.key;
      const field = (this.data.rows || []).find(f => f.key === key) || {};
      if (this.data.uploading || field.readonly) return;
      wx.chooseMedia({ count: 1, mediaType: ["image"], sizeType: ["compressed"], success: (r) => {
        if (!r.tempFiles || !r.tempFiles.length) return;
        this.setData({ uploading: true });
        this.triggerEvent("uploadstate", {uploading: true});
        const file = r.tempFiles[0];
        const prepared = getApp().globalData.transport === 'cloudrun'
          ? prepareNativeImage(file) : Promise.resolve(file.tempFilePath);
        return prepared.then(filePath => {
          if (context !== imageContext()) throw {clientImageContextError: true};
          return require("../../api").upload("/api/upload", filePath, { purpose: field.uploadPurpose || "PUBLIC_ASSET", ...(field.ownerId ? { ownerId: field.ownerId } : {}) }); })
          .then(url => {
            if (context !== imageContext()) throw {clientImageContextError: true};
            this.setData({ imageErrors: {...this.data.imageErrors, [key]: false} });
            this.triggerEvent("change", {key, value: /^https?:\/\//.test(url) ? url : getApp().globalData.baseUrl + url});
          }).catch(error => {
            if (error && error.clientImageContextError) wx.showToast({title:'登录状态已变化，请重新选择图片',icon:'none'});
            else if (error && error.clientImagePreparationError) wx.showToast({title:'图片压缩失败，请选择较小图片',icon:'none'});
          }).finally(() => {
            this.setData({ uploading: false });
            this.triggerEvent("uploadstate", {uploading: false});
          });
      } });
    },
    imageError(e) {
      this.setData({ imageErrors: {...this.data.imageErrors, [e.currentTarget.dataset.key]: true} });
    },
    imageLoaded(e) {
      this.setData({ imageErrors: {...this.data.imageErrors, [e.currentTarget.dataset.key]: false} });
    }
  }
});
