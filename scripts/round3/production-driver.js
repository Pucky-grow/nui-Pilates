// Injected by the owned browser runner into the actual production build.
(async()=>{
 const wait=async(predicate)=>{for(let i=0;i<200;i++){if(predicate())return;await new Promise(r=>setTimeout(r,100));}throw Error('production page wait timed out');};
 try{
  await wait(()=>document.querySelectorAll('input').length>=2);
  const inputs=document.querySelectorAll('input'),v=window.__ACCEPTANCE_INPUT__;
  for(const [field,value] of [[inputs[0],v.username],[inputs[1],v.password]]){field.value=value;field.dispatchEvent(new Event('input',{bubbles:true}));}
  document.querySelector('button').click();await wait(()=>sessionStorage.getItem('adminToken'));
  history.pushState(null,'','/schedules');window.dispatchEvent(new PopStateEvent('popstate'));
  await wait(()=>performance.getEntriesByType('resource').some(r=>r.name.includes('/api/admin/schedules/options')));
  await wait(()=>document.body.innerText.includes('测试课'));
  const requests=performance.getEntriesByType('resource').map(r=>r.name).filter(u=>u.includes('/api/')).map(u=>new URL(u).pathname);
  if(!requests.includes('/api/auth/admin-login'))throw Error('production login did not use proxy');
  window.__MOUNT_RESULT__={passed:true,results:[{operation:'production build real login and /schedules SPA route through HTTPS Nginx',passed:true}],requests};
 }catch(e){window.__MOUNT_RESULT__={passed:false,error:String(e)};}
})();
