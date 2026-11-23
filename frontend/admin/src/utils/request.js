import axios from 'axios';
import { ElMessage } from 'element-plus';
import router from '../router';

const http = axios.create({ baseURL: '/', timeout: 15000 });

http.interceptors.request.use((config) => {
  if (config.url === '/admin' || config.url.startsWith('/admin/')) config.url = '/api' + config.url;
  const token = localStorage.getItem('adminToken');
  if (token) config.headers.Authorization = 'Bearer ' + token;
  return config;
});

http.interceptors.response.use(
  (res) => {
    const body = res.data;
    if (body && body.code === 0) return body.data;
    if (body && body.code === 40100) {
      localStorage.removeItem('adminToken');
      router.push('/login');
    }
    ElMessage.error((body && body.message) || '请求失败');
    return Promise.reject(body);
  },
  (err) => { ElMessage.error('网络异常'); return Promise.reject(err); }
);

export default http;
