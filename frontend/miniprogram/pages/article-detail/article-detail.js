// pages/article-detail/article-detail.js —— 文章详情
const api = require('../../services/api');
const http = require('../../utils/request');
Page({
  data: { article: null, liked: false, favorited: false },
  onLoad(options) {
    this.id = options.id;
    http.login().then(() => {
      api.articleDetail(this.id).then(d => this.setData({ article: d.article, liked: d.liked, favorited: d.favorited }));
    });
  },
  like() {
    if (this.data.liked) return;
    api.likeArticle(this.id).then(() => this.setData({ liked: true }));
  },
  favorite() {
    api.favoriteArticle(this.id).then(() => this.setData({ favorited: true }));
  }
});
