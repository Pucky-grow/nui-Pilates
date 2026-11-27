import axios from 'axios';
import { ElMessage } from 'element-plus';
import router from '../router';
import { adminToken, clearAdminSession } from './adminSession';

const http = axios.create({ baseURL: '/', timeout: 15000 });

http.interceptors.request.use((config) => {
  if (config.url === '/admin' || config.url?.startsWith('/admin/')) config.url = '/api' + config.url;
  const token = adminToken();
  if (token && config.url?.startsWith('/api/') && !config.headers.Authorization) {
    config.headers.Authorization = 'Bearer ' + token;
  }
  return config;
});

function reportFailure(body, config, message, status) {
  const token = adminToken();
  if ((body?.code === 40100 || status === 401) && token && config?.headers?.Authorization === 'Bearer ' + token) {
    clearAdminSession();
    void router.replace('/login');
  }
  const detail = typeof body?.message === 'string' && body.message ? body.message : message;
  if (!config?.silent) ElMessage.error(detail);
  return Promise.reject(typeof body?.code === 'number' ? { ...body, message: detail } : new Error(detail));
}

http.interceptors.response.use(res => {
  if (res.data?.code === 0) return res.data.data;
  return reportFailure(res.data, res.config, '服务响应异常，请重试');
}, async error => {
  if (axios.isCancel(error)) throw error;
  const { config, response, code } = error;
  const status = response?.status;
  const timedOut = code === 'ECONNABORTED' || code === 'ETIMEDOUT';
  const temporary = timedOut || code === 'ERR_NETWORK' || [502, 503, 504].includes(status);
  if (temporary && config?.method === 'get' && !config.retried) {
    await new Promise(resolve => setTimeout(resolve, 300));
    return http.request({ ...config, retried: true });
  }
  const message = timedOut ? '请求超时，请重试' : status === 401 ? '登录已过期，请重新登录'
    : status === 403 ? '当前账号无权执行此操作' : status === 429 ? '请求过于频繁，请稍后再试'
      : status >= 500 ? '服务暂时不可用，请稍后再试' : response ? '请求失败，请重试' : '连接失败，请检查网络';
  return reportFailure(response?.data, config, message, status);
});

export default http;
