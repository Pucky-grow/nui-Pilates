const u = require('../common');
const display = require('../../utils/studio-ledger');
const forms = require('./forms');
const root = '/api/admin/studio-ledger';
const isReceipt = editor => ['receipt', 'recharge', 'refund', 'opening'].includes(editor);
const sameId = (left, right) => String(left) === String(right);
const memberTabs = ['lessons', 'receipts', 'entries'];
const workspaceTabs = ['review', 'sessions', 'rules', 'grants'];
const profileUrl = userId => '/admin/member-detail/member-detail?id=' + encodeURIComponent(userId);
const previewFields = ['sessionId', 'sessionOverride', 'bookingId', 'outcome', 'decision', 'manualAmount', 'cancelledDate', 'cancelledTime'];
const fullTime = value => {
  const timestamp = String(value || '').replace(' ', 'T');
  return timestamp.length === 16 ? timestamp + ':00' : timestamp.slice(0, 19);
};

function presentDebit(preview, readyTitle = '核实后可扣') {
  const { status, amountFen, balanceFen, balanceAtFen, basis, bucket, lessonAt, shortageFen } = preview;
  const blocked = status !== 'READY' && status !== 'NO_WALLET_DEBIT';
  if (status === 'NO_WALLET_DEBIT') {
    const label = display.label(basis);
    return { title: '本节不扣余额', detail: label === basis ? '' : label, blocked };
  }
  const amount = '¥' + display.money(amountFen);
  const balances = display.label(bucket) + '当前 ¥' + display.money(balanceFen) +
    (balanceAtFen == null ? '' : ' · 上课时点 ¥' + display.money(balanceAtFen));
  const messages = {
    HISTORICAL_BALANCE_LOW: '上课时点余额不足，请核对该时间之前的真实收款和入账记录。',
    LATER_HISTORICAL_BALANCE_LOW: '补扣会使后续某笔历史余额不足，请核对这段旧账的收支。',
    CURRENT_BALANCE_LOW: '当前账户余额不足，请核对该账户的已入账金额。'
  };
  return { title: blocked ? '应扣 ' + amount + '，暂不能扣款' : readyTitle + ' ' + amount,
    detail: display.displayTime(lessonAt) + ' · ' + balances,
    advice: blocked ? messages[status] + ' 可用余额缺口 ¥' + display.money(shortageFen) + '。' : '', blocked };
}

