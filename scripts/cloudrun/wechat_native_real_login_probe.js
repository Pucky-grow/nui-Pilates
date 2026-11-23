// Run through authorized WeChat DevTools automation_evaluate only.
// Never return the one-use code, token, session key or member identity.
function () {
  const name = '__yoyiNativeWechatLoginProbe20261106';
  if (globalThis[name] && globalThis[name].status === 'running') return globalThis[name];
  const environment = 'uni-test-d5gwbyymua6bf2ada';
  const service = 'uni-test';
  const report = {
    probeType: 'devtools-native-wechat-to-cloud-login', transport: 'cloudrun',
    environment, service, appid: 'wxd9bd556ad0c77104',
    origin: 'https://uni-test-339305-11-1505023965.sh.run.tcloudbase.com',
    status: 'running', stage: 'appid', startedAt: new Date().toISOString(), checks: [],
    realWechatCodeRetrieved: false, realWechatLoginPassed: false, negativeBoundaryPassed: false,
    deviceTested: false, existingApplicationConfigurationChanged: false,
    codeTokenOrMemberIdentityEmitted: false, publicHttpFallbackUsed: false
  };
  globalThis[name] = report;
  let token, code, initialized = false;
  const request = (path, data, auth) => new Promise((resolve, reject) => {
    if (!wx.cloud || typeof wx.cloud.init !== 'function' || typeof wx.cloud.callContainer !== 'function') {
      report.networkFailure = 'native-sdk-unavailable';
      reject(new Error('native-sdk-unavailable')); return;
    }
    try {
      if (!initialized) { wx.cloud.init({ env: environment, traceUser: false }); initialized = true; }
      wx.cloud.callContainer({ config: { env: environment }, path,
        method: data === undefined ? 'GET' : 'POST', data, timeout: 8000,
        followRedirect: false, dataType: 'json',
        header: { 'X-WX-SERVICE': service, 'Content-Type': 'application/json',
          ...(auth ? { Authorization: 'Bearer ' + auth } : {}) },
        success(res) {
          if (res.statusCode !== 200 || !res.data || typeof res.data !== 'object') {
            report.failedHttpStatus = res.statusCode;
            reject(new Error('unexpected-http-response')); return;
          }
          resolve(res.data);
        },
        fail(error) {
          const message = String(error.errMsg || '');
          report.networkFailure = /timeout/i.test(message) ? 'timeout'
            : /permission|not auth|not bound|unauthorized/i.test(message) ? 'native-access-denied' : 'native-request-failed';
          reject(new Error('native-request-failed'));
        }
      });
    } catch (_) {
      report.networkFailure = 'native-sdk-error'; reject(new Error('native-sdk-error'));
    }
  });
  const expect = (value, expected) => {
    if (value.code !== expected) { report.failureBusinessCode = value.code; throw new Error('unexpected-business-code'); }
  };
  const step = (stage, work) => () => {
    report.stage = stage;
    return Promise.resolve().then(work).then(value => { report.checks.push({ name: stage, status: 'passed' }); return value; });
  };
  return Promise.resolve().then(step('appid', () => {
    if (wx.getAccountInfoSync().miniProgram.appId !== report.appid) throw new Error('wrong-appid');
  })).then(step('cloud-health', () => request('/health').then(health => {
    expect(health, 0);
    if (!health.data || health.data.service !== 'pilates-server') throw new Error('not-full-application');
  }))).then(step('real-wx-login-code', () => new Promise((resolve, reject) => wx.login({
    timeout: 8000, success: resolve, fail: () => reject(new Error('wx-login-failed'))
  })).then(value => {
    if (!value.code) throw new Error('wx-code-missing');
    code = value.code; report.realWechatCodeRetrieved = true;
  }))).then(step('cloud-code-exchange', () => request('/api/auth/wx-login', { code }).then(value => {
    code = null; expect(value, 0);
    token = value.data && value.data.token;
    if (!token || value.data.userType !== 'MP_USER') throw new Error('unexpected-login-identity');
  }))).then(step('authenticated-me', () => request('/api/auth/me', undefined, token).then(value => expect(value, 0))))
    .then(step('logout-and-session-revocation', () => request('/api/auth/logout', {}, token).then(value => {
      expect(value, 0); return request('/api/auth/me', undefined, token);
    }).then(value => { expect(value, 40100); token = null; report.realWechatLoginPassed = true; })))
    .then(step('mock-code-rejection', () => request('/api/auth/wx-login', { code: 'round3_local_member' }).then(value => {
      if (value.code === 0) token = value.data && value.data.token;
      expect(value, 40100); report.negativeBoundaryPassed = true;
    }))).then(() => { report.status = 'passed-scoped'; })
    .catch(error => { report.status = 'failed'; report.failedStage = report.stage; report.errorType = error.name; })
    .then(() => token ? request('/api/auth/logout', {}, token)
      .then(value => { report.cleanupLogoutSucceeded = value.code === 0; })
      .catch(() => { report.cleanupLogoutSucceeded = false; }) : null)
    .then(() => { token = null; code = null; report.finishedAt = new Date().toISOString(); return report; });
}
