#!/usr/bin/env python3
"""Collect actual artifacts without summing duplicate runs or subchecks; no service connections."""
import collections, datetime, hashlib, json, pathlib, re, xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[2];OUT=ROOT/'server/target-round3'
def read(name):return json.loads((OUT/name).read_text())
def cases(folder):
 result={}
 for path in sorted(pathlib.Path(folder).glob('TEST-*.xml'),key=lambda p:p.stat().st_mtime):
  for node in ET.parse(path).getroot().findall('testcase'):
   key=node.get('classname')+'.'+node.get('name');status='failed' if node.find('failure') is not None or node.find('error') is not None else 'skipped' if node.find('skipped') is not None else 'passed'
   result[key]={'status':status,'artifact':str(path.relative_to(ROOT)),'name':key}
 return result
old=cases(ROOT/'server/target-round2/surefire-reports');current=cases(OUT/'surefire-reports');payment=cases(OUT/'h2/surefire-reports')
assert len(old)==67 and all(v['status']=='passed' for v in old.values()),'Round2 evidence changed; investigate, do not invent counts'
assert len(current)==59 and all(v['status']=='passed' for v in current.values()),'Expected final selected 59 JUnit methods'
assert len(payment)==3 and all(v['status']=='passed' for v in payment.values()),'Expected selected payment regression'
mysql=read('mysql8-acceptance.json');assert 'v=7.4.10' in mysql['redisBinaryVersion'],'Final Redis7 evidence required';web=read('web-acceptance.json');process=read('process-acceptance.json');operations=read('operations-check.json');mini=read('mini-syntax.json');wechat=read('wechat-environment.json')
assert mysql['status']=='passed' and web['passed'] and process['status']=='passed' and operations['status']=='passed'
mini_runs=[]
for filename,zone in [('mini-tests-utc.tap','UTC'),('mini-tests-la.tap','America/Los_Angeles')]:
 text=(OUT/filename).read_text();count=int(re.search(r'(?:ℹ|#) tests (\d+)',text).group(1));passed=int(re.search(r'(?:ℹ|#) pass (\d+)',text).group(1));assert count==passed==16
 mini_runs.append({'environment':zone,'tests':count,'passed':passed,'artifact':str((OUT/filename).relative_to(ROOT))})
