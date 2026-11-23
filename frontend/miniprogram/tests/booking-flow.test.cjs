const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const util = require('../utils/util');
function page(file, api, http={login:()=>Promise.resolve()}, wxOverrides={}) {
  let definition; const navigations=[];
  const wx={showToast(){},redirectTo:x=>navigations.push(x.url),navigateTo:x=>navigations.push(x.url),navigateBack(){},...wxOverrides};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'..',file),'utf8'),{Page:p=>definition=p,require:name=>name.includes('services/api')||name==='../api'?api:name.includes('request')?http:util,wx,console,Date,Promise,setTimeout});
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
test('course failures are distinct from empty results and date selection follows configured window',async()=>{
  const api={bookingRules:async()=>({bookingMode:'FREE',advanceDays:3}),schedules:async()=>{throw {message:'服务不可用'};}};
  const {instance:p}=page('pages/course/course.js',api);await p.onShow();assert.equal(p.data.days.length,4);assert.equal(p.data.error,'服务不可用');assert.equal(p.data.loading,false);
});
test('unbookable confirmation cannot submit',async()=>{
  let calls=0;const api={bookingRules:async()=>({bookingMode:'FREE'}),scheduleDetail:async()=>({bookable:false,bookingStatus:'DEADLINE'}),createBooking:()=>calls++};
  const {instance:p}=page('pages/booking-confirm/booking-confirm.js',api);p.onLoad({scheduleId:1});await p.onShow();await p.submit();assert.equal(calls,0);
});
test('a delayed booking response cannot replace the selected status and network errors can be retried',async()=>{
  const pending=[];
  const api={myBookings:status=>new Promise((resolve,reject)=>pending.push({status,resolve,reject}))};
  const {instance:p}=page('pages/booking/booking.js',api);
  const select=status=>p.onFilter({currentTarget:{dataset:{v:status}}});
  const first=select('BOOKED'), second=select('CANCELLED');
  pending[1].resolve([{status:'CANCELLED'}]);await second;
  pending[0].resolve([{status:'BOOKED'}]);await first;
  assert.equal(p.data.bookings[0].status,'CANCELLED');
  const failed=p.load();pending[2].reject({message:'网络异常'});await failed;
  assert.equal(p.data.error,'网络异常');assert.equal(p.data.loading,false);
  const retry=p.load();pending[3].resolve([]);await retry;
  assert.equal(p.data.error,'');assert.equal(p.data.bookings.length,0);
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
  for(const file of ['pages/course/course','pages/course-detail/course-detail','pages/booking-confirm/booking-confirm','pages/private-booking/private-booking','pages/checkin/checkin','coach-end/schedule/schedule']){
    const {instance}=page(file+'.js',{});const wxml=fs.readFileSync(path.join(__dirname,'..',file+'.wxml'),'utf8');
    for(const match of wxml.matchAll(/(?:bind|catch)(?::)?(?:tap|change|input|submit)="([A-Za-z][A-Za-z0-9_]*)"/g))assert.equal(typeof instance[match[1]],'function',file+': '+match[1]);
  }
});

test('historical zero-amount settlement refreshes without Mock or WeChat payment',async()=>{
  let loads=0;const api={createPay:async()=>({settled:true,mock:false}),mockPay:()=>assert.fail('must not use Mock payment'),orderDetail:async()=>{loads++;return {status:'PAID'};}};
  const {instance:p}=page('pages/order-detail/order-detail.js',api);p.orderId=1;await p.pay();assert.equal(loads,1);assert.equal(p.data.order.status,'PAID');
});
