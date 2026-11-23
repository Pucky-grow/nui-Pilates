const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const {resolveEnvironment}=require('../config/environment');
const createTransport=require('./support/transport.cjs');
function harness(){
 const storage=new Map(),calls=[],toasts=[],app={globalData:{baseUrl:'http://127.0.0.1:8090',useMockLogin:false,mockCode:'must-not-fallback',token:'old',userType:'ADMIN',userInfo:{}}};let handler=()=>{};
 const wx={getStorageSync:k=>storage.get(k),setStorageSync:(k,v)=>storage.set(k,v),removeStorageSync:k=>storage.delete(k),showToast(o){toasts.push(o)},reLaunch(){},login:o=>o.success({code:'one-use-code'}),request:o=>{calls.push({url:o.url,data:o.data});handler(o);}};
 const module={exports:{}};vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../utils/request.js'),'utf8'),{module,wx,getApp:()=>app,Date,Promise,setTimeout,require:()=>createTransport(wx,()=>app)});
 return {storage,calls,toasts,app,wx,http:module.exports,setHandler:h=>handler=h};
}
test('trial/release and unknown environment fail closed without HTTPS and never use fixed mock identity',()=>{
 const source=fs.readFileSync(path.join(__dirname,'../config/environment.js'),'utf8');
 const fixture=source.replace(/const environments = \{[\s\S]*?\n\};/, "const environments = {\n develop: {baseUrl:'http://127.0.0.1:8090',useMockLogin:true}, trial:{baseUrl:'',useMockLogin:true,mockCode:'must-not-use'}, release:{baseUrl:'',useMockLogin:true,mockCode:'must-not-use'}\n};");
 const module={exports:{}};vm.runInNewContext(fixture,{module});const resolve=module.exports.resolveEnvironment;
 for(const name of ['trial','release','unknown']){const e=resolve(name);assert.equal(e.useMockLogin,false);assert.equal(e.mockCode,'');assert.equal(e.baseUrl,'');assert.ok(e.error);}
 assert.match(resolve('develop').baseUrl,/127\.0\.0\.1/);
});
test('logout clears member coach admin identity and in-memory profile',()=>{
 const h=harness();for(const key of ['token','roles','coachId','userType','coachToken','coachName','loginAt','coachLoginAt','coachRoles','adminToken','adminRoles','adminPerms','adminName'])h.storage.set(key,'fixture');
 h.http.clearAuth();assert.equal(h.storage.size,0);assert.equal(h.app.globalData.token,'');assert.equal(h.app.globalData.userType,'');assert.equal(h.app.globalData.userInfo,null);
 assert.equal(h.app.globalData.authEpoch,1);h.http.clearAuth();assert.equal(h.app.globalData.authEpoch,2);
});
test('valid session is reused without a new wx login',async()=>{
 const h=harness();h.storage.set('token','fixture');h.storage.set('loginAt',Date.now());h.wx.login=()=>assert.fail('unexpected login');assert.equal((await h.http.login()).token,'fixture');
});
test('signed-in member submits the offline invitation and refreshes the same WeChat login after confirmation', async () => {
 const h = harness();
 h.storage.set('token', 'online-member');
 h.storage.set('loginAt', Date.now());
 const navigation = [];
 h.wx.navigateTo = ({ url }) => navigation.push(url);
 h.wx.switchTab = ({ url }) => navigation.push(url);
 let logins = 0;
 h.wx.login = options => options.success({ code: 'current-wechat-' + (++logins) });
 const inviteCode = 'abcdefghijklmnopqrstuvwxyz123456';
 h.setHandler(request => {
  if (request.url.endsWith('/offline-member-claims')) {
   assert.equal(request.data.code, 'current-wechat-1');
   assert.equal(request.data.inviteCode, inviteCode);
   request.success({ data: { code: 0, data: { proof: 'FA01BC23' } } });
  } else {
   assert.ok(request.url.endsWith('/wx-login'));
   assert.equal(request.data.code, 'current-wechat-2');
   request.success({ data: { code: 0, data: { token: 'bound-member', roles: ['MEMBER'], userType: 'MP_USER' } } });
  }
 });
 const page = file => {
  let definition;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '..', file), 'utf8'),
   { wx: h.wx, Page: value => { definition = value; }, require: () => h.http });
  return { ...definition, setData(values) { Object.assign(this.data, values); } };
 };
 page('pages/profile/profile.js').goOfflineClaim();
 const claim = page('pages/offline-claim/offline-claim.js');
 claim.onLoad({ from: 'profile' });
 claim.onInput({ detail: { value: inviteCode } });
 await claim.submit();
 assert.equal(claim.data.proof, 'FA01BC23');
 assert.equal(h.storage.get('token'), 'online-member');
 await claim.finish();
 assert.equal(h.storage.get('token'), 'bound-member');
 assert.deepEqual(navigation, ['/pages/offline-claim/offline-claim?from=profile', '/pages/profile/profile']);
});
test('expired HTTP session clears roles and retries once with freshly authenticated identity',async()=>{
 const h=harness();h.storage.set('token','old');h.storage.set('roles',['COACH']);h.storage.set('adminToken','old-admin');let attempts=0;
 h.setHandler(o=>{if(o.url.endsWith('/wx-login')){assert.equal(h.storage.has('adminToken'),false);o.success({data:{code:0,data:{token:'new',roles:['MEMBER'],userType:'MP_USER'}}});}else o.success({data:++attempts===1?{code:40100}:{code:0,data:{ok:true}}});});
 assert.equal((await h.http.get('/api/my/bookings')).ok,true);assert.equal(attempts,2);assert.deepEqual(Array.from(h.storage.get('roles')),['MEMBER']);
});
test('failed WeChat login does not recursively retry the login endpoint',async()=>{
 const h=harness();h.setHandler(o=>o.success({data:{code:40100,message:'rejected'}}));await assert.rejects(h.http.login({force:true}));assert.equal(h.calls.length,1);
});
test('non-mock login never falls back to a fixed identity when wx.login returns no code',async()=>{
 const h=harness();h.wx.login=o=>o.success({});await assert.rejects(h.http.login());assert.equal(h.calls.length,0);
});
test('missing endpoint blocks both requests and upload before any network operation',async()=>{
 const h=harness();h.app.globalData.baseUrl='';await assert.rejects(h.http.get('/api/courses'));await assert.rejects(h.http.upload('/api/upload','fixture'));assert.equal(h.calls.length,0);
});

