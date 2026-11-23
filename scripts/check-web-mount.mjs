import { spawn } from 'node:child_process';
import { mkdtemp, rm, readFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
const profile=await mkdtemp(path.join(os.tmpdir(),'pilates-chrome-'));
const executable=process.env.CHROME_BIN || (process.platform==='darwin'?'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome':'chromium');
const chrome=spawn(executable,['--headless=new','--no-first-run','--no-default-browser-check','--disable-background-networking','--remote-debugging-port=0','--user-data-dir='+profile,'about:blank'],{stdio:['ignore','ignore','pipe']});
let ws;
try {
 const endpoint=await new Promise((resolve,reject)=>{let output='';const timeout=setTimeout(()=>reject(new Error('Chrome startup timeout')),15000);chrome.stderr.on('data',chunk=>{output+=chunk;const m=output.match(/DevTools listening on (ws:\/\/[^\s]+)/);if(m){clearTimeout(timeout);resolve(m[1]);}});chrome.on('error',reject);chrome.on('exit',code=>reject(new Error('Chrome exited '+code+' '+output.slice(-500))));});
 ws=new WebSocket(endpoint);await new Promise((resolve,reject)=>{ws.addEventListener('open',resolve,{once:true});ws.addEventListener('error',reject,{once:true});});
 let counter=0;const pending=new Map();ws.onmessage=event=>{const message=JSON.parse(event.data);if(message.id&&pending.has(message.id)){const p=pending.get(message.id);pending.delete(message.id);message.error?p.reject(new Error(JSON.stringify(message.error))):p.resolve(message.result);}};
 const call=(method,params={},sessionId)=>new Promise((resolve,reject)=>{const id=++counter;const timeout=setTimeout(()=>{pending.delete(id);reject(new Error('CDP command timed out: '+method));},10000);pending.set(id,{resolve:v=>{clearTimeout(timeout);resolve(v);},reject:e=>{clearTimeout(timeout);reject(e);}});ws.send(JSON.stringify({id,method,params,sessionId}));});
 const {targetId}=await call('Target.createTarget',{url:'about:blank'});
 const {sessionId}=await call('Target.attachToTarget',{targetId,flatten:true});
 await call('Page.enable',{},sessionId);
 if(process.env.PILATES_LOCAL_TLS==='true'){if(!/^https:\/\/127\.0\.0\.1:/.test(process.env.MOUNT_URL))throw Error('TLS bypass only allowed for owned loopback');await call('Security.setIgnoreCertificateErrors',{ignore:true},sessionId);}
 if(process.env.PILATES_BROWSER_DRIVER)await call('Page.addScriptToEvaluateOnNewDocument',{source:await readFile(process.env.PILATES_BROWSER_DRIVER,'utf8')},sessionId);
 if(process.env.PILATES_BROWSER_INPUT)await call('Page.addScriptToEvaluateOnNewDocument',{source:'window.__ACCEPTANCE_INPUT__='+process.env.PILATES_BROWSER_INPUT},sessionId);
 await call('Page.navigate',{url:process.env.MOUNT_URL || 'http://127.0.0.1:5179/tests/mount.html'},sessionId);
 let result;
 for(let i=0;i<200;i++){await new Promise(r=>setTimeout(r,250));const response=await call('Runtime.evaluate',{expression:'window.__MOUNT_RESULT__ || null',returnByValue:true},sessionId);if(response.result.value){result=response.result.value;break;}}
 if(!result) {const dom=await call('Runtime.evaluate',{expression:'document.body.innerText',returnByValue:true},sessionId);throw new Error('Mount timed out: '+dom.result.value);}
 console.log(JSON.stringify(result,null,2));if(!result.passed) process.exitCode=1;
} catch(error) {console.error(error);process.exitCode=1;}
finally {if(ws)ws.close();if(chrome.exitCode===null){chrome.kill('SIGTERM');await Promise.race([new Promise(r=>chrome.once('exit',r)),new Promise(r=>setTimeout(r,3000))]);if(chrome.exitCode===null)chrome.kill('SIGKILL');}await rm(profile,{recursive:true,force:true,maxRetries:10,retryDelay:100});}
