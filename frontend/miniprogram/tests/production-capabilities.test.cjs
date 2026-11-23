const test=require('node:test');const assert=require('node:assert/strict');const vm=require('node:vm');const fs=require('node:fs');const path=require('node:path');
const createTransport=require('./support/transport.cjs');
test('FREE package entry retains owned history without requesting the sales catalogue',async()=>{
 let definition;let sales=0;const history=[{id:7,name:'历史课包',remainingCredits:2,totalCredits:10,status:'EXPIRED'}];
 const context={Page:p=>definition=p,require:name=>name.includes('services')?{myPackages:async()=>history,packages:async()=>{sales++;return[];}}:{ensureLogin:()=>true,get:async()=>({packageSales:false})},wx:{}};
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../pages/package-list/package-list.js'),'utf8'),context);
 const page={data:{},setData(value){Object.assign(this.data,value);}};definition.onShow.call(page);await new Promise(resolve=>setImmediate(resolve));assert.equal(sales,0);assert.equal(page.data.history,history);assert.equal(page.data.packageSales,false);assert.equal(page.data.loading,false);
});
test('admin account page reads the real list response field and preserves paging',async()=>{
 let definition;const rows=[{id:1,name:'合成账号'}];
 const u={option:(value,label)=>({value,label}),run:(page,fn)=>fn(),api:{get:async()=>({list:rows,total:21})}};
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../admin/accounts/accounts.js'),'utf8'),{Page:p=>definition=p,require:()=>u,wx:{}});
 const page={...definition,data:{page:1},setData(v){Object.assign(this.data,v);}};
 await page.load();assert.equal(page.data.rows[0].id,rows[0].id);assert.equal(page.data.more,true);
});
for(const channel of ['admin','coach-end'])test(channel+' upload returns readable preview URL instead of object reference',async()=>{
 const readable='https://isolated.example.test/media/public/synthetic-object';
 const wx={getStorageSync:key=>key==='roles'?['COACH']:'synthetic',showLoading(){},hideLoading(){},uploadFile(options){options.success({data:JSON.stringify({code:0,data:{url:'/media/ref/synthetic-object',previewUrl:readable}})});options.complete?.();}};
 const module={exports:{}};
 const getApp=()=>({globalData:{baseUrl:'https://isolated.example.test'}});
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../'+channel+'/api.js'),'utf8'),{module,exports:module.exports,getApp,wx,require:p=>p.includes('transport')?createTransport(wx,getApp):{}});
 assert.equal(await module.exports.upload('/api/upload','local-fixture.png'),readable);
});
test('member avatar uses signed preview without adding a second origin',async()=>{
 let definition;let saved;const preview='https://isolated.example.test/media/access/synthetic?expires=1&signature=fixture';
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../pages/profile/profile.js'),'utf8'),{Page:p=>definition=p,getApp:()=>({globalData:{baseUrl:'https://isolated.example.test'}}),require:name=>name.includes('services')?{updateProfile:async body=>{saved=body;}}:{upload:async()=>({url:'/media/ref/synthetic',previewUrl:preview})},wx:{showLoading(){},hideLoading(){},showToast(){}}});
 definition.uploadAvatar.call({loadProfile(){}},'local-fixture.png');await new Promise(resolve=>setImmediate(resolve));assert.equal(saved.avatarUrl,preview);
});
