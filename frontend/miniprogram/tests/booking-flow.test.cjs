const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const util = require('../utils/util');
const scheduleForm = require('../utils/schedule-form');
const memberBooking = require('../utils/member-booking');
const memberLedger = require('../utils/member-ledger');
const studioLedger = require('../utils/studio-ledger');
function page(file, api, http={login:()=>Promise.resolve()}, wxOverrides={}) {
  let definition; const navigations=[];
  const wx={showToast(){},redirectTo:x=>navigations.push(x.url),navigateTo:x=>navigations.push(x.url),navigateBack(){},...wxOverrides};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'..',file),'utf8'),{Page:p=>definition=p,require:name=>name.includes('services/api')||name==='../api'?api:name.includes('request')?http:name.endsWith('/schedule-form')?scheduleForm:name.endsWith('/member-booking')?memberBooking:name.endsWith('/member-ledger')?memberLedger:name.endsWith('/studio-ledger')?studioLedger:util,wx,console,Date,Promise,setTimeout});
  const instance={data:JSON.parse(JSON.stringify(definition.data)),setData(update){Object.assign(this.data,update);}};
  for(const [key,value] of Object.entries(definition))if(typeof value==='function')instance[key]=value.bind(instance);
  return {instance,navigations};
}
test('Shanghai dates: Monday, Sunday, cross month and year',()=>{
  assert.deepEqual(util.weekRange(false,Date.parse('2026-10-12T04:00Z')),{fromDate:'2026-10-12',toDate:'2026-10-18'});
  assert.deepEqual(util.weekRange(false,Date.parse('2026-10-18T04:00Z')),{fromDate:'2026-10-12',toDate:'2026-10-18'});
  assert.deepEqual(util.weekRange(true,Date.parse('2026-10-18T04:00Z')),{fromDate:'2026-10-19',toDate:'2026-10-25'});
  assert.deepEqual(util.weekRange(false,Date.parse('2026-11-01T04:00Z')),{fromDate:'2026-10-26',toDate:'2026-11-01'});
  assert.deepEqual(util.weekRange(false,Date.parse('2027-01-01T04:00Z')),{fromDate:'2026-12-28',toDate:'2027-01-03'});
  assert.equal(util.localDate(1,Date.parse('2026-12-31T16:30Z')),'2027-01-02');
});
test('FREE private initial loading, coach preselection and no package/order/payment call',async()=>{
  const calls=[];const api={bookingRules:async()=>({bookingMode:'FREE'}),coaches:async()=>[{id:1},{id:2}],myPackages:()=>{throw Error('must not load packages');},coachSchedules:async id=>{calls.push(id);return [{id:10,courseType:'PRIVATE',bookable:true}];},createBooking:async body=>{calls.push(body);return {bookingId:20};}};
  const {instance:p,navigations}=page('pages/private-booking/private-booking.js',api);p.onLoad({coachId:'2'});await p.onShow();assert.equal(p.data.coachIndex,1);assert.equal(calls[0],2);
  await p.submit({currentTarget:{dataset:{sid:10}}});assert.equal(calls[1].scheduleId,10);assert.equal(calls[1].userPackageId,undefined);assert.equal(navigations.length,1);
  await p.onCoach({detail:{value:0}});assert.equal(calls.at(-1),1);
});
test('PACKAGE private branch keeps selected package',async()=>{
  let payload;const api={bookingRules:async()=>({bookingMode:'PACKAGE'}),coaches:async()=>[{id:1}],myPackages:async()=>[{id:9,type:'PRIVATE',name:'私教卡'}],coachSchedules:async()=>[{id:10,courseType:'PRIVATE',bookable:true}],createBooking:async body=>{payload=body;return {bookingId:1};}};
  const {instance:p}=page('pages/private-booking/private-booking.js',api);p.onLoad({});await p.onShow();await p.submit({currentTarget:{dataset:{sid:10}}});assert.equal(payload.userPackageId,9);
});
test('confirmation prevents double clicks, refreshes failure and permits corrected retry',async()=>{
  let submits=0,loads=0;const api={bookingRules:async()=>({bookingMode:'FREE'}),scheduleDetail:async()=>{loads++;return {id:1,bookable:true};},createBooking:async()=>{if(++submits===1)throw {message:'名额发生变化'};return {bookingId:3};}};
  const {instance:p}=page('pages/booking-confirm/booking-confirm.js',api);p.onLoad({scheduleId:1});await p.onShow();const first=p.submit();p.submit();await first;assert.equal(submits,1);assert.equal(loads,2);assert.equal(p.data.submitting,false);assert.equal(p.data.error,'名额发生变化');await p.submit();assert.equal(submits,2);
});
test('course detail and coach timetable reload when returning',async()=>{
  let count=0;const api={courseDetail:async()=>({id:1}),courseSchedules:async()=>{count++;return [];},bookingRules:async()=>({bookingMode:'FREE'}),courseReviews:async()=>({list:[]}),get:async()=>{count++;return [];}};
  const {instance:p}=page('pages/course-detail/course-detail.js',api);p.onLoad({id:1});await p.onShow();await p.onShow();assert.equal(count,2);
  const {instance:c}=page('coach-end/schedule/schedule.js',api);c.onLoad();await c.onShow();await c.onShow();assert.equal(count,4);
});
test('coach selects either course type and arbitrary start minute, then books for a confirmed member', async () => {
  const requests = [];
  const approved = { status: 'APPROVED', courseStatus: 'ON_SHELF' };
  const courses = [
    { ...approved, courseId: 11, courseName: '器械小班', courseType: 'GROUP', durationMinutes: 50 },
    { ...approved, courseId: 12, courseName: '一对一', courseType: 'PRIVATE', durationMinutes: 45 },
    { ...approved, courseId: 13, courseName: '已下架', courseStatus: 'OFF_SHELF' }
  ];
  const api = {
    get: async url => url.endsWith('/my') ? courses : [{ id: 5, name: '练习室', capacity: 4 }],
    post: async (url, body) => {
      requests.push({ url, body });
      if (url.endsWith('/booking-member')) return { userId: 51, nickname: '测试会员' };
      if (url.endsWith('/book')) return { bookingId: 21, scheduleId: 17 };
      return 19;
    }
  };
  const date = util.localDate(3);
  const wx = { setNavigationBarTitle() {}, showModal: options => options.success({ confirm: true }) };
  const group = page('coach-end/schedule-create/schedule-create.js', api, undefined, wx).instance;
  await group.onLoad({ date });
  assert.equal(group.data.courseChoices.length, 3);
  assert.equal(group.data.courseChoices[1], '器械小班 · 团课');
  group.onCourse({ detail: { value: 1 } });
  group.onRoom({ detail: { value: 1 } });
  group.onStart({ detail: { value: '14:37' } });
  await group.submit();
  assert.equal(requests[0].url, '/api/coach-end/schedules');
  assert.equal(requests[0].body.endTime, '15:27');
  assert.equal(requests[0].body.maxCapacity, 4);

  const { instance: privateClass, navigations } = page('coach-end/schedule-create/schedule-create.js', api, undefined, wx);
  await privateClass.onLoad({ date, mode: 'booking' });
  privateClass.onCourse({ detail: { value: 2 } });
  privateClass.onRoom({ detail: { value: 1 } });
  privateClass.onStart({ detail: { value: '18:17' } });
  privateClass.onReason({ detail: { value: '已确认' } });
  await privateClass.submit();
  assert.equal(requests.length, 1);
  privateClass.onMember({ detail: { value: 'MB12345' } });
  await privateClass.lookupMember();
  await privateClass.submit();
  assert.equal(requests[1].url, '/api/coach-end/schedules/booking-member');
  assert.equal(requests[2].url, '/api/coach-end/schedules/book');
  assert.equal(requests[2].body.userId, 51);
  assert.equal(requests[2].body.schedule.maxCapacity, 1);
  assert.equal(requests[2].body.schedule.endTime, '19:02');
  assert.equal(navigations[0], '/coach-end/students/students?scheduleId=17');
});
test('course failures are distinct from empty results and date selection follows configured window',async()=>{
  const api={bookingRules:async()=>({bookingMode:'FREE',advanceDays:3}),schedules:async()=>{throw {message:'服务不可用'};}};
  const {instance:p}=page('pages/course/course.js',api);await p.onShow();assert.equal(p.data.days.length,4);assert.equal(p.data.error,'服务不可用');assert.equal(p.data.loading,false);
});
test('unbookable confirmation cannot submit',async()=>{
  let calls=0;const api={bookingRules:async()=>({bookingMode:'FREE'}),scheduleDetail:async()=>({bookable:false,bookingStatus:'DEADLINE'}),createBooking:()=>calls++};
  const {instance:p}=page('pages/booking-confirm/booking-confirm.js',api);p.onLoad({scheduleId:1});await p.onShow();await p.submit();assert.equal(calls,0);
});
test('member bookings put the closest class first and keep history local to the tab', async () => {
  const pending = [];
  const api = { myBookings: () => new Promise((resolve, reject) => pending.push({ resolve, reject })) };
  const { instance: p } = page('pages/booking/booking.js', api);
  const first = p.load(), second = p.load();
  const slot = { courseDate: '2099-11-27', endTime: '21:00' };
  pending[1].resolve([
    { ...slot, id: 90, status: 'CANCELLED', startTime: '17:00' },
    { ...slot, id: 30, status: 'BOOKED', startTime: '19:00' },
    { ...slot, id: 20, status: 'BOOKED', startTime: '18:00' }
  ]);
  await second;
  pending[0].resolve([]);
  await first;
  assert.equal(p.data.nextBooking.id, 20);
  assert.equal(p.data.upcoming[0].id, 30);
  assert.equal(p.data.history[0].id, 90);
  p.selectTab({ currentTarget: { dataset: { tab: 'history' } } });
  assert.equal(p.data.tab, 'history');
  assert.equal(pending.length, 2);
  const failure = p.load();
  pending[2].reject({ message: '网络异常' });
  await failure;
  assert.equal(p.data.error, '网络异常');
  const retry = p.load();
  pending[3].resolve([]);
  await retry;
  assert.equal(p.data.history.length, 0);
  assert.equal(p.data.error, '');
});
test('changing dates clears obsolete slots even when the new date fails', async () => {
  let requests = 0;
  const api = {
    bookingRules: async () => ({ advanceDays: 2 }),
    schedules: async () => {
      if (++requests === 2) throw new Error('弱网');
      return [{ id: requests === 1 ? 10 : 11, bookable: true, difficulty: 'BEGINNER' }];
    }
  };
  const { instance: p, navigations } = page('pages/course/course.js', api);
  await p.onShow();
  assert.equal(p.data.schedules[0].difficultyText, '入门');
  const change = p.onDay({ currentTarget: { dataset: { i: 1 } } });
  assert.equal(p.data.schedules.length, 0);
  await change;
  p.goDetail({ currentTarget: { dataset: { id: 10 } } });
  assert.equal(navigations.length, 0);
  assert.equal(p.data.error, '弱网');
  await p.retry();
  assert.equal(p.data.dayIndex, 1);
  p.goDetail({ currentTarget: { dataset: { id: 11 } } });
  assert.equal(navigations[0], '/pages/booking-confirm/booking-confirm?scheduleId=11');
});
test('a failed private-coach change cannot submit the previous coach slot', async () => {
  let submitted = false;
  const api = {
    bookingRules: async () => ({ bookingMode: 'FREE' }), coaches: async () => [{ id: 1 }, { id: 2 }],
    coachSchedules: async id => {
      if (id === 2) throw new Error('弱网');
      return [{ id: 10, courseType: 'PRIVATE', bookable: true }];
    },
    createBooking: async () => { submitted = true; }
  };
  const { instance: p } = page('pages/private-booking/private-booking.js', api);
  p.onLoad({});
  await p.onShow();
  await p.onCoach({ detail: { value: 1 } });
  await p.submit({ currentTarget: { dataset: { sid: 10 } } });
  assert.equal(p.data.schedules.length, 0);
  assert.equal(submitted, false);
});
test('home uses the real coach id and loads available slots only without an upcoming class', async () => {
  let hasBooking = true, queries = 0;
  const api = {
    home: async () => ({ coaches: [{ id: 5, coachId: 47, coachName: '林教练' }] }), studio: async () => null,
    myBookings: async () => hasBooking ? [{ id: 51, status: 'BOOKED', courseDate: '2099-11-27', startTime: '18:30', endTime: '19:30' }] : [],
    schedules: async params => { assert.equal(params.onlyAvailable, true); queries++; return [{ id: 30, bookable: true }]; }
  };
  const { instance: p, navigations } = page('pages/index/index.js', api);
  await p.load();
  assert.equal(p.data.nextBooking.id, 51);
  assert.equal(queries, 0);
  p.goCoach({ currentTarget: { dataset: { id: p.data.coaches[0].coachId } } });
  assert.equal(navigations[0], '/pages/coach-detail/coach-detail?id=47');
  hasBooking = false;
  await p.load();
  assert.equal(p.data.nextBooking, null);
  assert.equal(p.data.available[0].id, 30);
  assert.equal(queries, 1);
});
test('member ledger keeps balances independent and only reloads the selected tab', async () => {
  const requests = [];
  let entriesReply;
  const http = {
    ensureLogin: () => true, captureAuthContext: () => ({}), isAuthContextCurrent: () => true,
    get: async (url, params) => {
      requests.push({ url, params });
      if (url === '/api/my/wallet') return { accounts: [{ bucket: 'GENERAL', balance_fen: 88000 }] };
      if (url === '/api/my/wallet/entries') return new Promise(resolve => { entriesReply = resolve; });
      throw new Error('明细暂不可用');
    }
  };
  const { instance: p } = page('pages/studio-ledger/studio-ledger.js', {}, http);
  p.onLoad({});
  const initial = p.onShow();
  await p.selectTab({ currentTarget: { dataset: { tab: 'history' } } });
  assert.equal(p.data.accounts[0].amount, '¥880.00');
  assert.equal(p.data.listError, '明细暂不可用');
  entriesReply([{ id: 99, delta_fen: -100 }]);
  await initial;
  assert.equal(p.data.groups.length, 0);
  assert.equal(requests.filter(item => item.url === '/api/my/wallet').length, 1);
});
test('member billing uses charge signs, paid or pending states, and actual posting dates', () => {
  const entries = memberLedger.ledgerRows([
    { id: 1, kind: 'COURSE', delta_fen: -12000, after_fen: 88000, bucket: 'GENERAL',
      business_at: '2026-09-25T18:30:00', posted_at: '2026-11-27T09:10:00' },
    { id: 2, kind: 'REVERSAL', delta_fen: 12000, after_fen: 100000, posted_at: '2026-11-27T09:11:00' }
  ], 'entries');
  assert.equal(entries[0].date, '2026-11-27');
  assert.equal(entries[0].rows[0].amount, '−¥120.00');
  assert.match(entries[0].rows[0].note, /2026-09-25/);
  assert.equal(entries[0].rows[1].amount, '+¥120.00');
  const history = memberLedger.ledgerRows([
    { id: 3, settlement: 'NO_WALLET_DEBIT', settlement_basis: 'NO_CHARGE', start_at: '2026-11-25T11:00' },
    { id: 4, settlement: 'WAITING_BALANCE', amount_fen: 28000, start_at: '2026-11-25T12:00' }
  ], 'history');
  assert.equal(history[0].rows[0].status, '未扣款');
  assert.equal(history[0].rows[1].status, '待扣费');
  assert.equal(history[0].rows[1].amount, '¥280.00');
});
test('check-in shows the studio opening time and cancellation only shows applicable fees', () => {
  const b = { status: 'BOOKED', courseDate: '2099-11-27', startTime: '18:30', endTime: '19:30' };
  assert.equal(memberBooking.bookingDisplay(b).checkinText, '18:00 开始签到');
  assert.equal(memberBooking.bookingDisplay(b).feeText, '');
  const pending = memberBooking.bookingDisplay({
    ...b, status: 'CANCELLED', cancellationTerms: { lateFeeFen: 5000, state: 'ELIGIBLE' }
  });
  assert.match(pending.feeText, /暂未扣款/);
});
test('a sold out class cannot use waitlist when the capability is disabled',async()=>{
  let joins=0;
  const api={bookingRules:async()=>({bookingMode:'FREE',waitlistEnabled:false}),
    scheduleDetail:async()=>({bookable:false,bookingStatus:'FULL'}),joinWaitlist:()=>{joins++;}};
  const {instance:p}=page('pages/booking-confirm/booking-confirm.js',api);
  p.onLoad({scheduleId:1});await p.onShow();await p.joinWaitlist();
  assert.equal(p.data.waitlistEnabled,false);assert.equal(joins,0);
});
test('member attendance uses camera-only lookup and sends the booking ID with an onsite code',async()=>{
  const code='YOYI-CHECKIN:Aa1234567890123456789012';
  let scanner,calls=[];
  const api={bookingDetail:async()=>({canCheckin:true,status:'BOOKED'}),checkin:async(...args)=>calls.push(args)};
  const {instance:p}=page('pages/checkin/checkin.js',api,undefined,{scanCode:options=>{scanner=options;}});
  await p.onLoad({bookingId:'12'});p.scan();
  assert.equal(scanner.onlyFromCamera,true);scanner.success({result:'scheduleId=99'});scanner.complete();
  assert.equal(calls.length,0);p.scan();scanner.success({result:code});scanner.complete();
  await Promise.resolve();assert.deepEqual(calls,[['12',code]]);assert.equal(p.data.checked,true);
});
test('affected WXML event bindings resolve to actual Page methods',()=>{
  for(const file of ['pages/index/index','pages/profile/profile','pages/studio-ledger/studio-ledger','pages/booking/booking','pages/booking-detail/booking-detail','pages/course/course','pages/course-detail/course-detail','pages/booking-confirm/booking-confirm','pages/private-booking/private-booking','pages/checkin/checkin','coach-end/schedule/schedule','coach-end/schedule-create/schedule-create']){
    const {instance}=page(file+'.js',{});const wxml=fs.readFileSync(path.join(__dirname,'..',file+'.wxml'),'utf8');
    for(const match of wxml.matchAll(/(?:bind|catch)(?::)?(?:tap|change|input|submit)="([A-Za-z][A-Za-z0-9_]*)"/g))assert.equal(typeof instance[match[1]],'function',file+': '+match[1]);
  }
});

test('historical zero-amount settlement refreshes without Mock or WeChat payment',async()=>{
  let loads=0;const api={createPay:async()=>({settled:true,mock:false}),mockPay:()=>assert.fail('must not use Mock payment'),orderDetail:async()=>{loads++;return {status:'PAID'};}};
  const {instance:p}=page('pages/order-detail/order-detail.js',api);p.orderId=1;await p.pay();assert.equal(loads,1);assert.equal(p.data.order.status,'PAID');
});
