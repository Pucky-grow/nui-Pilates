// pages/article/article.js —— 优仪课堂文章列表
const api = require('../../services/api');
Page({
  data: { categories: [], articles: [], categoryId: '' },
  onLoad() {
    api.articleCategories().then(list => this.setData({ categories: list }));
    this.load();
  },
  load() {
    api.articles({ categoryId: this.data.categoryId || undefined, size: 50 }).then(d => this.setData({ articles: d.list }));
  },
  onCategory(e) { this.setData({ categoryId: e.currentTarget.dataset.v }); this.load(); },
  goDetail(e) { wx.navigateTo({ url: '/pages/article-detail/article-detail?id=' + e.currentTarget.dataset.id }); }
});
