const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const root=path.join(__dirname,'..');
const createTransport=require('./support/transport.cjs');
function moduleAt(file,context={}){const module={exports:{}};vm.runInNewContext(fs.readFileSync(path.join(root,file),'utf8'),{module,console,Date,Promise,setTimeout,clearTimeout,...context},{filename:file});return module.exports;}
function apiHarness(){
 const storage=new Map([['adminToken','test-token'],['adminRoles',['ADMIN']]]),requests=[],toasts=[],app={globalData:{baseUrl:'http://127.0.0.1:8090'}};let cleared=0,launched=0;
 const wx={getStorageSync:k=>storage.get(k),showLoading(){},hideLoading(){},showToast:o=>toasts.push(o.title),reLaunch(){launched++;},request:o=>requests.push(o),uploadFile:o=>requests.push(o)};
 const api=moduleAt('admin/api.js',{wx,getApp:()=>app,require:p=>p.includes('transport')?createTransport(wx,()=>app):({clearAuth(){storage.clear();cleared++;}})});
 return {storage,requests,toasts,app,wx,api,get cleared(){return cleared;},get launched(){return launched;}};
}
function pageHarness(file,responses={}) {
 let def;const calls=[],storage=new Map([['adminToken','fixture'],['adminRoles',['ADMIN']]]),nav=[];let modal=true;
 const wx={getStorageSync:k=>storage.get(k),setStorageSync:(k,v)=>storage.set(k,v),showToast(){},stopPullDownRefresh(){},reLaunch:o=>nav.push(o.url),navigateTo:o=>nav.push(o.url),redirectTo:o=>{nav.push(o.url);o.success?.();},showModal:o=>o.success({confirm:modal,content:'隔离测试原因'})};
 const api={};for(const verb of ['get','post','put','del'])api[verb]=async(url,body)=>{calls.push({verb,url,body});const response=responses[url];if(response instanceof Error)throw response;return typeof response==='function'?response(body,verb):response===undefined?{}:response;};
 const u=moduleAt('admin/common.js',{wx,require:()=>api});
 const modules = { '../common': u, '../api': api, '../../utils/studio-ledger': require('../utils/studio-ledger'), '../../utils/util': require('../utils/util') };
 if (file === 'admin/studio-ledger/studio-ledger.js') {
  modules['./forms'] = require('../admin/studio-ledger/forms');
 }
 moduleAt(file,{wx,getApp:()=>({globalData:{baseUrl:'http://127.0.0.1:8090'}}),require:p=>modules[p]||{clearAuth(){storage.clear();}},Page:d=>def=d});
 const page={...def,data:JSON.parse(JSON.stringify(def.data)),setData(values){for(const [k,v]of Object.entries(values)){const parts=k.split('.');let target=this.data;while(parts.length>1){const p=parts.shift();target[p]??={};target=target[p];}target[parts[0]]=v;}}};
 return {page,calls,storage,nav,wx,setModal:v=>modal=v};
}

test('admin requests fail closed without endpoint or token',async()=>{const h=apiHarness();h.app.globalData.baseUrl='';await assert.rejects(h.api.get('/admin/members'));assert.equal(h.requests.length,0);h.app.globalData.baseUrl='http://127.0.0.1:8090';h.storage.clear();await assert.rejects(h.api.get('/admin/members'));assert.equal(h.requests.length,0);});
test('admin token expiry clears every identity and does not fall back to member login',async()=>{const h=apiHarness();const promise=h.api.get('/admin/members');const req=h.requests[0];assert.equal(req.header.Authorization,'Bearer test-token');req.success({data:{code:40100,message:'expired'}});req.complete();await assert.rejects(promise);assert.equal(h.cleared,1);assert.equal(h.launched,1);assert.equal(h.requests.length,1);});
test('network failure retains wx errMsg and explains loopback address',async()=>{const h=apiHarness();const promise=h.api.get('/admin/members');h.requests[0].fail({errMsg:'request:fail connect refused'});h.requests[0].complete();await assert.rejects(promise,e=>e.errMsg==='request:fail connect refused'&&e.message.includes('真机'));});
test('GET removes empty filters and retains zero without altering mutation bodies',async()=>{const h=apiHarness();let p=h.api.get('/admin/members',{page:1,keyword:'',limit:0,userId:null});assert.equal(Object.keys(h.requests[0].data).join(','),'page,limit');h.requests[0].success({data:{code:0,data:[]}});h.requests[0].complete();await p;p=h.api.put('/admin/members/1',{validUntil:''});assert.equal(h.requests[1].data.validUntil,'');h.requests[1].success({data:{code:0}});h.requests[1].complete();await p;});
test('workspace refreshes server roles before exposing menus',async()=>{const h=pageHarness('admin/home/home.js',{'/api/auth/me':{roles:['STORE_MANAGER'],permissions:['member:view','schedule:view','dashboard:view'],name:'隔离店长'},'/api/admin/workspace/summary':{todaySchedules:2}});await h.page.load();const entries=h.page.data.groups.flatMap(g=>g.items.map(x=>x.page));assert.ok(entries.includes('members'));assert.ok(entries.includes('bookings'));assert.ok(!entries.includes('rules'));assert.ok(!entries.includes('logs'));assert.equal(h.storage.get('adminRoles')[0],'STORE_MANAGER');});
test('optional fee check failure preserves member and ledger menus', async () => {
 const h = pageHarness('admin/home/home.js', {
  '/api/auth/me': { roles: ['ADMIN'] },
  '/api/capabilities': { studioLedger: true },
  '/api/studio/late-fees/access': new Error('offline')
 });
 await h.page.load();
 const entries = h.page.data.groups.flatMap(group => group.items.map(item => item.page));
 assert.ok(entries.includes('members'));
 assert.ok(entries.includes('studio-ledger'));
 assert.ok(!entries.includes('late-fees'));
 const core = h.page.data.groups.find(group => group.title === '门店日常');
 const work = h.page.data.groups.find(group => group.title === '待办与对账');
 assert.equal(core.items.find(item => item.page === 'members').label, '会员档案');
 assert.equal(work.items.find(item => item.page === 'studio-ledger').label, '账本待办');
 assert.equal(h.page.data.groups.find(group => group.title === '小程序展示').settings, true);
 assert.equal(h.page.data.settingsOpen, false);
});
test('member creation recovers on refresh after the capability check fails', async () => {
 const responses = {
  '/admin/members': { total: 0, list: [] },
  '/api/auth/me': { roles: ['ADMIN'] },
  '/api/capabilities': new Error('offline')
 };
 const h = pageHarness('admin/members/members.js', responses);
 await h.page.onShow();
 assert.equal(h.page.data.ledgerStatus, 'unavailable');
 h.page.createOffline();
 assert.equal(h.nav.length, 0);
 responses['/api/capabilities'] = { studioLedger: true };
 await h.page.onPullDownRefresh();
 assert.equal(h.page.data.ledgerStatus, 'ready');
 h.page.createOffline();
 assert.equal(h.nav[0], '/admin/studio-ledger/studio-ledger?create=1');
 responses['/api/auth/me'] = { roles: ['STORE_MANAGER'], permissions: ['member:view'] };
 await h.page.onShow();
 assert.equal(h.page.data.ledgerStatus, 'restricted');
});

