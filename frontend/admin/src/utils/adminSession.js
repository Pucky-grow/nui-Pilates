const keys = ['adminToken', 'adminName', 'adminRoles', 'adminPerms'];

function read(key) {
  try {
    return sessionStorage.getItem(key) || '';
  } catch {
    return '';
  }
}

function readList(key) {
  try {
    const value = JSON.parse(read(key));
    return Array.isArray(value) ? value.filter(item => typeof item === 'string') : [];
  } catch {
    return [];
  }
}

export const adminToken = () => read('adminToken');
export const adminName = () => read('adminName');
export const adminRoles = () => readList('adminRoles');
export const adminPermissions = () => readList('adminPerms');
export const hasAdminPermission = permission => adminRoles().includes('ADMIN') || adminPermissions().includes(permission);

export function saveAdminSession(data) {
  sessionStorage.setItem('adminName', data.name || '');
  sessionStorage.setItem('adminRoles', JSON.stringify(data.roles || []));
  sessionStorage.setItem('adminPerms', JSON.stringify(data.permissions || []));
  sessionStorage.setItem('adminToken', data.token);
}

export function clearAdminSession() {
  try {
    for (const key of keys) sessionStorage.removeItem(key);
  } catch {
    // Storage can be unavailable in browser privacy modes.
  }
}

try {
  for (const key of keys) localStorage.removeItem(key);
} catch {
  // Clear legacy persistent sessions wherever browser storage is available.
}