test('concurrent WeChat login shares one code exchange including forced callers',async()=>{
 const h=harness();let pending,loginCalls=0;h.wx.login=o=>{loginCalls++;o.success({code:'synthetic-once'})};h.setHandler(o=>pending=o);
 const first=h.http.login(),second=h.http.login({force:true});assert.equal(first,second);assert.equal(loginCalls,1);assert.equal(h.calls.length,1);
 pending.success({data:{code:0,data:{token:'synthetic-current',userType:'MP_USER',roles:['MEMBER']}}});
 assert.equal((await first).token,'synthetic-current');assert.equal((await second).token,'synthetic-current');
});

test('clearing authentication before wx.login returns prevents code exchange',async()=>{
 const h=harness();let pending;h.wx.login=o=>pending=o;const login=h.http.login();h.http.clearAuth();
 pending.success({code:'synthetic-old-code'});await assert.rejects(login,e=>e.code==='AUTH_CONTEXT_CHANGED');assert.equal(h.calls.length,0);assert.equal(h.storage.has('token'),false);
});

test('late WeChat response cannot resurrect a member session after switching to admin',async()=>{
 const h=harness();let pending;h.setHandler(o=>pending=o);const login=h.http.login();h.http.clearAuth();h.storage.set('adminToken','synthetic-new-admin');
 pending.success({data:{code:0,data:{token:'synthetic-old-member',roles:['MEMBER'],userType:'MP_USER'}}});
 await assert.rejects(login,e=>e.code==='AUTH_CONTEXT_CHANGED');assert.equal(h.storage.has('token'),false);assert.equal(h.storage.get('adminToken'),'synthetic-new-admin');
});

test('changing any target identity field prevents accepting an older login response',async()=>{
 for(const field of ['environment','transport','baseUrl','cloudEnvironment','cloudService']){
  const h=harness();let pending;h.setHandler(o=>pending=o);const login=h.http.login();h.app.globalData[field]='changed-target';
  pending.success({data:{code:0,data:{token:'must-not-store',roles:['MEMBER']}}});
  await assert.rejects(login,e=>e.code==='AUTH_CONTEXT_CHANGED');assert.equal(h.storage.has('token'),false,field);
 }
});

test('late expired response does not clear a newer staff session or trigger another login',async()=>{
 const h=harness();let pending;h.setHandler(o=>pending=o);const request=h.http.get('/api/my/bookings');h.http.clearAuth();h.storage.set('adminToken','synthetic-admin');
 pending.success({data:{code:40100}});await assert.rejects(request,e=>e.code==='AUTH_CONTEXT_CHANGED');assert.equal(h.storage.get('adminToken'),'synthetic-admin');assert.equal(h.calls.length,1);
});

