#!/usr/bin/env python3
"""Read-only operational checks; no outbound notification and no Redis/database mutation."""
import argparse,datetime,json,pathlib,shutil,socket,ssl,urllib.request
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--internal-base',required=True);p.add_argument('--data-directory',required=True,type=pathlib.Path);p.add_argument('--backup-manifest',required=True,type=pathlib.Path);p.add_argument('--tls-host');p.add_argument('--tls-port',default=443,type=int);p.add_argument('--max-backup-age-hours',default=24,type=int);a=p.parse_args();results=[]
def check(name,fn):
 try:detail=fn();results.append({'name':name,'status':'passed' if detail else 'failed'})
 except Exception:results.append({'name':name,'status':'failed'})
def health(kind):
 with urllib.request.urlopen(a.internal_base+'/actuator/health/'+kind,timeout=10) as response:return json.load(response).get('status')=='UP'
check('liveness',lambda:health('liveness'));check('database-and-redis-readiness',lambda:health('readiness'))
check('disk-over-15-percent-free',lambda:(lambda d:d.free/d.total>.15)(shutil.disk_usage(a.data_directory)))
def backup():
 d=json.loads(a.backup_manifest.read_text());age=(datetime.datetime.now(datetime.timezone.utc)-datetime.datetime.fromisoformat(d['checkpointUtc'])).total_seconds();return d['status']=='passed' and age<a.max_backup_age_hours*3600
check('recent-successful-encrypted-backup',backup)
if a.tls_host:
 def certificate():
  with socket.create_connection((a.tls_host,a.tls_port),timeout=10) as tcp:
   with ssl.create_default_context().wrap_socket(tcp,server_hostname=a.tls_host) as tls:return ssl.cert_time_to_seconds(tls.getpeercert()['notAfter'])-datetime.datetime.now().timestamp()>14*86400
 check('trusted-certificate-more-than-14-days-left',certificate)
print(json.dumps({'checks':results,'externalAlerts':'not configured; no messages sent'},indent=2));raise SystemExit(1 if any(r['status']!='passed' for r in results) else 0)
