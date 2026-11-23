// Run only through the authorized WeChat DevTools automation_evaluate tool.
// The one-use code, token and returned member profile stay inside the runtime.
function () {
  const name = '__yoyiWechatLoginProbe20261103C';
  if (globalThis[name] && globalThis[name].status === 'running') return globalThis[name];
  const origin = 'https://uni-test-339305-11-1505023965.sh.run.tcloudbase.com';
  const report = { probeType: 'devtools-real-wechat-to-cloud-login', status: 'running',
    origin, appid: 'wxd9bd556ad0c77104',
    startedAt: new Date().toISOString(), stage: 'appid', checks: [],
    realWechatCodeRetrieved: false, realWechatLoginPassed: false, deviceTested: false,
    existingApplicationConfigurationChanged: false, domainValidationVerified: false,
    codeTokenOrMemberIdentityEmitted: false };
  globalThis[name] = report;
  let token, code;
  const request = (path, data, auth) => new Promise((resolve, reject) => wx.request({
    url: origin + path, method: data === undefined ? 'GET' : 'POST', data,
    timeout: 8000,
    header: { 'Content-Type': 'application/json', ...(auth ? { Authorization: 'Bearer ' + auth } : {}) },
    success(res) {
      if (res.statusCode !== 200 || !res.data || typeof res.data !== 'object') {
        report.failedHttpStatus = res.statusCode;
        return reject(new Error('unexpected-http-response'));
      }
      resolve(res.data);
    },
    fail(error) {
      const message = String(error.errMsg || '');
      report.networkFailure = /domain list|合法域名/.test(message) ? 'domain-not-allowed'
        : /timeout/.test(message) ? 'timeout' : /ssl|certificate/i.test(message) ? 'tls' : 'network';
      reject(new Error('request-failed'));
    }
  }));
  const expect = (value, code) => {
    if (value.code !== code) {
      report.failureBusinessCode = value.code;
      throw new Error('unexpected-business-code');
    }
  };
  const pass = () => report.checks.push({ name: report.stage, status: 'passed' });
  const step = (stage, work) => () => {
    report.stage = stage;
    return Promise.resolve().then(work).then((value) => { pass(); return value; });
  };
  const chain = Promise.resolve().then(step('appid', () => {
    if (wx.getAccountInfoSync().miniProgram.appId !== 'wxd9bd556ad0c77104') throw new Error('wrong-appid');
  })).then(step('real-wx-login-code', () => new Promise((resolve, reject) => wx.login({
      timeout: 8000, success: resolve, fail: () => reject(new Error('wx-login-failed'))
    })).then((loginCode) => {
    if (!loginCode.code) throw new Error('wx-code-missing');
    report.realWechatCodeRetrieved = true;
    code = loginCode.code;
  }))).then(step('cloud-code-exchange', () => request('/api/auth/wx-login', { code }).then((login) => {
    code = null;
    expect(login, 0);
    token = login.data && login.data.token;
    if (!token || login.data.userType !== 'MP_USER') throw new Error('unexpected-login-identity');
    report.newTestMemberCreated = login.data.new === true;
  }))).then(step('authenticated-me', () => request('/api/auth/me', undefined, token).then((value) => expect(value, 0))))
  .then(step('logout-and-session-revocation', () => request('/api/auth/logout', {}, token).then((value) => {
    expect(value, 0);
    return request('/api/auth/me', undefined, token);
  }).then((value) => {
    expect(value, 40100);
    token = null;
    report.realWechatLoginPassed = true;
  }))).then(step('mock-code-rejection', () => request('/api/auth/wx-login', { code: 'round3_local_member' }).then((negative) => {
    if (negative.code === 0) token = negative.data && negative.data.token;
    if (negative.code === 0) throw new Error('synthetic-login-was-accepted');
    report.negativeBoundaryPassed = negative.code === 40100;
    report.negativeBusinessCode = negative.code;
  }))).then(step('cloud-health', () => request('/health').then((health) => {
    expect(health, 0);
    if (!health.data || health.data.service !== 'pilates-server') throw new Error('not-full-application');
  }))).then(() => {
    report.status = report.negativeBoundaryPassed ? 'passed-scoped' : 'positive-passed-negative-boundary-failed';
  }).catch((error) => {
    report.status = 'failed';
    report.failedStage = report.stage;
    report.errorType = error.name;
  }).then(() => token ? request('/api/auth/logout', {}, token).then((value) => {
    report.cleanupLogoutSucceeded = value.code === 0;
  }).catch(() => { report.cleanupLogoutSucceeded = false; }) : null).then(() => {
    report.finishedAt = new Date().toISOString();
    return report;
  });
  return chain;
}