test('member avatar waits for upload and verifies the saved image using the refreshed preview', async () => {
 const path = '/admin/members/51';
 const imagePath = '/media/access/4f7cd0ad-c3a3-4d9e-9fed-6f9244bdea83';
 const uploadPreview = 'https://example.test' + imagePath + '?expires=1&signature=upload';
 const readPreview = 'https://example.test' + imagePath + '?expires=2&signature=refreshed';
 let avatar = '';
 const h = pageHarness('admin/member-detail/member-detail.js', {
  [path]: body => {
   if (body) {
    if (body.avatarUrl) avatar = readPreview;
    return;
   }
   return { user: { nickname: '测试会员', avatarUrl: avatar }, member: { id: 6, status: 'ACTIVE' }, levels: [] };
  }
 });
 await h.page.onLoad({ id: 51 });
 assert.equal(h.page.data.fields.find(field => field.key === 'avatarUrl').ownerId, 51);
 h.page.onUploadState({ detail: { uploading: true } });
 await h.page.save();
 await h.page.onPullDownRefresh();
 assert.deepEqual(h.calls.filter(call => call.url === path).map(call => call.verb), ['get']);

 h.page.change({ detail: { key: 'avatarUrl', value: uploadPreview } });
 h.page.onUploadState({ detail: { uploading: false } });
 await h.page.save();
 assert.deepEqual(h.calls.filter(call => call.url === path).map(call => call.verb), ['get', 'put', 'get']);
 assert.equal(h.calls.find(call => call.verb === 'put').body.avatarUrl, uploadPreview);
 assert.equal(h.page.data.form.avatarUrl, readPreview);
 assert.equal(h.page.data.error, '');

 h.page.change({ detail: { key: 'nickname', value: '改昵称' } });
 await h.page.save();
 assert.equal(h.calls.filter(call => call.verb === 'put').at(-1).body.avatarUrl, undefined);
});

const ledgerRoot = '/api/admin/studio-ledger';
const memberLedgerPath = ledgerRoot + '/members/9';
const ledgerMember = { id: 9, nickname: '测试会员', binding: 'UNBOUND' };
const ledgerWallet = balance => ({ mode: 'SHARED', balance_fen: balance, accounts: [{ bucket: 'GENERAL', balance_fen: balance }] });

test('member detail handles invitation creation, copy, revoke and approval', async () => {
 const memberNo = 'MB2026112200001234';
 const inviteCode = 'abcdefghijklmnopqrstuvwxyz123456';
 const expiresAt = '2026-11-23T18:00:00';
 let invitationState;
 const path = memberLedgerPath + '/invites';
 const detail = pageHarness('admin/member-detail/member-detail.js', {
  '/api/capabilities': { studioLedger: true },
  '/admin/members/51': { user: { nickname: '测试会员', status: 'ACTIVE' }, member: { id: 9, memberNo, totalSessions: 0 }, levels: [], wechatBound: false },
  [path]: (_, verb) => {
   if (verb === 'post') {
    invitationState = 'ISSUED';
    return { id: 12, code: inviteCode, expiresAt };
   }
   return invitationState ? [{ id: 12, state: invitationState, expires_at: expiresAt }] : [];
  },
  [path + '/12/revoke']: () => { invitationState = 'REVOKED'; },
  [path + '/12/approve']: body => {
   assert.equal(body.proof, 'A1B2C3D4');
   assert.equal(body.reason, '本人核对');
   invitationState = 'CLAIMED';
   return 9;
  }
 });
 const copied = [];
 detail.wx.setClipboardData = ({ data }) => copied.push(data);
 await detail.page.onLoad({ id: 51 });
 assert.equal(detail.page.data.member.memberNo, memberNo);
 assert.equal(detail.page.copyMemberNo, undefined);
 assert.equal(detail.calls.some(call => call.url === memberLedgerPath), false);
 await detail.page.toggleBinding();
 await detail.page.issueInvite();
 assert.equal(detail.page.data.bindingInvite.expiresText, '2026-11-23 18:00');
 detail.page.copyInvite();
 await detail.page.revokeInvite();
 detail.page.copyInvite();
 assert.deepEqual(copied, [inviteCode]);
 await detail.page.issueInvite();
 invitationState = 'REQUESTED';
 await detail.page.refreshInvites();
 assert.equal(detail.page.data.inviteCode, '');
 assert.equal(detail.page.data.bindingInvite.state, 'REQUESTED');
 await detail.page.approveInvite();
 assert.match(detail.page.data.bindingError, /核对码/);
 detail.page.changeBinding({ detail: { key: 'proof', value: 'A1B2C3D4' } });
 detail.page.changeBinding({ detail: { key: 'reason', value: '本人核对' } });
 await detail.page.approveInvite();
 assert.equal(invitationState, 'CLAIMED');
 assert.equal(detail.page.data.wechatBound, true);
 assert.equal(detail.page.data.bindingOpen, false);
});

