const u = require('../common');

const sections = [
  { title: '门店日常', items: [
    ['members', '会员档案', '查找会员、建档和维护资料', 'member:view'],
    ['bookings', '预约与签到', '查系统预约、代签到或调整预约', 'member:view'],
    ['schedules', '课程排班', '按日期看场次和预约名单', 'schedule:view']
  ] },
  { title: '待办与对账', items: [
    ['studio-ledger', '账本待办', '核实收退款、历史课和待结算课费'],
    ['ledger-overview', '经营账本', '查看课程消费、净收款和上课人次', 'statistics:view'],
    ['late-fees', '临时取消费', '核对单笔费用，决定收取或免收'],
    ['proposals', '课程申请审核', '审核教练提交的新课程申请', 'manager:course:proposal:view'],
    ['reviews', '请假与调课审核', '处理教练的请假和排班变更', 'schedule:update']
  ] },
  { title: '课程与教练', settings: true, items: [
    ['courses', '课程目录', '维护课程名称、分类和上架状态', 'course:view'],
    ['coaches', '教练档案', '维护教练资料和启用状态', 'coach:manage'],
    ['classrooms', '教室资料', '维护教室类型和人数上限', 'classroom:manage']
  ] },
  { title: '小程序展示', settings: true, items: [
    ['studio', '门店信息', '修改会员看到的地址、电话和营业时间', 'coach:home:view'],
    ['banners', '首页轮播图', '管理首页图片及其链接', 'coach:home:view'],
    ['hot-courses', '首页推荐课程', '选择和排序首页展示的课程', 'coach:home:view'],
    ['team-coaches', '首页教练展示', '选择和排序首页展示的教练', 'coach:home:view']
  ] },
  { title: '系统设置', settings: true, items: [
    ['accounts', '后台账号与密码', '维护后台账号或修改本人密码', 'dashboard:view'],
    ['rules', '预约规则', '设置预约时间范围、名额限制和取消约定', 'system:manage'],
    ['logs', '操作日志', '查看后台修改记录', 'system:manage']
  ] }
];

function visibleSections(ledger, lateFees) {
  const available = { 'studio-ledger': ledger, 'late-fees': lateFees };
  return sections.map(section => ({
    title: section.title,
    settings: Boolean(section.settings),
    items: section.items.filter(([page, , , permission]) => permission ? u.can(permission) : available[page])
      .map(([page, label, description]) => {
        if (page === 'accounts' && !u.isAdmin()) return { page, label: '修改登录密码', description: '修改当前账号的密码' };
        if (page === 'bookings' && !u.isAdmin()) return { page, label, description: '查看系统预约和签到情况' };
        return { page, label, description };
      })
  })).filter(section => section.items.length);
}

Page({
  data: { name: '', groups: [], settingsOpen: false, hasSettings: false, summary: null, summaryUnavailable: false, busy: false, error: '' },
  onShow() {
    if (u.guard()) return this.load();
  },
  onPullDownRefresh() { return this.load(); },
  load() {
    return u.run(this, async () => {
      const token = wx.getStorageSync('adminToken');
      const me = await u.api.get('/api/auth/me');
      if (wx.getStorageSync('adminToken') !== token) return;
      if (!me?.roles?.some(role => role === 'ADMIN' || role === 'STORE_MANAGER')) {
        this.logout();
        return;
      }
      wx.setStorageSync('adminRoles', me.roles);
      wx.setStorageSync('adminPerms', me.permissions || []);
      wx.setStorageSync('adminName', me.name);
      const groups = visibleSections(false, false);
      this.setData({ name: me.name, groups, hasSettings: groups.some(group => group.settings), summary: null, summaryUnavailable: false });

      const quiet = { quiet: true };
      const [capabilities, summary] = await Promise.all([
        u.api.get('/api/capabilities', undefined, quiet).catch(() => null),
        u.can('dashboard:view') ? u.api.get('/api/admin/workspace/summary', undefined, quiet).catch(() => null) : null
      ]);
      if (wx.getStorageSync('adminToken') !== token) return;
      this.setData({ summary, summaryUnavailable: u.can('dashboard:view') && !summary });
      if (!capabilities?.studioLedger) return;
      this.setData({ groups: visibleSections(u.isAdmin(), false) });
      const lateFees = await u.api.get('/api/studio/late-fees/access', undefined, quiet).catch(() => false);
      if (wx.getStorageSync('adminToken') === token && lateFees === true) {
        this.setData({ groups: visibleSections(u.isAdmin(), true) });
      }
    });
  },
  toggleSettings() { this.setData({ settingsOpen: !this.data.settingsOpen }); },
  go(e) {
    const page = e.currentTarget.dataset.page;
    if (this.data.groups.some(group => group.items.some(entry => entry.page === page))) {
      wx.navigateTo({ url: '/admin/' + page + '/' + page });
    }
  },
  logout() {
    require('../../utils/request').clearAuth();
    wx.reLaunch({ url: '/pages/login/login' });
  }
});