rows={r['test']:r for r in map(json.loads,(OUT/'case-evidence.jsonl').read_text().splitlines())};assert len(rows)==53
sources=[
'server/src/main/java/com/yoyi/pilates/common/config/DevelopmentCapabilities.java',
'server/src/main/java/com/yoyi/pilates/PilatesApplication.java',
'server/src/main/java/com/yoyi/pilates/user/application/WxMpClient.java',
'server/src/main/java/com/yoyi/pilates/user/interfaces/PhoneController.java',
'server/src/main/java/com/yoyi/pilates/trade/application/OrderService.java',
'server/src/main/java/com/yoyi/pilates/trade/application/PaymentService.java',
'server/src/main/java/com/yoyi/pilates/trade/interfaces/AdminTradeController.java',
'server/src/main/java/com/yoyi/pilates/stats/interfaces/StatsController.java',
'server/src/main/java/com/yoyi/pilates/system/application/RbacService.java',
'server/src/main/java/com/yoyi/pilates/booking/application/StockService.java','server/src/main/java/com/yoyi/pilates/booking/application/BookingService.java',
'server/src/main/java/com/yoyi/pilates/booking/infrastructure/entity/WaitlistEntity.java',
'server/src/main/java/com/yoyi/pilates/infra/scheduler/PilatesScheduler.java',
'server/src/test/java/com/yoyi/pilates/support/BookingFixture.java',
'server/src/test/java/com/yoyi/pilates/booking/Round3AcceptanceTest.java',
'server/src/test/java/com/yoyi/pilates/course/WebSchedulingBrowserTest.java',
'server/src/test/java/com/yoyi/pilates/trade/PaymentIdempotencyTest.java',
'server/src/test/resources/reset-mysql.sql','server/src/main/resources/application.yml','server/src/main/resources/application-dev.yml','server/src/main/resources/application-prod.yml',
'database/migration_round2_permissions.sql','database/migration_round2_booking.sql','database/schema.sql','database/init_data.sql','database/check_round3_readonly.sql',
'admin/src/views/Login.vue','admin/src/views/Dashboard.vue','admin/src/views/Orders.vue','admin/tests/live.js',
'miniprogram/app.js','miniprogram/config/environment.js','miniprogram/utils/request.js','miniprogram/coach-end/api.js','miniprogram/admin/api.js','miniprogram/pages/order-detail/order-detail.js','miniprogram/project.config.json',
'deploy/docker-compose.yml','deploy/.env.example','scripts/test-isolated.py','scripts/check-web-mount.mjs']
sources+=list(str(p.relative_to(ROOT)) for p in (ROOT/'scripts/round3').glob('*') if p.is_file())
sources+=list(str(p.relative_to(ROOT)) for p in (ROOT/'server/src/test/java/com/yoyi/pilates/acceptance').glob('*.java'))
sources+=list(str(p.relative_to(ROOT)) for p in (ROOT/'admin/tests').glob('round3*') if p.is_file())
sources+=list(str(p.relative_to(ROOT)) for p in (ROOT/'miniprogram/tests').glob('*.cjs'))
hashes={path:hashlib.sha256((ROOT/path).read_bytes()).hexdigest() for path in sorted(set(sources))}
artifact_paths=['mysql8-acceptance.json','booking-show-create.txt','process-acceptance.json','operations-check.json','web-acceptance.json','vite-browser.json','nginx-browser.json','boundary-components.json','case-evidence.jsonl','mini-tests-utc.tap','mini-tests-la.tap','mini-syntax.json','local-engine-probe.json','wechat-environment.json','mysql8-target-guard.json']
artifacts={str((OUT/path).relative_to(ROOT)):hashlib.sha256((OUT/path).read_bytes()).hexdigest() for path in artifact_paths}
unit=[v for k,v in current.items() if '.Round3ConfigurationTest.' in k];db=[v for k,v in current.items() if '.Round3ConfigurationTest.' not in k]
executions=[
 {'id':'mysql8-sql','type':'real_database_ddl_and_restore','status':'passed','environment':mysql['sqlEnvironment'],'command':'PATH=<owned Redis7 bin>:$PATH PILATES_NGINX_BIN=<owned nginx> python3 scripts/round3/accept.py --mysql-bin <MySQL 8.0.46 bin>','checks':mysql['cases'][:13],'artifact':'server/target-round3/mysql8-acceptance.json'},
 {'id':'backend-mysql8','type':'real_database_integration','status':'passed','environment':'owned MySQL 8.0.46 + Redis 7.4.10; JUnit profile; schedulers disabled','command':'default owned acceptance run, then scoped Round3AcceptanceTest,RoundOneConsistencyTest,Round3HttpTest regression; deduped by method','tests':len(db),'latestScopedRegression':mysql.get('testSelection'),'scope':{'service_or_MockMvc':44,'real_tcp_http':10},'results':db},
 {'id':'configuration','type':'unit_mocks','status':'passed','environment':'JUnit/Mockito/MockEnvironment; no database or real third party','command':'same selected Maven run','tests':len(unit),'results':unit},
 {'id':'processes','type':'real_http_two_os_processes','status':'passed','environment':'two independent Java21 processes sharing owned MySQL8/Redis7','command':'accept.py -> processes.verify','result':process,'reuseNote':'two OS process booking/cancellation/restart evidence retained; latest code change only suppresses duplicate signin events, verified separately by related service/HTTP regression'},
 {'id':'stock-operations','type':'real_database_and_redis_operations','status':'passed','environment':'owned MySQL8/Redis7; backend processes stopped before apply','command':'accept.py -> reconcile-stock.py dry-run/apply and anomaly rejection','result':operations},
 {'id':'web-vite','type':'real_browser_real_http','status':'passed','environment':'Chrome155, actual Login/Schedules Vue components, real Vite proxy to owned backend','command':'Round3HttpTest.realBrowserViteProxyAndProductionNginx -> scripts/round3/web.py','result':web['vite'],'countingNote':'subchecks inside one of the 10 real HTTP JUnit methods; not additional backend tests'},
 {'id':'web-nginx','type':'real_browser_real_http_https','status':'passed','environment':'native Nginx1.28.0, production admin build, repository outer+inner proxy configs with temporary paths/ports/cert','command':'same web.py; production-driver.js','result':web['nginx'],'limitations':'not a deployed Docker image or public certificate/domain'},
 {'id':'web-components','type':'component_http_adapter','status':'passed','environment':'Chrome155 and mocked axios HTTP adapter','command':'scripts/check-web-mount.mjs against admin/tests/round3-boundaries.html','checks':web['componentAdapter']['checks'],'countingNote':'3 focused new checks, separate from reused Round2 22 and from HTTP authorization evidence'},
 {'id':'payment-regression','type':'h2_service_integration','status':'passed','environment':'H2 memory + separate owned Redis7; development mock settlement only','command':'PATH=<owned Redis7 bin>:$PATH python3 scripts/round3/supplement.py','tests':3,'results':list(payment.values()),'notEvidenceFor':'MySQL8 or real WeChat payment/callback'},
 {'id':'mini-logic','type':'node_vm_logic','status':'passed','command':'TZ=UTC / TZ=America/Los_Angeles node --test miniprogram/tests/*.test.cjs','uniqueTests':16,'runs':mini_runs,'notEvidenceFor':'WeChat developer tools or real device'},
 {'id':'mini-syntax','type':'static_syntax','status':'passed','command':'node scripts/check-miniapp.cjs','result':mini},
 {'id':'business-precheck','type':'business_database_readonly','status':'blocked','reason':'No explicitly provided read-only business connection; SQL and runbook delivered; no query or migration executed'},
 {'id':'target-container','type':'target_linux_image_runtime','status':'blocked','reason':'No Docker/Podman/Colima runtime; pinned MySQL image digest resolved, native exact 8.0.46 passed; Linux image itself not executed'},
 {'id':'wechat-tools','type':'wechat_developer_tools','status':'blocked','reason':'Tool installed but CLI login probe exit 246; no confirmed isolated test AppID/credentials/domain. No business flow executed','environment':wechat},
 {'id':'wechat-device','type':'real_wechat_device','status':'blocked','reason':'No actual test device/authorized isolated test application; manual checklist delivered'},
 {'id':'business-migration','type':'business_database_mutation','status':'skipped','reason':'Explicitly prohibited this round; no deployment/publish/real notification/real payment'}
]
report={'round':3,'generatedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'overallStatus':'partial_acceptance_completed_not_release_ready','productionDatabaseTouched':False,'productionReadonlyChecked':False,'productionMigrated':False,'deployed':False,'miniProgramPublished':False,'realNotificationsSent':False,'realPaymentsTriggered':False,
 'evidenceReuse':{'aggregation':'Unique test method results retained from full run plus final 32-method signin regression; not a sum of executions','lastScope':mysql.get('testSelection'),'eventReplayScope':'16 concurrent HTTP/service signin acknowledgements; already-processed consumer event sequential replay; not a RocketMQ broker end-to-end test'},
 'instructions':{'AGENTS':'No AGENTS.md found in project or ancestor locations checked','actualRound2FilesRead':True},
 'baseline':mysql['baseline'],'localEngineProbe':read('local-engine-probe.json'),'runtime':{'os':'macOS 27.0.1 arm64','java':'21.0.12.1','node':'24.20.0','npm':'11.19.0','redisFinal':'7.4.10','redisEarlierSupplemental':'8.10.1; not used as Redis7 proof','chrome':'155.0.8059.40','nginx':'1.28.0'},
 'round2EvidenceAudit':{'uniqueJUnitMethods':67,'passed':67,'source':'13 TEST-*.xml suites under server/target-round2/surefire-reports, deduped by classname + test method','webBrowserSubchecksIncludedInOneJUnitMethod':5,'componentAdapterChecks':22,'miniNodeLogicTests':8,'miniSyntaxCounts':{'javascript':58,'json':54},'migrationScriptCases':7,'databaseWasMySQL8':False,'countingNote':'Historical evidence only. Do not sum 67 with browser subchecks, component checks, VM checks or Round3 reruns; these are not equal business coverage.'},
 'counts':{'selectedJUnitMethods':59,'realMySQLMethods':54,'unitMockMethods':5,'h2PaymentMethods':3,'nodeUniqueMethods':16,'nodeTimezoneRuns':2,'newComponentAdapterChecks':3,'sqlMigrationBackupChecks':13,'processScenarios':4,'fixtureCaseSnapshots':53},
 'occupancyDefinition':{'predicate':"deleted=0 AND status IN ('BOOKED','SIGNED_IN','COMPLETED','NO_SHOW')",'excluded':['CANCELLED','REFUNDED','deleted rows'],'redisForScheduled':'capacity - occupied count after DB count consistency check','redisForCancelledFinishedDeleted':'key absent','statusStorage':'VARCHAR, not a DB ENUM/CHECK; precheck stops on unsupported/noncanonical values'},
 'executions':executions,'manualAcceptance':{'developerTools':'pending','realDevice':'pending','checklist':'docs/ROUND3-WECHAT-CHECKLIST.md'},
 'concurrencyEvidence':[v for v in rows.values() if v['concurrentRuns']],
 'resolvedFindings':[{'finding':'repeated signin emitted duplicate BOOKING_CHECKED_IN events','fix':'publish only after first successful state transition; 16 concurrent acknowledgements, one attendance/event/points ledger, sequential replay remains idempotent'},{'finding':'MySQL8 reserved RANK broke existing cancellation consumer SQL','fix':'quote WaitlistEntity rank field; do not expand waitlist feature'},{'finding':'only first 1000 scheduled rows reconciled; cancelled/finished stale caches never recovered','fix':'keyset pagination, all statuses, same schedule lock'},{'finding':'role revocation could wait for cached grants','fix':'authorization reads current DB; legacy cache ignored'},{'finding':'production could enable development Mock and SMS returned verification code','fix':'profile-bound development capability, fail closed if unconfigured'},{'finding':'new package orders not server-gated; finance fields reached STAFF/COACH','fix':'package sales default disabled; explicit server response boundaries; preserve historical operations'}],
 'resolvedTestInfrastructureIssues':['init_data USE target rewriting','MySQL8 repeated DDL error is 1091, not guessed 1060','compile outside workspace due duplicated class artifacts','explicit local CORS origins for random test ports; no production wildcard relaxation','fixture includes required NORMAL member level; schema constraint retained','Redis probe explicitly selects owned DB15','restored SHOW CREATE can add redundant CHARACTER SET; actual column/index metadata and history must match'],
 'brokerLimitation':'Isolated backend uses memory MQ; no actual RocketMQ delivery/retry or cluster failover acceptance',
 'blockers':['Confirm business actual version/state using explicit read-only credentials; no current data assertions','Run pinned image on actual deployment platform and confirm Redis/Nginx image versions','Provide isolated WeChat test AppID, HTTPS legal domain and developer login, complete developer-tools flow','Complete real-device acceptance','Approve reviewed maintenance/backup/recovery/permission plan before any business migration'],
 'sourceSha256':hashes,'artifactSha256':artifacts}
(ROOT/'docs/round3-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'report':'docs/round3-verification.json','counts':report['counts'],'releaseReady':False},ensure_ascii=False))
