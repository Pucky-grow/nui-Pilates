#!/usr/bin/env python3
"""Authentication and read-endpoint smoke checks on the owned loopback stack; no external URL."""
import importlib.util, json, pathlib, re, subprocess, sys, urllib.request
ROOT=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('local_stack',ROOT/'scripts/dev-local.py')
local=importlib.util.module_from_spec(spec);spec.loader.exec_module(local)
state=local.read('status.json')
if state.get('status')!='running' or not local.live(state.get('pid')):raise SystemExit('Start the owned local stack first')
# Confirm owned datadir before even issuing the login request.
result=subprocess.run([str(local.MYSQL/'mysql'),'--defaults-file='+str(local.STATE/'client.cnf'),'--protocol=SOCKET','--user=root','--batch','--skip-column-names','-e','SELECT @@datadir'],capture_output=True,text=True)
if result.returncode or pathlib.Path(result.stdout.strip()).resolve()!=(local.STATE/'mysql-data').resolve():raise SystemExit('Owned datadir check failed')
report={'isolated':True,'endpoint':'http://127.0.0.1:8090','status':'running','checks':[],'wechatDeviceAcceptance':'pending'}
def call(method,path,body=None,token=None):
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    req=urllib.request.Request('http://127.0.0.1:8090'+path,method=method,headers=headers,data=json.dumps(body).encode() if body is not None else None)
    with urllib.request.urlopen(req,timeout=10) as response:value=json.load(response)
    if value.get('code')!=0:raise RuntimeError('Request failed: '+path+' code='+str(value.get('code')))
    report['checks'].append({'method':method,'path':path,'status':'passed'})
    return value.get('data')
try:
    rules=call('GET','/api/booking-rules');assert rules['bookingMode']=='FREE'
    # Only the initial fake local test account; never print or store its credentials/token.
    seed=(ROOT/'database/init_data.sql').read_text()
    password=re.search(r'密码均\s+([A-Za-z0-9]+)',seed).group(1)
    login=call('POST','/api/auth/admin-login',{'username':'admin','password':password});token=login['token']
    try:
        for path in ['/api/auth/me','/api/admin/workspace/summary','/api/admin/members','/api/admin/bookings','/api/admin/courses','/api/admin/classrooms','/api/admin/schedules/options','/api/admin/workspace/rules','/api/admin/workspace/logs']:
            call('GET',path,token=token)
    finally:call('POST','/api/auth/logout',token=token)
    report['status']='passed'
except Exception as error:
    report['status']='failed';report['reason']=type(error).__name__ # no response/credentials in output
print(json.dumps(report,ensure_ascii=False,indent=2))
sys.exit(0 if report['status']=='passed' else 1)
