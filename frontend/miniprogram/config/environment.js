// 开发/体验连接隔离云测试服务；正式环境保持关闭，待生产验收后单独配置。
const environments = {
  develop: { transport: 'cloudrun', cloudEnvironment: 'uni-test-d5gwbyymua6bf2ada', cloudService: 'uni-test', useMockLogin: false },
  trial: { transport: 'cloudrun', cloudEnvironment: 'uni-test-d5gwbyymua6bf2ada', cloudService: 'uni-test', useMockLogin: false },
  release: { baseUrl: '', useMockLogin: false }
};
function resolveEnvironment(version) {
  const name = Object.hasOwnProperty.call(environments, version) ? version : 'release';
  const value = { ...environments[name], name, transport: environments[name].transport || 'http' };
  if (value.transport === 'cloudrun') {
    value.baseUrl = '';
    value.useMockLogin = false;
    value.mockCode = '';
    if (!/^[a-zA-Z0-9_-]+$/.test(value.cloudEnvironment || '') || !/^[a-zA-Z0-9_-]+$/.test(value.cloudService || '')) {
      value.error = '请先配置云托管环境和服务';
      value.cloudEnvironment = '';
      value.cloudService = '';
    }
  } else if (value.transport !== 'http') {
    value.baseUrl = ''; value.error = '服务接入方式未配置';
  } else if (name !== 'develop' && !/^https:\/\/[^/]+/.test(value.baseUrl)) {
    value.baseUrl = ''; value.error = '请先配置当前环境的 HTTPS 服务地址';
  }
  if (name !== 'develop') { value.useMockLogin = false; value.mockCode = ''; }
  return value;
}
module.exports = { resolveEnvironment };
