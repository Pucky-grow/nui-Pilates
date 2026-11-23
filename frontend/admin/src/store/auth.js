import { defineStore } from 'pinia';
import http from '../utils/request';

export const useAuthStore = defineStore('auth', {
  state: () => ({ token: localStorage.getItem('adminToken') || '', name: '' }),
  actions: {
    async login(username, password) {
      const data = await http.post('/api/auth/admin-login', { username, password });
      this.token = data.token;
      this.name = data.name;
      localStorage.setItem('adminToken', data.token);
      localStorage.setItem('adminPerms', JSON.stringify(data.permissions || []));
      localStorage.setItem('adminRoles', JSON.stringify(data.roles || []));
      return data;
    },
    logout() {
      this.token = '';
      this.name = '';
      localStorage.removeItem('adminToken');
      localStorage.removeItem('adminRoles');
      localStorage.removeItem('adminPerms');
    },
    isAdmin() {
      const roles = JSON.parse(localStorage.getItem('adminRoles') || '[]');
      return roles.includes('ADMIN');
    },
    isCoach() {
      const roles = JSON.parse(localStorage.getItem('adminRoles') || '[]');
      return roles.includes('COACH');
    },
    role() {
      const roles = JSON.parse(localStorage.getItem('adminRoles') || '[]');
      return roles[0] || '';
    },
    hasPerm(perm) {
      const perms = JSON.parse(localStorage.getItem('adminPerms') || '[]');
      return this.isAdmin() || perms.includes(perm);
    }
  }
});
