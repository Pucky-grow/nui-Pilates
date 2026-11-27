// Actual built Web UI + real HTTPS backend. No HTTP adapters or mock responses.
(async()=>{
 const wait=async p=>{for(let i=0;i<200;i++){if(p())return;await new Promise(r=>setTimeout(r,100));}throw Error('Web UI timeout');};
 const input=(field,value)=>{field.value=value;field.dispatchEvent(new Event('input',{bubbles:true}));};
 const click=text=>{const button=Array.from(document.querySelectorAll('button')).find(b=>b.textContent.trim()===text);if(!button)throw Error('Missing button '+text);button.click();};
 const route=path=>{history.pushState(null,'',path);window.dispatchEvent(new PopStateEvent('popstate'));};
 try{
  await wait(()=>document.querySelectorAll('input').length>=2);const values=window.__ACCEPTANCE_INPUT__;const fields=document.querySelectorAll('input');input(fields[0],values.username);input(fields[1],values.password);document.querySelector('button').click();await wait(()=>sessionStorage.getItem('adminToken'));
  route('/system');await wait(()=>document.querySelectorAll('.el-input-number input').length===5);click('保存并生效');await wait(()=>document.body.innerText.includes('已保存'));
  route('/accounts');await wait(()=>document.querySelectorAll('.el-form').length===2);const form=document.querySelectorAll('.el-form')[1];const account=form.querySelectorAll('input');input(account[0],'browser_staff');input(account[1],'浏览器合成前台');input(account[2],values.testPassword);click('创建');await wait(()=>document.body.innerText.includes('browser_staff'));
  window.__MOUNT_RESULT__={passed:true,results:[{operation:'built Web login through HTTPS proxy',passed:true},{operation:'real persisted booking settings form',passed:true},{operation:'safe staff account creation through real UI/API',passed:true}],requests:performance.getEntriesByType('resource').map(r=>r.name).filter(n=>n.includes('/api/')).map(n=>new URL(n).pathname)};
 }catch(error){window.__MOUNT_RESULT__={passed:false,error:String(error),route:location.pathname,visible:document.body.innerText.slice(0,2500)};}
})();
