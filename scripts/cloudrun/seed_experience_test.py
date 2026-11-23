#!/usr/bin/env python3
"""Create labelled synthetic fixtures in the fixed, isolated uni-test service only."""
import datetime,json,urllib.request,urllib.error
from pathlib import Path
from zoneinfo import ZoneInfo
from http_smoke import ORIGIN,ROOT,NoRedirect,protected_properties
OUT=ROOT/'docs/evidence/wechat/20261107-experience'
OUT.mkdir(parents=True,exist_ok=True)
REPORT=OUT/'test-fixtures.json'
state=json.loads(REPORT.read_text()) if REPORT.exists() else {'environment':'uni-test-d5gwbyymua6bf2ada','service':'uni-test','synthetic':True,'ids':{},'schedules':[]}
opener=urllib.request.build_opener(NoRedirect()); token=None; stage='login'
def save(): REPORT.write_text(json.dumps(state,ensure_ascii=False,indent=2)+'\n');REPORT.chmod(0o600)
def api(path,body=None,method=None,timeout=20):
 headers={'Content-Type':'application/json','Accept':'application/json'}
 if token:headers['Authorization']='Bearer '+token
 req=urllib.request.Request(ORIGIN+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
 with opener.open(req,timeout=timeout) as r:v=json.loads(r.read(1024*1024))
 if v.get('code')!=0:raise RuntimeError('business-code-'+str(v.get('code')))
 return v.get('data')
def ensure(key,items,name,endpoint,body):
 found=[x for x in items if x.get('name')==name]
 if len(found)>1:raise RuntimeError('duplicate-labelled-fixture')
 value=found[0]['id'] if found else api(endpoint,body)
 state['ids'][key]=value;save();return value
try:
 stage='health';health=api('/health',timeout=60)
 if health.get('service')!='pilates-server':raise RuntimeError('not-business-application')
 stage='login'
 cfg=protected_properties(ROOT/'.secrets/cloud-acceptance/bootstrap.properties')
 login=api('/api/auth/admin-login',{'username':cfg['admin.username'],'password':cfg['admin.password']})
 if 'ADMIN' not in login.get('roles',[]):raise RuntimeError('wrong-role')
 token=login['token'];stage='capability-boundary'
 caps=api('/api/capabilities')
 if caps['bookingMode']!='FREE' or caps['paymentInitiation']:raise RuntimeError('not-free-test-mode')
 stage='category'
 cat=ensure('category',api('/api/admin/course-categories'),'联调测试分类','/api/admin/course-categories',{'code':'INTEGRATION_TEST','name':'联调测试分类','sort':999})
 stage='coach'
 coach=ensure('coach',api('/api/coaches'),'联调测试教练','/api/admin/coaches',{'name':'联调测试教练','title':'虚拟测试角色','years':0,'bio':'仅用于软件联调，不提供真实授课。','sort':999,'status':'ACTIVE'})
 stage='classroom'
 room=ensure('classroom',api('/api/admin/classrooms'),'联调测试教室','/api/admin/classrooms',{'name':'联调测试教室','type':'MAT','capacity':10,'status':'ACTIVE','remark':'软件测试使用，不是真实门店教室。'})
 stage='course'
 name='联调测试基础课（非真实授课）'
 course=ensure('course',api('/api/courses'),name,'/api/admin/courses',{'categoryId':cat,'name':name,'description':'仅供预约、取消和签到功能联调，请勿前往门店。','durationMinutes':45,'type':'GROUP','difficulty':'BEGINNER','creditsCost':1,'price':0,'notices':'免费联调记录，不代表真实授课安排。','sort':999,'status':'ON_SHELF'})
 stage='qualification';api(f'/api/admin/courses/{course}/coaches/{coach}',{},'PUT')
 # Idempotent schedules recorded by date/time; no edits to unrelated existing data.
 now=datetime.datetime.now(ZoneInfo('Asia/Shanghai')); future=(now+datetime.timedelta(days=1)).replace(hour=18,minute=0,second=0,microsecond=0)
 targets=[('phone-preview',future),('phone-preview-2',future+datetime.timedelta(days=1))]
 if not any(x['purpose']=='checkin-acceptance' for x in state['schedules']):
  near=(now+datetime.timedelta(minutes=32)).replace(second=0,microsecond=0)
  targets.insert(0,('checkin-acceptance',near))
 stage='schedules'
 for purpose,start in targets:
  if any(x['purpose']==purpose for x in state['schedules']):continue
  end=start+datetime.timedelta(minutes=45)
  if end.date()!=start.date():raise RuntimeError('cross-midnight-fixture-not-supported')
  body={'courseId':course,'coachId':coach,'classroomId':room,'courseDate':str(start.date()),'startTime':start.strftime('%H:%M:%S'),'endTime':end.strftime('%H:%M:%S'),'maxCapacity':10}
  schedule=api('/api/admin/schedules',body)
  state['schedules'].append({'purpose':purpose,'id':schedule,**body,'checkinOpensAt':(start-datetime.timedelta(minutes=30)).isoformat()});save()
 state['status']='created';state['realStudioConfigured']=False;save()
 print(json.dumps({'status':'created','synthetic':True,'ids':state['ids'],'schedules':state['schedules']},ensure_ascii=False))
except Exception as e:
 state['status']='failed';state['stage']=stage;state['failureType']=type(e).__name__;state['httpStatus']=getattr(e,'code',None);save()
 print(json.dumps({'status':'failed','stage':stage,'failureType':type(e).__name__,'safeReason':str(e) if isinstance(e,RuntimeError) else 'details-withheld'}));raise SystemExit(1)
finally:
 if token:
  try:api('/api/auth/logout',{})
  except Exception:pass