test('member detail takes WeChat binding status from the ledger on an older backend', async () => {
 let resolveCapabilities;
 let binding = 'UNBOUND';
 const detail = pageHarness('admin/member-detail/member-detail.js', {
  '/api/capabilities': () => new Promise(resolve => { resolveCapabilities = resolve; }),
  '/admin/members/51': { user: { nickname: '线下测试会员', status: 'ACTIVE' }, member: { id: 9, totalSessions: 0 }, levels: [] },
  [memberLedgerPath]: () => ({ member: { ...ledgerMember, binding }, wallet: ledgerWallet(0) }),
  [memberLedgerPath + '/invites']: []
 });
 const loading = detail.page.onLoad({ id: 51, bind: '1' });
 assert.equal(detail.calls.some(call => call.url === memberLedgerPath), false);
 resolveCapabilities({ studioLedger: true });
 await loading;
 assert.equal(detail.page.data.wechatBound, false);
 assert.equal(detail.page.data.bindingOpen, true);
 assert.equal(detail.page.data.bindingLoaded, true);
 assert.ok(detail.calls.some(call => call.verb === 'get' && call.url === memberLedgerPath));

 binding = 'BOUND';
 await detail.page.onPullDownRefresh();
 assert.equal(detail.page.data.wechatBound, true);
 assert.equal(detail.page.data.bindingOpen, false);
 assert.equal(detail.page.canBind(), false);
});

test('member detail exposes a retry when legacy WeChat status cannot be read', async () => {
 const responses = {
  '/api/capabilities': { studioLedger: true },
  '/admin/members/51': { user: { nickname: '线下测试会员', status: 'ACTIVE' }, member: { id: 9 }, levels: [] },
  [memberLedgerPath]: new Error('offline')
 };
 const detail = pageHarness('admin/member-detail/member-detail.js', responses);
 await detail.page.onLoad({ id: 51 });
 assert.equal(detail.page.data.error, '');
 assert.equal(detail.page.data.wechatBound, null);
 assert.equal(detail.page.data.bindingStatusFailed, true);
 assert.equal(detail.page.canBind(), false);

 responses[memberLedgerPath] = { member: ledgerMember, wallet: ledgerWallet(0) };
 await detail.page.refreshBindingStatus();
 assert.equal(detail.page.data.wechatBound, false);
 assert.equal(detail.page.data.bindingStatusFailed, false);
 assert.equal(detail.page.canBind(), true);
 assert.equal(detail.calls.filter(call => call.url === '/admin/members/51').length, 1);
});

test('new offline member and old binding links open the member profile', async () => {
 const h = pageHarness('admin/studio-ledger/studio-ledger.js', {
  [ledgerRoot + '/review']: { lessons: [], receipts: [] },
  [ledgerRoot + '/default-mode']: 'SHARED',
  [ledgerRoot + '/members']: { ...ledgerMember, user_id: 51 },
  [memberLedgerPath]: { member: { ...ledgerMember, user_id: 51 }, wallet: ledgerWallet(0) },
  [memberLedgerPath + '/lessons']: []
 });
 await h.page.onLoad({ create: '1' });
 setLedgerForm(h.page, { nickname: '线下会员' });
 await h.page.save();
 await h.page.onLoad({ id: 9, tab: 'invites' });
 assert.deepEqual(h.nav, ['/admin/member-detail/member-detail?id=51', '/admin/member-detail/member-detail?id=51&bind=1']);
 assert.equal(h.page.copyMemberNo, undefined);
});

function setLedgerForm(page, fields) {
 for (const [key, value] of Object.entries(fields)) page.change({ detail: { key, value } });
}

