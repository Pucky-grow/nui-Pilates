#!/usr/bin/env python3
"""Exercise only the labelled uni-test fixtures through authorized native WeChat APIs.
Run book, then checkin when the real 30-minute check-in window opens. Never changes rules.
"""
import argparse,json,subprocess,datetime
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--phase',choices=['book','checkin'],required=True);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
fixtures=json.loads((ROOT/'docs/evidence/wechat/20261107-experience/test-fixtures.json').read_text())
if fixtures.get('status')!='created' or not fixtures.get('synthetic') or fixtures.get('environment')!='uni-test-d5gwbyymua6bf2ada':raise SystemExit('STOP: reviewed synthetic fixtures required')
future=next(x for x in fixtures['schedules'] if x['purpose']=='phone-preview');near=next(x for x in fixtures['schedules'] if x['purpose']=='checkin-acceptance')
source=(ROOT/'scripts/cloudrun/wechat_native_real_login_probe.js').read_text();source=source[source.index('function ()'):].strip()
source=source.replace('__yoyiNativeWechatLoginProbe20261106','__yoyiNativeBookingAcceptance20261107').replace("probeType: 'devtools-native-wechat-to-cloud-login'","probeType: 'native-wechat-booking-acceptance'")
source=source.replace('const request = (path, data, auth)', 'const request = (path, data, auth, method)').replace("method: data === undefined ? 'GET' : 'POST'", "method: method || (data === undefined ? 'GET' : 'POST')")
extra='''
    .then(step('fixture-booking-acceptance', () => {
      const phase=PHASE, future=FUTURE, near=NEAR;
      report.bookingOrAttendanceWritten=false; report.phoneCameraTested=false;
      let booked=null;
      const ok=(v)=>{expect(v,0);return v.data;};
      const assertStatus=(id,status)=>request('/api/bookings/'+id,undefined,token).then(ok).then(v=>{if(v.status!==status)throw new Error('unexpected-booking-state');});
      const book=(id)=>request('/api/bookings',{scheduleId:id,remark:'联调自动验收-20261107'},token).then(ok).then(v=>{if(!v.bookingId)throw new Error('missing-booking-id');report.bookingOrAttendanceWritten=true;return v.bookingId;});
      return Promise.all([future,near].map(id=>request('/api/course-schedules/'+id,undefined,token).then(ok).then(v=>{
        if(!String(v.courseName||'').startsWith('联调测试'))throw new Error('not-labelled-fixture');
      }))).then(()=>{
        if(phase==='checkin')return request('/api/my/bookings',undefined,token).then(ok).then(rows=>{
          const row=rows.find(v=>v.scheduleId===near && v.remark==='联调自动验收-20261107' && ['BOOKED','SIGNED_IN'].includes(v.status));
          if(!row)throw new Error('acceptance-booking-not-found');
          return request('/api/attendance/check-in?scheduleId='+near,{},token).then(ok)
            .then(()=>{report.bookingOrAttendanceWritten=true;return assertStatus(row.id,'SIGNED_IN');})
            .then(()=>request('/api/attendance/check-in?scheduleId='+near,{},token)).then(ok)
            .then(()=>assertStatus(row.id,'SIGNED_IN')).then(()=>{report.checkinAndRepeatStatusPassed=true;});
        });
        return book(future).then(id=>{booked=id;return assertStatus(id,'BOOKED');})
          .then(()=>request('/api/bookings',{scheduleId:future,remark:'联调自动验收-20261107'},token))
          .then(v=>expect(v,41005)).then(()=>request('/api/bookings/'+booked,undefined,token,'DELETE')).then(ok)
          .then(()=>assertStatus(booked,'CANCELLED')).then(()=>{booked=null;return book(future);})
          .then(id=>{booked=id;return assertStatus(id,'BOOKED');})
          .then(()=>request('/api/bookings/'+booked,undefined,token,'DELETE')).then(ok)
          .then(()=>assertStatus(booked,'CANCELLED')).then(()=>{booked=null;report.bookDuplicateCancelRebookPassed=true;return book(near);})
          .then(id=>assertStatus(id,'BOOKED')).then(()=>{report.pendingCheckinFixture=true;});
      }).catch(error=>{if(booked)return request('/api/bookings/'+booked,undefined,token,'DELETE').then(v=>{
        report.failedRunBookingCleanupSucceeded=v.code===0;throw error;
      });throw error;});
    }))
'''.replace('PHASE',json.dumps(a.phase)).replace('FUTURE',str(future['id'])).replace('NEAR',str(near['id']))
source=source.replace("    .then(step('logout-and-session-revocation',",extra+"    .then(step('logout-and-session-revocation',")
target=a.report.resolve()
if not target.is_relative_to(ROOT/'docs/evidence/wechat') or target.exists():raise SystemExit('STOP: new evidence path required')
r=subprocess.run(['wechatide','-c','Codex','automation_evaluate','--project',str(ROOT),'--fn-source',source],capture_output=True,text=True,timeout=75)
payload=json.loads(r.stdout[r.stdout.index('{'):])
def find(v):
 if isinstance(v,dict):
  if v.get('probeType')=='native-wechat-booking-acceptance':return v
  for x in v.values():
   found=find(x)
   if found:return found
 return None
report=find(payload) or {'status':'tool-failed','reason':payload.get('reason'),'taskId':payload.get('taskId')}
report['phase']=a.phase;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');target.chmod(0o600)
print(json.dumps({k:report.get(k) for k in ['status','phase','failedStage','failureBusinessCode','bookDuplicateCancelRebookPassed','checkinAndRepeatStatusPassed']}))
raise SystemExit(0 if report['status']=='passed-scoped' else 1)