test('malformed successful exchange never installs an empty session',async()=>{
 const h=harness();h.setHandler(o=>o.success({data:{code:0,data:{roles:['MEMBER']}}}));await assert.rejects(h.http.login());assert.equal(h.storage.has('token'),false);
});

test('settling an older rejected login does not release a newer single flight',async()=>{
 const h=harness();const pending=[];h.setHandler(o=>pending.push(o));const old=h.http.login();h.http.clearAuth();const current=h.http.login();
 pending[0].success({data:{code:0,data:{token:'old'}}});await assert.rejects(old,e=>e.code==='AUTH_CONTEXT_CHANGED');
 assert.equal(h.http.login({force:true}),current);assert.equal(h.calls.length,2);pending[1].success({data:{code:0,data:{token:'current'}}});assert.equal((await current).token,'current');
});

function loginPage(h){
 let definition;
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../pages/login/login.js'),'utf8'),{Page:d=>definition=d,wx:h.wx,getApp:()=>h.app,setTimeout,require:p=>p.includes('transport')?createTransport(h.wx,()=>h.app):h.http});
 return {...definition,data:{...definition.data,agreed:true},setData(value){Object.assign(this.data,value)}};
}

test('login page presents a fixed failure for business 50000 and permits retry without duplicated requests',async()=>{
 const h=harness();let pending;h.setHandler(o=>pending=o);const page=loginPage(h);
 const first=page.wechatLogin();assert.equal(page.data.wechatLoggingIn,true);assert.equal(page.wechatLogin(),undefined);assert.equal(h.calls.length,1);
 pending.success({data:{code:50000,message:'synthetic-sensitive-upstream-detail'}});await first;
 assert.equal(page.data.wechatLoggingIn,false);assert.equal(h.toasts.length,1);assert.equal(h.toasts[0].title,'微信登录暂未成功，请稍后重试');assert.equal(h.storage.has('token'),false);
 const retry=page.wechatLogin();assert.equal(h.calls.length,2);pending.success({data:{code:50000}});await retry;assert.equal(page.data.wechatLoggingIn,false);
});

test('staff login cancels an in-flight member login and accepts only its own session',async()=>{
 const h=harness(),pending=[];h.setHandler(o=>pending.push(o));const page=loginPage(h);page.data.username='synthetic-admin';page.data.password='synthetic-password';
 const member=page.wechatLogin();page.accountLogin();assert.equal(pending.length,2);
 pending[0].success({data:{code:0,data:{token:'old-member',roles:['MEMBER']}}});await member;
 pending[1].success({data:{code:0,data:{token:'new-admin',roles:['ADMIN'],permissions:[]}}});pending[1].complete();
 assert.equal(h.storage.has('token'),false);assert.equal(h.storage.get('adminToken'),'new-admin');assert.equal(page.data.loggingIn,false);assert.equal(page.data.password,'');assert.equal(h.toasts.length,0);
});

test('staff response from an older authentication generation cannot install credentials',()=>{
 const h=harness();let pending;h.setHandler(o=>pending=o);const page=loginPage(h);page.data.username='synthetic-admin';page.data.password='synthetic-password';page.accountLogin();h.http.clearAuth();
 pending.success({data:{code:0,data:{token:'old-admin',roles:['ADMIN']}}});pending.complete();assert.equal(h.storage.has('adminToken'),false);assert.equal(page.data.loggingIn,false);
});

test('coach password login cancels pending member login and rejects an old coach response',async()=>{
 const h=harness(),pending=[];h.setHandler(o=>pending.push(o));const member=h.http.login();const module={exports:{}};
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../coach-end/api.js'),'utf8'),{module,wx:h.wx,getApp:()=>h.app,Date,Promise,setTimeout,require:p=>p.includes('transport')?createTransport(h.wx,()=>h.app):h.http});
 const coach=module.exports.login('synthetic-coach','synthetic-password');
 pending[0].success({data:{code:0,data:{token:'old-member',roles:['MEMBER']}}});await assert.rejects(member,e=>e.code==='AUTH_CONTEXT_CHANGED');h.http.clearAuth();
 pending[1].success({data:{code:0,data:{token:'old-coach',roles:['COACH']}}});await assert.rejects(coach,e=>e.code==='AUTH_CONTEXT_CHANGED');assert.equal(h.storage.has('token'),false);assert.equal(h.storage.has('coachToken'),false);
});