test('ledger recharge exposes the amount and refreshes the balance after verification', async () => {
 let receipt = null;
 let posted = 0;
 let offline = false;
 const h = pageHarness('admin/studio-ledger/studio-ledger.js', {
  [memberLedgerPath]: () => {
   if (offline) throw new Error('offline');
   return { member: ledgerMember, wallet: ledgerWallet(posted) };
  },
  [memberLedgerPath + '/lessons']: [],
  [memberLedgerPath + '/receipts']: body => {
   if (!body?.kind) return receipt ? [receipt] : [];
   receipt = { id: 7, version: 1, kind: body.kind, amount_fen: 80050, state: 'PENDING_VERIFY', business_at: body.businessAt };
   return receipt.id;
  },
  [memberLedgerPath + '/receipts/7/verify']: () => {
   posted = receipt.amount_fen;
   receipt.state = 'VERIFIED';
   offline = true;
   return receipt.id;
  }
 });
 await h.page.onLoad({ id: 9 });
 await h.page.showEditor({ currentTarget: { dataset: { kind: 'recharge' } } });
 assert.equal(h.page.data.fields[0].key, 'amount');
 assert.equal(h.page.data.saveText, '保存待核实');
 setLedgerForm(h.page, { amount: '800.50', businessDate: '2026-10-21', businessTime: '09:00', channel: '现金', reason: '纸质台账' });
 await h.page.save();
 const saved = h.calls.find(call => call.verb === 'post' && call.url.endsWith('/receipts'));
 assert.equal(saved.body.amount, '800.50');
 assert.equal(saved.body.allocation, null);
 assert.equal(h.page.data.tab, 'receipts');
 assert.equal(h.page.data.rows[0].stateText, '待核实');
 assert.equal(h.page.data.accounts[0].amountText, '0.00');
 await h.page.review({ currentTarget: { dataset: { id: 7, type: 'receipts', action: 'verify' } } });
 assert.equal(h.page.data.needsRefresh, true);
 assert.match(h.page.data.error, /操作已提交/);
 offline = false;
 await h.page.load();
 assert.equal(h.page.data.needsRefresh, false);
 assert.equal(h.page.data.rows[0].stateText, '已入账');
 assert.equal(h.page.data.accounts[0].amountText, '800.50');
});

