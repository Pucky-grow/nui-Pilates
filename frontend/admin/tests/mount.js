import { createApp, nextTick } from 'vue';
import ElementPlus, { ElMessageBox } from 'element-plus';
import 'element-plus/dist/index.css';
import http from '../src/utils/request';
const names = ['Schedules','CourseProposals','Coaches','Members','Packages','Orders','Coupons','Articles','Feedback'];
const results = []; const requests = []; let mode = 'empty';
http.defaults.adapter = async config => {
  await new Promise(r=>setTimeout(r,15));
  requests.push({method:config.method,url:config.url,data:config.data});
  if (mode === 'error') throw new Error('测试加载失败');
  const array = /course-proposals|feedback|options|course-categories|classrooms|\/api\/courses|\/api\/coaches|\/coaches$/.test(config.url);
  return {data:{code:0,data:array?[]:{list:[],total:0}},status:200,statusText:'OK',headers:{},config};
};
const settle = async () => { for(let i=0;i<8;i++){await new Promise(r=>setTimeout(r,20));await nextTick();} };
try {
 for (const name of names) {
  const component = (await import(`../src/views/${name}.vue`)).default;
  for (mode of ['empty','error']) {
   const errors = []; const start=requests.length;
   const app=createApp(component);app.use(ElementPlus);app.config.errorHandler=e=>errors.push(String(e));
   app.mount('#root');await settle();
   if(errors.length) throw new Error(name+': '+errors.join(';'));
   if(requests.length===start) throw new Error(name+' did not request data');
   const text=document.getElementById('root').textContent;
   if(mode==='error'&&!text.includes('测试加载失败')) throw new Error(name+' missing error state');
   if(mode==='empty'&&!text.includes('暂无数据')) throw new Error(name+' missing empty state');
   results.push({page:name,mode,passed:true});app.unmount();
  }
 }

 // Exercise mounted Element Plus form state and actions; mocked HTTP stays separate from backend integration tests.
 localStorage.setItem('adminRoles', JSON.stringify(['ADMIN']));
 const optionData={courses:[{id:11,name:'器械基础'}],coaches:[{id:21,name:'王教练'},{id:22,name:'李教练'}],classrooms:[{id:31,name:'一号教室',capacity:12}],qualifications:[{courseId:11,coachId:21,status:'APPROVED'}]};
 let rows=[];let relations=[{courseId:11,coachId:21,status:'APPROVED'},{courseId:11,coachId:22,status:'DISABLED'}];
 http.defaults.adapter=async config=>{
  requests.push({method:config.method,url:config.url,data:config.data});let data;
  if(config.method==='get'&&config.url.endsWith('/schedules/options')) data=optionData;
  else if(config.method==='get'&&config.url.endsWith('/schedules')) data={list:rows,total:rows.length};
  else if(config.method==='get'&&config.url.endsWith('/coaches'))data=relations;
  else if(config.method==='post'&&config.url.endsWith('/schedules')){
   const body=JSON.parse(config.data);if('bookedCount' in body||'version' in body)throw Error('client wrote server fields');
   rows=[{...body,id:41,courseName:'器械基础',coachName:'王教练',classroomName:'一号教室',bookedCount:0,remaining:body.maxCapacity,status:'SCHEDULED',editable:true,cancellable:true,bookable:true}];data=41;
  } else if(config.method==='put'&&config.url.endsWith('/schedules/41')){const body=JSON.parse(config.data);rows[0]={...rows[0],maxCapacity:body.maxCapacity,remaining:body.maxCapacity};data=null;}
  else if(config.method==='post'&&config.url.endsWith('/schedules/41/cancel')){rows[0]={...rows[0],status:'CANCELLED',cancellable:false};data=null;}
  else if(config.method==='put'&&config.url.endsWith('/coaches/22')){relations[1].status='APPROVED';optionData.qualifications=relations.filter(r=>r.status==='APPROVED');data=null;}
  else throw Error('unexpected mounted request '+config.method+' '+config.url);
  return {data:{code:0,data},status:200,statusText:'OK',headers:{},config};
 };
 const component=(await import('../src/views/Schedules.vue')).default;
 const app=createApp(component);app.use(ElementPlus);const errors=[];app.config.errorHandler=e=>errors.push(String(e));app.mount('#root');await settle();
 const state=app._instance.setupState;
 state.openForm();state.form.courseId=11;state.form.coachId=21;state.form.classroomId=31;await nextTick();
 if(state.eligibleCoaches.length!==1||state.eligibleCoaches[0].id!==21)throw Error('qualification filtering failed');
 if(!document.body.textContent.includes('器械基础')||!document.body.textContent.includes('王教练')||!document.body.textContent.includes('一号教室'))throw Error('named choices missing');
 await state.save();await settle();if(state.list.length!==1||state.list[0].maxCapacity!==8)throw Error('create/refresh failed');results.push({operation:'create with named choices and eligible coach',passed:true});
 state.openCapacity(state.list[0]);state.capacity=10;await state.saveCapacity();await settle();if(state.list[0].maxCapacity!==10)throw Error('capacity/refresh failed');results.push({operation:'capacity edit and refresh',passed:true});
 state.openQualifications();state.qualificationCourse=11;await state.loadQualifications();await state.grant(state.qualificationRows.find(r=>r.id===22));await settle();if(!state.eligibleCoaches.some(c=>c.id===22))throw Error('qualification recovery failed');results.push({operation:'explicit qualification recovery',passed:true});
 const original=ElMessageBox.confirm;ElMessageBox.confirm=()=>Promise.resolve();await state.cancel(state.list[0]);ElMessageBox.confirm=original;await settle();if(state.list[0].status!=='CANCELLED')throw Error('cancel/refresh failed');results.push({operation:'whole cancellation and refresh',passed:true});
 if(errors.length)throw Error(errors.join(';'));app.unmount();
 window.__MOUNT_RESULT__={passed:true,results,requests};

} catch(error) {window.__MOUNT_RESULT__={passed:false,error:String(error),results,requests};}
document.body.dataset.testComplete='true';
