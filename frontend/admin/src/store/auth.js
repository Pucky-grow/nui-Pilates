import { defineStore } from 'pinia';
import http from '../utils/request';
import { adminToken, adminName, adminRoles, hasAdminPermission, saveAdminSession, clearAdminSession } from '../utils/adminSession';

export const useAuthStore = defineStore('auth', {
  state: () => ({ token: adminToken(), name: adminName() }),
  actions: {
    async login(username, password) {
      const data = await http.post('/api/auth/admin-login', { username, password });
      saveAdminSession(data);
      this.token = data.token;
      this.name = data.name;
      return data;
    },
    logout() {
      const token = adminToken();
      this.token = '';
      this.name = '';
      clearAdminSession();
      if (token) return http.post('/api/auth/logout', {}, {
        headers: { Authorization: 'Bearer ' + token },
        silent: true
      }).catch(() => {});
    },
    isAdmin() {
      return adminRoles().includes('ADMIN');
    },
    isCoach() {
      return adminRoles().includes('COACH');
    },
    role() {
      return adminRoles()[0] || '';
    },
    hasPerm(perm) {
      return hasAdminPermission(perm);
    }
  }
});