test('ledger opening balance validates first and retries the receipt without repeating the setup', async () => {
 let wallet = ledgerWallet(0);
 let attempts = 0;
 let receipt = null;
 const h = pageHarness('admin/studio-ledger/studio-ledger.js', {
  [memberLedgerPath]: () => ({ member: ledgerMember, wallet }),
  [memberLedgerPath + '/lessons']: [],
  [memberLedgerPath + '/import']: body => {
   wallet = { ...wallet, import_mode: body.mode, cutoff_at: body.cutoffAt };
   return 9;
  },
  [memberLedgerPath + '/receipts']: body => {
   if (!body?.kind) return receipt ? [receipt] : [];
   if (++attempts === 1) throw new Error('offline');
   receipt = { id: 8, kind: body.kind, amount_fen: 80000, state: 'PENDING_VERIFY', business_at: body.businessAt };
   return receipt.id;
  }
 });
 await h.page.onLoad({ id: 9 });
 await h.page.edit('opening');
 assert.equal(h.page.data.fields[0].label, '期初余额（元）');
 setLedgerForm(h.page, { amount: '800.001', businessDate: '2026-10-21', businessTime: '09:00', reason: '已核对旧账' });
 await h.page.save();
 assert.equal(h.calls.filter(call => call.verb === 'post').length, 0);
 assert.match(h.page.data.error, /两位小数/);
 setLedgerForm(h.page, { amount: '800.00' });
 await h.page.save();
 assert.equal(h.page.data.editor, 'opening');
 assert.equal(h.page.data.form.amount, '800.00');
 await h.page.save();
 const posts = h.calls.filter(call => call.verb === 'post');
 assert.deepEqual(posts.map(call => call.url.split('/').at(-1)), ['import', 'receipts', 'receipts']);
 assert.equal(posts[0].body.cutoffAt, posts[2].body.businessAt);
 assert.equal(h.page.data.rows[0].amountText, '800.00');
 await h.page.edit('opening');
 assert.equal(h.page.data.editor, '');
 assert.match(h.page.data.notice, /期初余额 #8 待核实/);
});

test('ledger replay setup leads directly to entering the first recharge amount', async () => {
 const h = pageHarness('admin/studio-ledger/studio-ledger.js', {
  [memberLedgerPath]: { member: ledgerMember, wallet: ledgerWallet(0) },
  [memberLedgerPath + '/lessons']: [],
  [memberLedgerPath + '/import']: 9
 });
 await h.page.onLoad({ id: 9 });
 await h.page.edit('import');
 assert.equal(h.page.data.saveText, '保存并录入充值');
 setLedgerForm(h.page, { cutoffDate: '2026-10-21', cutoffTime: '09:00', reason: '逐笔核对旧账' });
 await h.page.save();
 assert.equal(h.calls.find(call => call.verb === 'post').body.mode, 'REPLAY');
 assert.equal(h.page.data.editor, 'recharge');
 assert.equal(h.page.data.fields[0].key, 'amount');
});

test('wallet range settings show the current mode and only propose allocation for an actual switch', async () => {
 const h = pageHarness('admin/studio-ledger/studio-ledger.js', {
  [memberLedgerPath]: { member: ledgerMember, wallet: ledgerWallet(80000) },
  [memberLedgerPath + '/lessons']: []
 });
 await h.page.onLoad({ id: 9 });
 await h.page.edit('mode');
 assert.equal(h.page.data.form.mode, 'SHARED');
 assert.equal(h.page.data.fields.some(field => field.key === 'group'), false);
 await h.page.save();
 assert.equal(h.calls.filter(call => call.verb === 'post').length, 0);
 setLedgerForm(h.page, { mode: 'SPLIT' });
 assert.equal(h.page.data.fields.some(field => field.key === 'group'), true);
});

test('ledger lesson retains the amount when creating its session and shows original fees for record-only lessons', async () => {
 const lessons = [{ id: 1, state: 'VERIFIED', settlement: 'NO_WALLET_DEBIT', settlement_basis: 'RECORD_ONLY', decision: 'RECORD_ONLY', manual_fen: 12025, amount_fen: 0 }];
 const h = pageHarness('admin/studio-ledger/studio-ledger.js', {
  [memberLedgerPath]: { member: ledgerMember, wallet: ledgerWallet(0) },
  [memberLedgerPath + '/lessons']: body => {
   if (!body?.sessionId) return lessons;
   lessons.unshift({ id: 2, state: 'PENDING_VERIFY', settlement: 'UNPOSTED', decision: body.decision, manual_fen: 12025 });
   return 2;
  },
  [memberLedgerPath + '/lessons/preview']: { status: 'CURRENT_BALANCE_LOW', bucket: 'GENERAL', lessonAt: '2026-10-21T09:00:00', amountFen: 12025, balanceFen: 0, shortageFen: 12025 },
  [ledgerRoot + '/sessions']: body => body ? 10 : []
 });
 await h.page.onLoad({ id: 9 });
 assert.equal(h.page.data.rows[0].originalFeeText, '120.25');
 assert.equal(h.page.data.rows[0].basisText, '仅补录');
 await h.page.edit('lesson');
 assert.equal(h.page.data.fields.find(field => field.key === 'manualAmount').label, '实际扣费（元）');
 setLedgerForm(h.page, { manualAmount: '120.25', reason: '历史课' });
 h.page.newLessonSession();
 assert.equal(h.page.data.saveText, '保存场次并继续');
 assert.ok(!h.page.data.fields.some(field => field.key === 'endTime' || field.key === 'endDate'));
 setLedgerForm(h.page, { courseName: '线下小班', coachName: '测试教练', startDate: '2026-10-21', startTime: '09:00', price: '120.25' });
 await h.page.save();
 const session = h.calls.find(call => call.verb === 'post' && call.url.endsWith('/sessions'));
 assert.equal(session.body.startAt, '2026-10-21T09:00:00');
 assert.equal(session.body.endAt, undefined);
 assert.equal(h.page.data.editor, 'lesson');
 assert.equal(h.page.data.form.sessionId, 10);
 assert.equal(h.page.data.form.manualAmount, '120.25');
 assert.equal(h.page.data.form.reason, '历史课');
 await h.page.save();
 const saved = h.calls.find(call => call.verb === 'post' && call.url.endsWith('/lessons'));
 assert.equal(saved.body.sessionId, 10);
 assert.equal(saved.body.decision, 'MANUAL');
 assert.equal(saved.body.manualAmount, '120.25');
 assert.equal(h.page.data.rows[0].stateText, '待核实');
});

test('ledger warns before saving a historic charge when the receipt is later than the lesson', async () => {
 const path = memberLedgerPath + '/lessons';
 const preview = { status: 'HISTORICAL_BALANCE_LOW', bucket: 'GENERAL', lessonAt: '2026-10-21T16:29:00',
  amountFen: 50000, balanceFen: 100000, balanceAtFen: 0, shortageFen: 50000 };
 const h = pageHarness('admin/studio-ledger/studio-ledger.js', {
  [memberLedgerPath]: { member: ledgerMember, wallet: ledgerWallet(100000) },
  [path]: [],
  [path + '/preview']: preview,
  [path + '/7/quote']: { preview },
  [ledgerRoot + '/sessions']: [{ id: 10, course_name: '历史小班', start_at: '2026-10-21T16:29:00' }]
 });
 let confirmation;
 h.wx.showModal = options => { confirmation = options; options.success({ confirm: false }); };
 await h.page.onLoad({ id: 9 });
 await h.page.edit('lesson');
 setLedgerForm(h.page, { sessionId: 10, manualAmount: '500', reason: '课程凭据' });
 await new Promise(resolve => setTimeout(resolve, 375));
 assert.match(h.page.data.lessonCheck.title, /暂不能扣款/);
 await h.page.save();
 assert.match(confirmation.content, /上课时点 ¥0\.00/);
 assert.match(confirmation.content, /当前 ¥1000\.00/);
 assert.equal(h.calls.filter(call => call.verb === 'post' && call.url === path).length, 0);
 assert.equal(h.page.data.form.manualAmount, '500');
 h.page.closeEditor();
 h.page.setData({ rows: [{ id: 7, version: 2, settlement: 'WAITING_BALANCE' }] });
 await h.page.review({ currentTarget: { dataset: { type: 'lessons', id: 7, action: 'settle' } } });
 assert.match(h.page.data.error, /上课时点余额不足/);
 assert.equal(h.calls.filter(call => call.verb === 'post' && call.url.endsWith('/settle')).length, 0);
});

test('full or absent target booking is rejected in UI without POST',async()=>{const h=pageHarness('admin/booking-edit/booking-edit.js');h.page.setData({userId:1,scheduleId:2,reason:'测试',list:[{id:2,bookable:false}]});await h.page.submit();assert.equal(h.calls.filter(c=>c.verb==='post').length,0);assert.match(h.page.data.error,/可预约/);});
test('cancelled booking confirmation produces no mutation',async()=>{const h=pageHarness('admin/booking-edit/booking-edit.js');h.setModal(false);h.page.setData({userId:1,scheduleId:2,reason:'测试',list:[{id:2,bookable:true}]});await h.page.submit();assert.equal(h.calls.filter(c=>c.verb==='post').length,0);});
test('booking move uses explicit admin endpoint and redirects to new ID',async()=>{const h=pageHarness('admin/booking-edit/booking-edit.js',{'/api/admin/bookings/9/move':10});h.page.setData({userId:1,bookingId:9,scheduleId:2,reason:'测试',list:[{id:2,bookable:true}]});await h.page.submit();assert.equal(h.calls[0].url,'/api/admin/bookings/9/move');assert.equal(h.calls[0].body.scheduleId,2);assert.ok(h.nav[0].endsWith('id=10'));});

test('ledger booking shows the account holder and cannot be switched to a different member', async () => {
 const h = pageHarness('admin/booking-edit/booking-edit.js', {
  [memberLedgerPath]: { member: { id: 9, user_id: 51, nickname: '账本会员' } },
  '/api/course-schedules': [{ id: 16, courseName: '测试小班', bookable: true }],
  [ledgerRoot + '/booking-terms/16']: null,
  [memberLedgerPath + '/bookings']: 73
 });
 await h.page.onLoad({ ledgerMemberId: 9, userId: 99 });
 assert.equal(h.page.data.fixedMember, true);
 assert.equal(h.page.data.userId, 51);
 assert.equal(h.page.data.memberName, '账本会员');
 h.page.setData({ members: [{ member: { userId: 88, memberNo: 'other' } }], scheduleId: 16, reason: '会员已确认' });
 h.page.pickMember({ currentTarget: { dataset: { id: 88 } } });
 assert.equal(h.page.data.userId, 51);
 await h.page.submit();
 assert.ok(h.calls.some(call => call.verb === 'post' && call.url === memberLedgerPath + '/bookings'));
 assert.equal(h.nav[0], '/admin/booking-detail/booking-detail?id=73');
});

test('one booking cancellation entry routes each reason to its distinct endpoint', async () => {
 const getPath = '/api/admin/bookings/73';
 for (const [tapIndex, endpoint] of [[0, 'cancel'], [1, 'member-cancel']]) {
  let cancelled = false;
  const h = pageHarness('admin/booking-detail/booking-detail.js', {
   '/api/capabilities': { studioLedger: true },
   [getPath]: () => ({ booking: { status: cancelled ? 'CANCELLED' : 'BOOKED', canCancel: !cancelled } }),
   [getPath + '/' + endpoint]: () => { cancelled = true; }
  });
  await h.page.onLoad({ id: 73 });
  let choose;
  h.wx.showActionSheet = options => { choose = options.success; };
  h.page.cancelMenu();
  assert.equal(h.calls.filter(call => call.verb === 'post').length, 0);
  await choose({ tapIndex });
  const writes = h.calls.filter(call => call.verb === 'post');
  assert.deepEqual(writes.map(call => call.url), [getPath + '/' + endpoint]);
  assert.equal(h.page.data.row.booking.status, 'CANCELLED');
 }
});
test('course details load by ID beyond a paginated first hundred and preserve all editable fields',async()=>{const h=pageHarness('admin/course-edit/course-edit.js',{'/admin/course-categories':[{id:1,name:'小班课'}],'/admin/courses/200':{id:200,categoryId:1,name:'测试课',durationMinutes:60,type:'GROUP',difficulty:'BEGINNER',creditsCost:1,price:90,targetUsers:'目标',trainingFocus:'重点',notices:'注意',status:'ON_SHELF',opaque:'must not submit'}});h.page.setData({id:200});await h.page.load();await h.page.save();const put=h.calls.find(c=>c.verb==='put');assert.equal(put.body.price,90);assert.equal(put.body.notices,'注意');assert.equal(put.body.opaque,undefined);assert.ok(!h.calls.some(c=>c.url.includes('size=100')));});
test('schedule with history restricts protected fields and keeps capacity editable',async()=>{const h=pageHarness('admin/schedule-edit/schedule-edit.js',{'/admin/schedules/options':{courses:[],coaches:[],classrooms:[],qualifications:[]},'/admin/schedules/200':{courseId:1,coachId:1,classroomId:1,courseName:'历史课程',coachName:'历史教练',classroomName:'历史教室',courseDate:'2026-10-16',startTime:'09:00:00',endTime:'10:00:00',maxCapacity:8,hasBookingHistory:true,editable:true,cancellable:true}});h.page.setData({id:200});await h.page.load();assert.equal(h.page.data.originalSummary,'历史课程 · 历史教练 · 历史教室');assert.equal(h.page.data.fields.find(f=>f.key==='courseId').readonly,true);assert.ok(!h.page.data.fields.find(f=>f.key==='maxCapacity').readonly);assert.ok(!h.calls.some(c=>c.url.includes('size=100')));});
test('rule form keeps dotted keys as literal configuration names',async()=>{const h=pageHarness('admin/rules/rules.js');h.page.change({detail:{key:'booking.max_per_day',value:'2'}});assert.equal(h.page.data.form['booking.max_per_day'],'2');await h.page.save();assert.equal(h.calls[0].body['booking.max_per_day'],2);assert.equal(h.calls[0].body.booking,undefined);});
test('page load failure is caught and a later retry clears error',async()=>{const responses={'/admin/members':new Error('offline')},h=pageHarness('admin/members/members.js',responses);await h.page.load();assert.equal(h.page.data.error,'offline');assert.equal(h.page.data.busy,false);responses['/admin/members']={total:0,list:[]};await h.page.load();assert.equal(h.page.data.error,'');});
test('all registered admin routes and event handlers resolve to existing files and functions',()=>{
 const app=JSON.parse(fs.readFileSync(path.join(root,'app.json'),'utf8'));const routes=app.subpackages.find(s=>s.root==='admin').pages;
 for(const route of routes){for(const ext of ['js','wxml','wxss','json'])assert.ok(fs.existsSync(path.join(root,'admin',route+'.'+ext)),route+'.'+ext);const script=fs.readFileSync(path.join(root,'admin',route+'.js'),'utf8'),template=fs.readFileSync(path.join(root,'admin',route+'.wxml'),'utf8');new vm.Script(script,{filename:route});for(const match of template.matchAll(/(?:bind|catch)(?:tap|input|change|submit|confirm)="([A-Za-z_]\w*)"/g))assert.match(script,new RegExp('\\b'+match[1]+'\\s*\\('),route+' handler '+match[1]);}
});

test('management form preserves numeric zero and selects actual option ID',()=>{
 let component;moduleAt('admin/components/management-form/index.js',{Component:c=>component=c});
 const form={data:{rows:[]},setData(v){Object.assign(this.data,v);},triggerEvent(name,detail){this.event={name,detail};}};
 component.observers['fields,value'].call(form,[{key:'gender',type:'select',options:[{value:0,label:'未设置'},{value:2,label:'女'}]}],{gender:0});
 assert.equal(form.data.rows[0].current,0);assert.equal(form.data.rows[0].selected,'未设置');
 component.methods.select.call(form,{currentTarget:{dataset:{key:'gender'}},detail:{value:'1'}});
 assert.equal(form.event.detail.value,2);
});
test('management form treats dotted configuration keys as literal keys',()=>{
 let component;moduleAt('admin/components/management-form/index.js',{Component:c=>component=c});
 const form={data:{rows:[]},setData(v){Object.assign(this.data,v);}};
 component.observers['fields,value'].call(form,[{key:'booking.max_per_day',type:'number'}],{'booking.max_per_day':3});
 assert.equal(form.data.rows[0].current,3);
});

test('studio initial load failure blocks saving an empty replacement',async()=>{
 const h=pageHarness('admin/studio/studio.js',{'/api/coach-end/home/studio':new Error('offline')});
 await h.page.load();assert.equal(h.page.data.error,'offline');h.page.setData({info:{name:'测试门店',address:'测试地址',phone:'13900000000'}});h.page.submit();
 assert.equal(h.calls.filter(c=>c.verb==='put').length,0);
});
test('missing banner detail cannot be submitted as a replacement',async()=>{
 const h=pageHarness('admin/banner-edit/banner-edit.js',{'/api/coach-end/home/banners':[]});h.page.id=99;
 await h.page.load();assert.match(h.page.data.error,/不存在/);h.page.setData({imageUrl:'https://example.test/test.png'});h.page.submit();
 assert.equal(h.calls.filter(c=>c.verb==='put'||c.verb==='post').length,0);
});
test('uploaded image is resolved to a reachable backend URL before storing form value',async()=>{
 let component,pending;moduleAt('admin/components/management-form/index.js',{
  Component:c=>component=c,getApp:()=>({globalData:{baseUrl:'https://isolated.example.test'}}),
  wx:{chooseMedia:o=>pending=o.success({tempFiles:[{tempFilePath:'local-image'}]})},require:()=>({upload:async()=>'/uploads/fake.png'})
 });
 const form={data:{disabled:false},setData(v){Object.assign(this.data,v);},triggerEvent(name,detail){if(name==='change')this.changed=detail;}};
 component.methods.image.call(form,{currentTarget:{dataset:{key:'cover'}}});await pending;
 assert.equal(form.changed.value,'https://isolated.example.test/uploads/fake.png');
});

test('new course only selects a course type and reads back the saved course',async()=>{
 const h=pageHarness('admin/course-edit/course-edit.js',{'/admin/courses':42,'/admin/courses/42':{id:42,name:'保存后的名称',type:'PRIVATE',categoryId:77}});
 await h.page.load();
 const type=h.page.data.fields.find(f=>f.label==='课程类型');
 assert.equal(type.key,'type');assert.deepEqual(Array.from(type.options,o=>o.label),['小班课','私教课']);
 assert.ok(!h.page.data.fields.some(f=>f.key==='categoryId'||f.label==='分类'));
 h.page.setData({'form.name':'新私教','form.type':'PRIVATE'});await h.page.save();
 assert.deepEqual(h.calls.map(c=>[c.verb,c.url]),[['post','/admin/courses'],['get','/admin/courses/42']]);
 assert.ok(!Object.hasOwn(h.calls[0].body,'categoryId'));assert.equal(h.calls[0].body.type,'PRIVATE');
 assert.equal(h.page.data.form.name,'保存后的名称');
});

test('editing a historical experience course can keep its type without resubmitting category',async()=>{
 const h=pageHarness('admin/course-edit/course-edit.js',{'/admin/courses/42':{id:42,name:'历史体验课',type:'EXPERIENCE',categoryId:77,durationMinutes:60,creditsCost:1}});
 h.page.setData({id:42});await h.page.load();
 assert.ok(h.page.data.fields.find(f=>f.key==='type').options.some(o=>o.value==='EXPERIENCE'));
 h.page.setData({'form.name':'更新体验课'});await h.page.save();
 assert.deepEqual(h.calls.map(c=>c.verb),['get','put','get']);
 assert.ok(!Object.hasOwn(h.calls[1].body,'categoryId'));assert.equal(h.calls[1].body.type,'EXPERIENCE');
});

test('coach cannot save during upload and reads persisted avatar immediately after saving',async()=>{
 const h=pageHarness('admin/coach-edit/coach-edit.js',{'/admin/coaches/9':{name:'已保存姓名',avatar:'https://example.test/media/public/new',years:3,sort:0,status:'ACTIVE'}});
 h.page.setData({id:9,form:{name:'本地姓名',avatar:'https://example.test/media/public/new',years:3,sort:0},uploading:true});
 await h.page.save();assert.equal(h.calls.length,0);
 h.page.setData({uploading:false});await h.page.save();
 assert.equal(h.calls[0].verb,'put');assert.equal(h.calls[1].verb,'get');assert.equal(h.page.data.form.name,'已保存姓名');
 assert.equal(h.page.data.form.avatar,'https://example.test/media/public/new');
});

test('returning to hot-course/team management refreshes details while preserving unsaved selections',async()=>{
 for(const [folder,key,allKey,publicPath,selectionPath,idField] of [
  ['hot-courses','hotCourseIds','allCourses','/api/courses','/api/coach-end/home/hot-courses','courseId'],
  ['team-coaches','teamCoachIds','allCoaches','/api/coaches','/api/coach-end/home/team-coaches','coachId']]){
  const responses={[publicPath]:[{id:1,name:'旧名称'}],[selectionPath]:[{[idField]:1}]};
  const h=pageHarness(`admin/${folder}/${folder}.js`,responses);await h.page.load();
  h.page.toggle({currentTarget:{dataset:{id:1}}});assert.equal(h.page.data[key].length,0);
  responses[publicPath]=[{id:1,name:'新名称',avatar:'https://example.test/new.png'}];
  await h.page.onShow();assert.equal(h.page.data[allKey][0].name,'新名称');assert.equal(h.page.data[key].length,0);
  assert.equal(h.page.data[allKey][0].selected,false);
 }
});

test('image upload reports pending state and renders an actual image preview',async()=>{
 let component,finish,pending;const events=[];
 moduleAt('admin/components/management-form/index.js',{Component:c=>component=c,getApp:()=>({globalData:{baseUrl:''}}),
  wx:{chooseMedia:o=>{pending=o.success({tempFiles:[{tempFilePath:'synthetic.png'}]});}},require:()=>({upload:()=>new Promise(resolve=>finish=resolve)})});
 const form={data:{rows:[{key:'avatar'}],disabled:false,imageErrors:{}},setData(v){Object.assign(this.data,v);},triggerEvent(name,detail){events.push({name,detail});}};
 component.methods.image.call(form,{currentTarget:{dataset:{key:'avatar'}}});
 assert.equal(form.data.uploading,true);assert.equal(events[0].name,'uploadstate');
 await Promise.resolve();finish('https://example.test/new.png');await pending;
 assert.equal(form.data.uploading,false);assert.equal(events.find(e=>e.name==='change').detail.value,'https://example.test/new.png');
 assert.equal(events.at(-1).detail.uploading,false);
 const template=fs.readFileSync(path.join(root,'admin/components/management-form/index.wxml'),'utf8');
 assert.match(template,/<image[^>]*src="\{\{item.current\}\}"/);
});

test('large native coach images are compressed before upload while saving stays blocked',async()=>{
 let component,pending,uploadedPath;const widths=[];
 moduleAt('admin/components/management-form/index.js',{Component:c=>component=c,getApp:()=>({globalData:{transport:'cloudrun',baseUrl:''}}),
 wx:{chooseMedia:o=>pending=o.success({tempFiles:[{tempFilePath:'large.jpg',size:1024*1024}]}),
 getFileSystemManager:()=>({stat:o=>o.success({stats:{size:o.path==='compressed-720.jpg'?200*1024:800*1024}})}),
 compressImage:o=>{widths.push(o.compressedWidth);o.success({tempFilePath:'compressed-'+o.compressedWidth+'.jpg'});}},require:()=>({upload:async(p,file)=>{uploadedPath=file;return 'https://example.test/photo.png';}})});
 const form={data:{rows:[],disabled:false,imageErrors:{}},setData(v){Object.assign(this.data,v);},triggerEvent(){}};
 component.methods.image.call(form,{currentTarget:{dataset:{key:'avatar'}}});assert.equal(form.data.uploading,true);await pending;
 assert.deepEqual(widths,[1024,720]);assert.equal(uploadedPath,'compressed-720.jpg');assert.equal(form.data.uploading,false);
});

test('oversized compression output never reaches native upload',async()=>{
 let component,pending,uploads=0;const widths=[],toasts=[];
 moduleAt('admin/components/management-form/index.js',{Component:c=>component=c,getApp:()=>({globalData:{transport:'cloudrun',baseUrl:''}}),
 wx:{chooseMedia:o=>pending=o.success({tempFiles:[{tempFilePath:'large.png'}]}),
 getFileSystemManager:()=>({stat:o=>o.success({stats:{size:800*1024}})}),
 compressImage:o=>{widths.push(o.compressedWidth);o.success({tempFilePath:'compressed.png'});},showToast:o=>toasts.push(o.title)},
 require:()=>({upload:async()=>{uploads++;}})});
 const form={data:{rows:[],disabled:false,imageErrors:{}},setData(v){Object.assign(this.data,v);},triggerEvent(){}};
 component.methods.image.call(form,{currentTarget:{dataset:{key:'avatar'}}});await pending;
 assert.deepEqual(widths,[1024,720,480]);assert.equal(uploads,0);assert.equal(form.data.uploading,false);
 assert.ok(toasts[0].includes('图片压缩失败'));
});

test('changing account during image preparation prevents uploading the previous selection',async()=>{
 let component,pending,uploads=0;const globalData={transport:'cloudrun',baseUrl:'',authEpoch:1};
 moduleAt('admin/components/management-form/index.js',{Component:c=>component=c,getApp:()=>({globalData}),
 wx:{chooseMedia:o=>pending=o.success({tempFiles:[{tempFilePath:'selection.png',size:100}]}),getFileSystemManager:()=>({stat:o=>o.success({stats:{size:100}})}),showToast(){}},require:()=>({upload:async()=>{uploads++;return 'https://example.test/photo.png';}})});
 const form={data:{rows:[],disabled:false,imageErrors:{}},setData(v){Object.assign(this.data,v);},triggerEvent(){}};
 component.methods.image.call(form,{currentTarget:{dataset:{key:'avatar'}}});globalData.authEpoch=2;await pending;
 assert.equal(uploads,0);assert.equal(form.data.uploading,false);
});
