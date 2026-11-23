const u = require('../common');
Page({ data:{ isAdmin:false, rows:[], page:1, more:false, busy:false, error:'', form:{username:'',name:'',password:'',role:'STAFF'}, own:{oldPassword:'',newPassword:''}, fields:[{key:'username',label:'账号',required:true,maxlength:32},{key:'name',label:'姓名',required:true,maxlength:32},{key:'password',label:'初始口令（至少14字符）',type:'password',maxlength:72},{key:'role',label:'角色',type:'select',options:[u.option('STAFF','前台'),u.option('STORE_MANAGER','店长'),u.option('COACH','教练')]}], ownFields:[{key:'oldPassword',label:'当前口令',type:'password',maxlength:72},{key:'newPassword',label:'新口令（至少14字符）',type:'password',maxlength:72}] },
 onLoad(){if(!u.guard())return;const admin=(wx.getStorageSync('adminRoles')||[]).includes('ADMIN');this.setData({isAdmin:admin});if(admin){this.load();u.api.get('/admin/coaches',{page:1,size:100}).then(d=>{const options=(d.list||d.records||[]).filter(c=>!c.sysUserId&&c.status==='ACTIVE').map(c=>u.option(c.id,c.name));this.setData({fields:[...this.data.fields,{key:'coachId',label:'创建教练账号时选择未绑定的教练档案',type:'select',options}]});}).catch(()=>{});}},
 load(){return u.run(this,()=>this.readAccounts());},
 async readAccounts(){
  const d=await u.api.get('/admin/accounts',{page:this.data.page,size:20});
  const roleNames={ADMIN:'管理员',STORE_MANAGER:'店长',STAFF:'前台',COACH:'教练'};
  this.setData({rows:d.list.map(row=>({...row,roleText:(row.roles||[]).map(role=>roleNames[role]||role).join('、')})),more:this.data.page*20<d.total});
 },
 next(){this.setData({page:this.data.page+1});this.load();},prev(){this.setData({page:Math.max(1,this.data.page-1)});this.load();},
 change(e){this.setData({['form.'+e.detail.key]:e.detail.value});}, ownChange(e){this.setData({['own.'+e.detail.key]:e.detail.value});},
 create(){return u.run(this,async()=>{
  if(this.data.form.role==='COACH'&&!this.data.form.coachId){wx.showToast({title:'请选择未绑定的教练档案',icon:'none'});return;}
  const id=await u.api.post('/admin/accounts',this.data.form);
  this.setData({'form.password':'',createdId:id});
  await this.readAccounts();
  u.success();
 });},
 status(e){return u.run(this,async()=>{const row=this.data.rows.find(r=>r.id===Number(e.currentTarget.dataset.id));if(!await u.confirm('账号状态','更改后旧会话失效，确认继续？'))return;await u.api.put('/admin/accounts/'+row.id+'/status',{status:row.status==='ACTIVE'?'DISABLED':'ACTIVE'});u.success();}).then(()=>this.load());},
 reset(e){const id=Number(e.currentTarget.dataset.id);this.setData({resetId:id,resetPassword:''});}, resetInput(e){this.setData({resetPassword:e.detail.value});},cancelReset(){this.setData({resetId:null,resetPassword:''});},
 saveReset(){return u.run(this,async()=>{await u.api.post('/admin/accounts/'+this.data.resetId+'/password',{password:this.data.resetPassword});this.cancelReset();u.success();});},
 password(){return u.run(this,async()=>{await u.api.post('/api/auth/password',this.data.own);this.setData({own:{oldPassword:'',newPassword:''}});require('../../utils/request').clearAuth();wx.reLaunch({url:'/pages/login/login'});});}
});
