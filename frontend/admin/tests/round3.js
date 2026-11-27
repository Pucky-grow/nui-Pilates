import { createApp, nextTick } from 'vue';
import { createPinia } from 'pinia';
import Login from '../src/views/Login.vue';
import router from '../src/router';
import ElementPlus, { ElMessageBox } from 'element-plus';
import 'element-plus/dist/index.css';
import Schedules from '../src/views/Schedules.vue';
import http from '../src/utils/request';
const input=window.__ACCEPTANCE_INPUT__ || {}, memberToken=input.memberToken;
sessionStorage.clear();
const results=[],requests=[],errors=[];
http.interceptors.request.use(config=>{requests.push({method:config.method,url:config.url});return config;});
const app=createApp(Schedules);app.use(ElementPlus);app.config.errorHandler=e=>errors.push(String(e));
const settle=async()=>{await nextTick();await new Promise(r=>setTimeout(r,50));await nextTick();};
try {
 // Exercise the real Login component and auth store; credentials arrive through CDP, never a URL.
 const loginApp=createApp(Login);loginApp.use(createPinia());loginApp.use(router);loginApp.use(ElementPlus);loginApp.mount('#root');
 await settle(); const fields=document.querySelectorAll('input');
 for(const [field,value] of [[fields[0],input.username],[fields[1],input.password]]){field.value=value;field.dispatchEvent(new Event('input',{bubbles:true}));}
 await settle(); document.querySelector('button').click();
 for(let i=0;i<100&&!sessionStorage.getItem('adminToken');i++)await new Promise(r=>setTimeout(r,50));
 if(!sessionStorage.getItem('adminToken'))throw Error('real login failed');
 results.push({operation:'Login component / real credential verification / Vite API proxy',passed:true});
 const managerToken=sessionStorage.getItem('adminToken'); loginApp.unmount();
 app.mount('#root');const state=app._instance.setupState;await state.initialize();await settle();
 if(state.loadError||state.optionError)throw Error(state.loadError||state.optionError);
 state.qualificationCourse=state.options.courses[0].id;await state.loadQualifications();
 let coach=state.qualificationRows[0];await state.disableQualification(coach);await state.loadQualifications();
 if(state.qualificationRows[0].status!=='DISABLED')throw Error('qualification disable failed');
 await state.grant(state.qualificationRows[0]);if(state.qualificationError)throw Error(state.qualificationError);
 if(state.qualificationRows[0].status!=='APPROVED')throw Error('qualification recovery failed');
 const unassigned=state.qualificationRows.find(r=>r.status==='未指派');if(!unassigned)throw Error('unassigned qualification fixture missing');await state.grant(unassigned);if(state.qualificationError)throw Error(state.qualificationError);
 if(!state.qualificationRows.some(r=>r.id===unassigned.id&&r.status==='APPROVED'))throw Error('explicit assignment failed');
 results.push({operation:'explicit single-course qualification assignment, disable and recovery',passed:true});
 state.openForm();state.form.courseId=state.options.courses[0].id;state.form.coachId=state.eligibleCoaches[0].id;state.form.classroomId=state.options.classrooms[0].id;
 state.form.startTime='12:00:00';state.form.endTime='13:00:00';await settle();await state.save();await settle();
 if(state.saveError)throw Error(state.saveError);
 let row=state.list.find(r=>r.startTime.startsWith('12:00'));if(!row||row.maxCapacity!==8||!row.bookable)throw Error('live create failed');
 results.push({operation:'STORE_MANAGER creates named future schedule',passed:true});
 state.openCapacity(row);state.capacity=10;await state.saveCapacity();if(state.saveError)throw Error(state.saveError);row=state.list.find(r=>r.id===row.id);if(row.maxCapacity!==10)throw Error('live capacity edit failed');results.push({operation:'capacity expansion to 10',passed:true});
 const scheduleId=row.id;
 sessionStorage.setItem('adminToken',memberToken);
 if((await http.get('/api/my/packages')).length!==0)throw Error('fixture member unexpectedly owns package');
 const a=await http.post('/api/bookings',{scheduleId});await http.delete('/api/bookings/'+a.bookingId);
 const b=await http.post('/api/bookings',{scheduleId});await http.delete('/api/bookings/'+a.bookingId);if((await http.get('/api/bookings/'+b.bookingId)).status!=='BOOKED')throw Error('old ID affected new booking');
 await http.delete('/api/bookings/'+b.bookingId);const c=await http.post('/api/bookings',{scheduleId});
 if(new Set([a.bookingId,b.bookingId,c.bookingId]).size!==3)throw Error('history IDs reused');
 results.push({operation:'unpackaged member FREE book/cancel/rebook twice over real HTTP',passed:true});
 sessionStorage.setItem('adminToken',managerToken);await state.load();row=state.list.find(r=>r.id===scheduleId);if(row.bookedCount!==1||row.remaining!==9)throw Error('admin/member counts diverged');results.push({operation:'committed counts and remaining seats',passed:true});
 state.openForm();state.form.courseId=row.courseId;state.form.coachId=row.coachId;state.form.classroomId=row.classroomId;state.form.startTime='14:00:00';state.form.endTime='15:00:00';await state.save();if(state.saveError)throw Error(state.saveError);
 const other=state.list.find(r=>r.startTime.startsWith('14:00'));if(!other)throw Error('second live create failed');
 const confirm=ElMessageBox.confirm;ElMessageBox.confirm=()=>Promise.resolve();await state.cancel(other);ElMessageBox.confirm=confirm;
 if(state.list.find(r=>r.id===other.id).status!=='CANCELLED')throw Error('live cancellation failed');results.push({operation:'STORE_MANAGER whole cancellation over real HTTP',passed:true});
 if(errors.length)throw Error(errors.join(';'));
 const networkUrls=performance.getEntriesByType('resource').map(entry=>entry.name).filter(url=>url.startsWith(location.origin));
 if(!networkUrls.some(url=>url.includes('/api/admin/schedules')))throw Error('management API namespace was not normalized');
 window.__MOUNT_RESULT__={passed:true,results,requests,networkUrls,scheduleId,bookingId:c.bookingId};
}catch(error){window.__MOUNT_RESULT__={passed:false,error:String(error),results,requests,errors};}
finally{app.unmount();document.body.dataset.testComplete='true';}
