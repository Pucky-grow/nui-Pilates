// services/api.js —— 后端接口统一出口
const http = require('../utils/request');

module.exports = {
  // 认证
  wxLogin: (code) => http.post('/api/auth/wx-login', { code }),
  bindPhone: (data) => http.post('/api/auth/bind-phone', data),
  sendPhoneCode: (data) => http.post('/api/auth/phone/send-code', data),
  bindPhoneByCode: (data) => http.post('/api/auth/phone/bind', data),
  myProfile: () => http.get('/api/auth/me'),
  updateProfile: (data) => http.put('/api/auth/profile', data),
  // 首页
  bookingRules: () => http.get('/api/booking-rules'),
  home: () => http.get('/api/home'),
  banners: () => http.get('/api/banners'),
  studio: () => http.get('/api/studio'),
  // 课程
  categories: () => http.get('/api/course-categories'),
  courses: (params) => http.get('/api/courses', params),
  courseDetail: (id) => http.get('/api/courses/' + id),
  courseSchedules: (id) => http.get('/api/courses/' + id + '/schedules'),
  schedules: (params) => http.get('/api/course-schedules', params),
  scheduleDetail: (id) => http.get('/api/course-schedules/' + id),
  // 教练
  coaches: () => http.get('/api/coaches'),
  coachDetail: (id) => http.get('/api/coaches/' + id),
  coachSchedules: (id) => http.get('/api/coaches/' + id + '/schedules'),
  coachReviews: (id) => http.get('/api/coaches/' + id + '/reviews'),
  // 课包
  packages: () => http.get('/api/packages'),
  packageDetail: (id) => http.get('/api/packages/' + id),
  myPackages: (status) => http.get('/api/my/packages', { status }),
  creditLogs: (id) => http.get('/api/my/packages/' + id + '/logs'),
  // 订单与支付
  createOrder: (data) => http.post('/api/orders', data),
  myOrders: (params) => http.get('/api/my/orders', params),
  orderDetail: (id) => http.get('/api/orders/' + id),
  cancelOrder: (id) => http.post('/api/orders/' + id + '/cancel'),
  createPay: (orderId) => http.post('/api/payment/wechat/create?orderId=' + orderId),
  mockPay: (orderId) => http.post('/api/payment/mock-pay?orderId=' + orderId),
  // 预约
  createBooking: (data) => http.post('/api/bookings', data),
  cancelBooking: (id) => http.del('/api/bookings/' + id),
  bookingDetail: (id) => http.get('/api/bookings/' + id),
  myBookings: (status) => http.get('/api/my/bookings', { status }),
  checkin: (bookingId, code) => http.post('/api/attendance/check-in', { bookingId: Number(bookingId), code }),
  // 候补
  joinWaitlist: (scheduleId) => http.post('/api/waitlist/' + scheduleId + '/join'),
  leaveWaitlist: (scheduleId) => http.post('/api/waitlist/' + scheduleId + '/leave'),
  myWaitlist: () => http.get('/api/my/waitlist'),
  // 训练
  myTrainingRecords: () => http.get('/api/training-records'),
  trainingStats: () => http.get('/api/training-records/stats'),
  myAssessments: () => http.get('/api/assessments'),
  assessmentDetail: (id) => http.get('/api/assessments/' + id),
  measurementTrend: () => http.get('/api/measurements/trend'),
  // 评价
  createReview: (data) => http.post('/api/reviews', data),
  myReviews: () => http.get('/api/my/reviews'),
  courseReviews: (id) => http.get('/api/courses/' + id + '/reviews'),
  // 营销
  coupons: () => http.get('/api/coupons'),
  myCoupons: (status) => http.get('/api/my/coupons', { status }),
  receiveCoupon: (id) => http.post('/api/coupons/' + id + '/receive'),
  myPoints: () => http.get('/api/my/points'),
  pointsRecords: () => http.get('/api/my/points/records'),
  // 内容
  articleCategories: () => http.get('/api/article-categories'),
  articles: (params) => http.get('/api/articles', params),
  articleDetail: (id) => http.get('/api/articles/' + id),
  likeArticle: (id) => http.post('/api/articles/' + id + '/like'),
  favoriteArticle: (id) => http.post('/api/articles/' + id + '/favorite'),
  // 消息
  notifications: () => http.get('/api/notifications'),
  unreadCount: () => http.get('/api/notifications/unread-count'),
  readNotification: (id) => http.post('/api/notifications/' + id + '/read'),
  // 意见反馈
  submitFeedback: (data) => http.post('/api/feedback', data),
  myFeedback: () => http.get('/api/my/feedback'),
  // 邀请好友
  myInviteCode: () => http.get('/api/invitation/my-code'),
  bindInvite: (inviteCode) => http.post('/api/invitation/bind', { inviteCode })
};
