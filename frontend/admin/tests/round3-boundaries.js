import {createApp,nextTick} from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import Dashboard from '../src/views/Dashboard.vue';
import Orders from '../src/views/Orders.vue';
import http from '../src/utils/request';
const results=[];
try{
 for(const [Component,role] of [[Dashboard,'STAFF'],[Orders,'STAFF'],[Orders,'ADMIN']]){
  localStorage.setItem('adminRoles',JSON.stringify([role]));localStorage.setItem('adminPerms',JSON.stringify(role==='STAFF'?['dashboard:view','order:view']:[]));
  const requests=[];http.defaults.adapter=async config=>{requests.push(config.url);return {status:200,statusText:'OK',headers:{},config,data:{code:0,data:config.url.includes('/statistics/dashboard')?{todayBookings:1,todayFinished:0,todayNewMembers:0,monthNewMembers:0,monthBookings:1,totalMembers:1}:{list:[{id:1,orderNo:'isolated',status:'PAID',...(role==='ADMIN'?{payAmount:123}:{})}],total:1}}};};
  const app=createApp(Component);app.use(ElementPlus);app.mount('#root');await nextTick();await new Promise(r=>setTimeout(r,150));
  const text=document.body.innerText;
  if(role==='STAFF'&&(text.includes('营业额')||text.includes('实付')||text.includes('退款')||text.includes('undefined')))throw Error('staff finance presentation leak');
  if(Component===Dashboard&&requests.some(x=>x.includes('revenue-trend')||x.includes('top-courses')))throw Error('dashboard requests forbidden statistics');
  if(role==='ADMIN'&&!text.includes('实付'))throw Error('admin historical amount query lost');
  if(role==='ADMIN'&&text.includes('退款'))throw Error('FREE release exposed new refund action');
  results.push({operation:Component===Dashboard?'STAFF dashboard without finance requests':role+' order fields and actions',passed:true});app.unmount();
 }
 window.__MOUNT_RESULT__={passed:true,results};
}catch(e){window.__MOUNT_RESULT__={passed:false,error:String(e),results};}