Page({
  data: {
    memberId: '', member: {}, wallet: {}, accounts: [], defaultMode: 'SHARED', busy: false, error: '',
    tab: 'review', rows: [], pendingReceipts: [], more: false, editor: '', fields: [], form: {},
    notice: '', needsRefresh: false, editorHint: '', saveText: '保存', lessonCheck: null, settingsOpen: false
  },
  onLoad(q) {
    if (!u.guard() || !u.isAdmin()) return;
    this.send = display.commands(u.api);
    const memberTab = memberTabs.includes(q.tab) ? q.tab : 'lessons';
    this.setData({ memberId: q.id || '', tab: q.id ? memberTab : 'review', today: u.date() });
    return this.load().then(() => {
      if (q.create === '1') return this.edit('member');
      if (q.tab === 'invites' && this.data.member.user_id) {
        wx.redirectTo({ url: profileUrl(this.data.member.user_id) + '&bind=1' });
      }
    });
  },
  onShow() {
    if (this.loaded && !this.data.editor) this.load();
    this.loaded = true;
  },
  onPullDownRefresh() { this.load(); },
  onUnload() { this.cancelLessonCheck(); },
  load() { return u.run(this, () => this.read(false)); },
  next() { return u.run(this, () => this.read(true)); },
  tab(e) {
    return this.selectTab(e.currentTarget.dataset.tab);
  },
  selectTab(tab) {
    if (this.data.busy || !(this.data.memberId ? memberTabs : workspaceTabs).includes(tab)) return;
    this.setData({ tab, rows: [], more: false, notice: '', settingsOpen: false });
    return this.load();
  },
  toggleSettings() {
    if (!this.data.busy && !this.data.needsRefresh) this.setData({ settingsOpen: !this.data.settingsOpen });
  },
  async read(append) {
    const { memberId, tab, rows } = this.data;
    const path = memberId ? root + '/members/' + memberId : root;
    if (memberId) {
      const { member, wallet } = await u.api.get(path);
      const accounts = wallet.accounts.map(row => ({ ...row, title: display.label(row.bucket), amountText: display.money(row.balance_fen) }));
      const importModeText = { REPLAY: '逐笔补录', OPENING: '期初余额' }[wallet.import_mode] || '未设置旧账方式';
      this.setData({ member, wallet, accounts, modeText: wallet.mode === 'SPLIT' ? '按课型分开' : '小班和私教共用',
        importModeText, importCutoffText: display.displayTime(wallet.cutoff_at) });
    }
    if (tab === 'review') {
      const { lessons, receipts } = await u.api.get(root + '/review');
      this.setData({ rows: lessons.map(row => this.present(row, tab)), pendingReceipts: receipts.map(display.present), more: false, needsRefresh: false });
      return;
    }
    const route = tab === 'grants' ? 'late-fee-grants' : tab;
    const data = append ? { before: rows[rows.length - 1]?.id } : {};
    const records = await u.api.get(path + '/' + route, data);
    this.setData({ rows: (append ? rows : []).concat(records.map(row => this.present(row, tab))),
      more: records.length === 30 && tab !== 'grants', needsRefresh: false });
  },
  present(row, tab) {
    const item = display.present(row);
    if (row.state === 'PENDING_VERIFY') item.stateText = '待核实';
    if (row.state === 'RETURNED') item.stateText = '已退回';
    if (tab === 'review' && row.settlement === 'UNPOSTED') item.stateText = '待核实';
    if (tab === 'receipts' && row.state === 'VERIFIED') item.stateText = '已入账';
    if (tab === 'entries') item.postedText = display.displayTime(row.posted_at);
    if (tab === 'lessons' && row.manual_fen != null) {
      item.originalFeeText = display.money(row.manual_fen);
    }
    return item;
  },
  showEditor(e) {
    const { kind, id } = e.currentTarget.dataset;
    const row = id == null ? null : this.data.rows.find(r => sameId(r.id || r.operator_id, id));
    return this.edit(kind === 'receipt' && row?.kind === 'OPENING' ? 'opening' : kind, row);
  },
  edit(kind, row) {
    if (this.data.needsRefresh) return;
    return u.run(this, async () => {
      if (kind === 'member' || kind === 'default') {
        this.setData({ defaultMode: await u.api.get(root + '/default-mode') });
      }
      if (kind === 'opening' && !row) {
        const existing = await this.findOpening();
        const { wallet } = this.data;
        const pendingMatches = existing?.state === 'PENDING_VERIFY' && wallet.import_mode === 'OPENING'
          && fullTime(existing.business_at) === fullTime(wallet.cutoff_at);
        if (existing?.state === 'VERIFIED' || pendingMatches) {
          this.setData({ tab: 'receipts', settingsOpen: false, notice: existing.state === 'VERIFIED'
            ? '期初余额 #' + existing.id + ' 已入账，可在「充值与退款」查看。'
            : '期初余额 #' + existing.id + ' 待核实，请打开原单据核对后入账。' });
          await this.read(false);
          return;
        }
        row = existing;
      }
      const sessions = kind === 'lesson' ? (await u.api.get(root + '/sessions')).map(display.present) : [];
      this.setEditor(kind, row, sessions);
    });
  },
  async findOpening() {
    const path = this.memberPath() + '/receipts';
    let batch = await u.api.get(path);
    let editable;
    while (batch.length) {
      const verified = batch.find(item => item.kind === 'OPENING' && item.state === 'VERIFIED');
      if (verified) return verified;
      editable ??= batch.find(item => item.kind === 'OPENING' && ['PENDING_VERIFY', 'RETURNED'].includes(item.state));
      if (batch.length < 30 || this.data.wallet.import_mode !== 'OPENING') break;
      batch = await u.api.get(path, { before: batch[batch.length - 1].id });
    }
    return editable;
  },
  setEditor(kind, row, sessions = [], draft = null) {
    const spec = forms.definition(kind, this.data, row, sessions);
    const form = draft || spec.form;
    this.editorFields = spec.fields;
    this.editingRow = row;
    this.sessions = sessions;
    const saveText = kind === 'import' ? '保存并录入充值'
      : kind === 'session' && this.lessonDraft ? '保存场次并继续'
        : isReceipt(kind) || kind === 'lesson' ? '保存待核实' : '保存';
    this.setData({ editor: kind, title: spec.title, fields: forms.visibleFields(spec.fields, form, false),
      editorHint: spec.hint || '', form, allowDuplicate: false, notice: '', saveText, settingsOpen: false });
    this.queueLessonCheck();
  },
  change(e) {
    const { key, value } = e.detail;
    const patch = { ['form.' + key]: value };
    if (['mode', 'outcome', 'decision'].includes(key)) {
      patch.fields = forms.visibleFields(this.editorFields, { ...this.data.form, [key]: value }, this.data.allowDuplicate);
    }
    this.setData(patch);
    if (this.data.editor === 'lesson' && previewFields.includes(key)) this.queueLessonCheck();
  },
  cancelLessonCheck() {
    if (this.lessonCheckTimer) clearTimeout(this.lessonCheckTimer);
    this.lessonCheckTimer = null;
    this.lessonCheckRequest = null;
  },
  queueLessonCheck() {
    this.cancelLessonCheck();
    this.setData({ lessonCheck: null });
    if (this.data.editor !== 'lesson') return;
    const body = forms.payload('lesson', this.data.form, this.data.wallet);
    if (!Number.isSafeInteger(body.sessionId) || body.sessionId <= 0 ||
        (body.decision === 'MANUAL' && !/^\d{1,7}(\.\d{1,2})?$/.test(body.manualAmount))) return;
    const check = {};
    this.lessonCheckRequest = check;
    this.setData({ lessonCheck: { title: '正在核对扣费条件…' } });
    this.lessonCheckTimer = setTimeout(async () => {
      this.lessonCheckTimer = null;
      const result = await this.checkLessonCharge(body);
      if (this.lessonCheckRequest === check) this.setData({ lessonCheck: result });
    }, 350);
  },
  async checkLessonCharge(body) {
    const { sessionId, bookingId, outcome, cancelledAt, decision, manualAmount } = body;
    try {
      const preview = await u.api.post(this.memberPath() + '/lessons/preview',
        { sessionId, bookingId, outcome, cancelledAt, decision, manualAmount }, undefined, { quiet: true });
      if (!preview?.status) throw new Error('请刷新后重试。');
      return presentDebit(preview);
    } catch (error) {
      return { title: '暂未能确认扣费条件', advice: error?.message || '请核对场次、金额或网络。', blocked: true };
    }
  },
  closeEditor() {
    if (this.lessonDraft) {
      const { form, row, sessions } = this.lessonDraft;
      this.lessonDraft = null;
      this.setEditor('lesson', row, sessions, form);
      this.setData({ error: '' });
      return;
    }
    this.cancelLessonCheck();
    this.setData({ editor: '', fields: [], form: {}, error: '', lessonCheck: null });
  },
  newLessonSession() {
    if (this.data.busy || this.data.editor !== 'lesson') return;
    this.lessonDraft = { form: this.data.form, row: this.editingRow, sessions: this.sessions };
    this.setEditor('session');
    this.setData({ error: '' });
  },
  memberPath() { return root + '/members/' + this.data.memberId; },
  async saveOpening(base, body, wallet) {
    forms.validateOpening(body);
    const cutoff = fullTime(wallet.cutoff_at);
    if (wallet.import_mode === 'OPENING' && cutoff.slice(0, 16) === body.businessAt.slice(0, 16)) {
      body.businessAt = cutoff;
    } else {
      await this.send(base + '/import', { mode: 'OPENING', cutoffAt: body.businessAt, reason: body.reason });
      this.setData({ 'wallet.import_mode': 'OPENING', 'wallet.cutoff_at': body.businessAt });
    }
    return this.send(base + '/receipts', body);
  },
  async refreshAfterWrite(notice) {
    this.setData({ notice, needsRefresh: true });
    try {
      await this.read(false);
    } catch (_) {
      throw new Error('操作已提交，账本刷新失败，请点「刷新账本」核对。');
    }
  },
  save() {
    if (!this.data.editor || this.data.needsRefresh) return;
    return u.run(this, async () => {
      const { editor, form, wallet } = this.data;
      const base = this.memberPath();
      const routes = {
        member: root + '/members', lesson: base + '/lessons', mode: base + '/mode',
        import: base + '/import', session: root + '/sessions',
        rule: root + '/rules', grant: root + '/late-fee-grants/' + form.operatorId, default: root + '/default-mode'
      };
      const confirmations = {
        mode: '切换后将重新分配现有账户余额，请核对金额。',
        grant: '此设置决定该后台账号能否处理临时取消费。'
      };
      if (editor === 'mode' && form.mode === wallet.mode) throw new Error('请选择要切换到的余额使用范围。');
      if (confirmations[editor] && !await u.confirm(this.data.title, confirmations[editor])) return;
      const body = forms.payload(editor, form, wallet);
      if (editor === 'lesson' && Number.isSafeInteger(body.sessionId) && body.sessionId > 0) {
        this.cancelLessonCheck();
        const check = await this.checkLessonCharge(body);
        this.setData({ lessonCheck: check });
        if (check.blocked && !await u.confirm('仍要保存历史课？',
          [check.title, check.detail, check.advice, '可以先保存待核实，稍后处理扣费。'].filter(Boolean).join('\n'))) return;
      }
      let result;
      try {
        result = editor === 'opening' ? await this.saveOpening(base, body, wallet)
          : await this.send(isReceipt(editor) ? base + '/receipts' : routes[editor], body);
      } catch (error) {
        if (/确属另一|可能重复/.test(error?.message || '')) {
          this.setData({ allowDuplicate: true, fields: forms.visibleFields(this.editorFields, form, true) });
        }
        throw error;
      }
      if (editor === 'session' && this.lessonDraft) {
        const { form: lesson, row, sessions } = this.lessonDraft;
        this.lessonDraft = null;
        const added = { id: result, course_name: body.courseName || '新建场次', timeText: display.displayTime(body.startAt) };
        this.setEditor('lesson', row, [added, ...sessions], { ...lesson, sessionId: result, sessionOverride: '' });
        return;
      }
      this.closeEditor();
      if (editor === 'member') {
        this.setData({ memberId: result.id, tab: 'lessons', rows: [] });
        if (result.user_id && await new Promise(resolve => wx.redirectTo({
          url: profileUrl(result.user_id), success: () => resolve(true), fail: () => resolve(false)
        }))) return;
      } else if (isReceipt(editor)) {
        this.setData({ tab: 'receipts', rows: [] });
      } else if (editor === 'lesson') {
        this.setData({ tab: 'lessons', rows: [] });
      }
      if (editor === 'import') {
        await this.refreshAfterWrite('逐笔补录已设置。');
        this.setEditor('recharge');
        this.setData({ editorHint: '逐笔补录已设置。请填写第一笔旧充值的金额和实际收款日期。' });
        return;
      }
      const notice = isReceipt(editor) ? '已保存待核实。点「核实单据」查看实际入账结果。'
        : editor === 'lesson' ? '历史课已保存。点该课的「核实课程」确认扣费。'
          : editor === 'session' ? '场次 #' + result + ' 已保存。' : '已保存。';
      await this.refreshAfterWrite(notice);
      u.success();
    });
  },
  review(e) {
    if (this.data.needsRefresh) return;
    const { id, action, type } = e.currentTarget.dataset;
    const row = this.data.rows.find(r => String(r.id) === String(id));
    if (!row) return;
    return u.run(this, async () => {
      const path = root + '/members/' + this.data.memberId + '/' + type + '/' + id;
      const quote = type === 'lessons' && ['verify', 'settle'].includes(action) ? await u.api.get(path + '/quote') : null;
      const check = quote?.preview ? presentDebit(quote.preview, action === 'settle' ? '可结算' : undefined) : null;
      const reviewText = check ? [check.title, check.detail, check.advice].filter(Boolean).join('\n') : '';
      if (action === 'settle' && check?.blocked) throw new Error(reviewText);
      if (check?.blocked && !await u.confirm('本次只核实课程结果', reviewText)) return;
      let title = { verify: quote ? '核实课程' : '核实单据', return: '退回修改', settle: '确认结算', reverse: '冲正已入账记录' }[action];
      if (check?.blocked) title = '核实并转待结算';
      else if (quote?.amountFen > 0) title += ' ¥' + display.money(quote.amountFen);
      else if (action === 'verify' && type === 'receipts') title += ' ¥' + row.amountText;
      if (action === 'reverse') {
        const detail = row.state === 'VERIFIED_COVERED'
          ? '此单据由期初余额覆盖，此操作会撤销核实。'
          : '冲正会反向调整账本余额，保留原流水。实际向会员退钱请使用「登记线下退款」。';
        if (!await u.confirm('确认更正账本记录', detail)) return;
        if (row.state === 'VERIFIED_COVERED') title = '撤销单据核实';
      }
      const reason = await u.reason(title, '');
      if (!reason) return;
      let destination;
      if (action === 'reverse' && row.state !== 'VERIFIED_COVERED') {
        const selected = await new Promise(resolve => wx.showActionSheet({
          itemList: ['原账户', '通用余额', '小班账户', '私教账户'], success: r => resolve(r.tapIndex), fail: () => resolve(-1)
        }));
        if (selected < 0) return;
        destination = [null, 'GENERAL', 'GROUP', 'PRIVATE'][selected];
      }
      await this.send(path + '/' + action, { version: row.version, quote: action === 'verify' ? quote?.token : undefined, destination, reason });
      await this.refreshAfterWrite('已处理，请查看单据状态和上方已入账余额。');
      const updated = this.data.rows.find(item => sameId(item.id, id));
      if (type === 'receipts' && action === 'verify') {
        if (updated?.state === 'VERIFIED_COVERED') this.setData({ notice: '单据已核实。这笔金额已包含在期初余额中。' });
        if (updated?.state === 'VERIFIED') this.setData({ notice: '单据已入账，可在「余额流水」查看。' });
      }
      if (type === 'lessons' && ['verify', 'settle'].includes(action)) {
        if (updated?.settlement === 'WAITING_BALANCE') {
          this.setData({ notice: '课程已核实，¥' + updated.amountText + ' 待结算。请先核对账户余额和原收款时间。' });
          return;
        }
        if (updated?.settlement === 'POSTED') this.setData({ notice: '课程已扣 ¥' + updated.amountText + '，可在「余额流水」查看。' });
        if (updated?.settlement === 'NO_WALLET_DEBIT') this.setData({ notice: '课程已核实，本节不扣余额。' });
      }
      u.success();
    });
  },
  openMember(e) {
    const { id, tab = 'lessons' } = e.currentTarget.dataset;
    wx.navigateTo({ url: '/admin/studio-ledger/studio-ledger?id=' + id + '&tab=' + tab });
  },
  openProfile() {
    const id = this.data.member.user_id;
    if (!id) return;
    const pages = getCurrentPages();
    const previous = pages[pages.length - 2];
    if (previous?.route === 'admin/member-detail/member-detail' && sameId(previous.data?.id, id)) wx.navigateBack();
    else wx.navigateTo({ url: profileUrl(id) });
  },
  memberList() { wx.navigateTo({ url: '/admin/members/members' }); },
  book() {
    const { memberId, member } = this.data;
    if (memberId && member.user_id) wx.navigateTo({ url: '/admin/booking-edit/booking-edit?userId=' + member.user_id + '&ledgerMemberId=' + memberId });
  },
  lateFees() { wx.navigateTo({ url: '/admin/late-fees/late-fees' }); }
});
