const configuredApiBase = import.meta.env.VITE_API_BASE_URL || '/api';
const API = configuredApiBase.replace(/\/+$/, '');
export const localDateString = (date = new Date()) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
const unsafe = method => !['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method.toUpperCase());
// Keep the token returned by the CSRF bootstrap endpoint in memory. The API may
// be reached through a same-origin proxy, where its cookie is intentionally
// host-only and cannot be read from JavaScript running on another origin.
let csrfToken = '';
let csrfBootstrap;
async function refreshCsrf() {
  if (!csrfBootstrap) {
    csrfBootstrap = (async () => {
      const response = await fetch(`${API}/auth/csrf`, { credentials: 'include' });
      if (!response.ok) throw new Error('Could not initialize secure session token.');
      const payload = await response.json();
      if (!payload.token) throw new Error('Could not initialize secure session token.');
      csrfToken = payload.token;
      return csrfToken;
    })();
  }
  try { return await csrfBootstrap; }
  finally { csrfBootstrap = undefined; }
}
async function request(path, options = {}, retryCsrf = true) {
  const method = options.method || 'GET';
  const headers = { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...options.headers };
  if (unsafe(method)) {
    if (!csrfToken) await refreshCsrf();
    headers['X-XSRF-TOKEN'] = csrfToken;
  }
  const response = await fetch(`${API}${path}`, { ...options, method, credentials: 'include', headers });
  // Login and logout deliberately clear the server's CSRF cookie, so discard
  // the corresponding in-memory token before the next state-changing request.
  if ((path === '/auth/login' || path === '/auth/logout') && response.ok) csrfToken = '';
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    if (response.status === 403 && problem.code === 'csrf_invalid' && retryCsrf && unsafe(method)) {
      csrfToken = '';
      await refreshCsrf();
      return request(path, options, false);
    }
    if (response.status === 401 && path !== '/auth/login') window.dispatchEvent(new Event('gym:unauthorized'));
    const error = new Error(problem.message || `Request failed (${response.status})`);
    error.status = response.status;
    throw error;
  }
  if (response.status === 204) return null;
  return options.responseType === 'text' ? response.text() : response.json();
}
const json = (method, body) => ({ method, body: JSON.stringify(body) });
export const api = {
  csrf: refreshCsrf,
  login: credentials => request('/auth/login', json('POST', credentials)),
  activateMember: data => request('/auth/activate-member', json('POST', data)),
  logout: () => request('/auth/logout', { method: 'POST' }),
  me: () => request('/auth/me'),
  accounts: () => request('/admin/users'),
  createAccount: data => request('/admin/users', json('POST', data)),
  setAccountEnabled: (id, enabled) => request(`/admin/users/${id}/enabled`, json('PUT', { enabled })),
  createMemberActivation: data => request('/admin/member-activations', json('POST', data)),
  memberActivationCandidates: () => request('/admin/member-activations/available-members'),
  memberDashboard: () => request('/member/dashboard'),
  memberProfile: () => request('/member/profile'),
  memberMembership: () => request('/member/membership'),
  memberAttendance: () => request('/member/attendance'),
  memberAttendanceActivity: (from,to) => request(`/member/attendance/activity?${new URLSearchParams({from,to})}`),
  memberPayments: () => request('/member/payments'),
  memberReceipt: id => request(`/member/payments/${id}/receipt`, { responseType: 'text' }),
  memberFitnessSummary: () => request('/member/fitness/summary'),
  memberMeasurements: params => request(`/member/measurements${params ? `?${new URLSearchParams(params)}` : ''}`),
  saveMemberMeasurement: (data,id) => request(`/member/measurements${id?`/${id}`:''}`,json(id?'PUT':'POST',data)),
  deleteMemberMeasurement: id => request(`/member/measurements/${id}`,{method:'DELETE'}),
  memberGoals: () => request('/member/goals'),
  saveMemberGoal: (data,id) => request(`/member/goals${id?`/${id}`:''}`,json(id?'PUT':'POST',data)),
  completeMemberGoal: id => request(`/member/goals/${id}/complete`,json('POST',{})),
  deleteMemberGoal: id => request(`/member/goals/${id}`,{method:'DELETE'}),
  exercises: () => request('/member/exercises'),
  workoutPlans: () => request('/member/workouts/plans'),
  saveWorkoutPlan: (data,id) => request(`/member/workouts/plans${id?`/${id}`:''}`,json(id?'PUT':'POST',data)),
  deleteWorkoutPlan: id => request(`/member/workouts/plans/${id}`,{method:'DELETE'}),
  workoutHistory: () => request('/member/workouts/sessions'),
  activeWorkout: () => request('/member/workouts/sessions/active'),
  startWorkout: data => request('/member/workouts/sessions/start',json('POST',data)),
  finishWorkout: (id,data) => request(`/member/workouts/sessions/${id}/complete`,json('POST',data)),
  completeWorkout: data => request('/member/workouts/sessions',json('POST',data)),
  attendanceGoal: () => request('/member/attendance-goal'),
  saveAttendanceGoal: data => request('/member/attendance-goal',json('PUT',data)),
  memberAchievements: () => request('/member/achievements'),
  nutrition: date => request(`/member/nutrition${date?`?date=${encodeURIComponent(date)}`:''}`),
  saveMeal: (data,id) => request(`/member/nutrition/meals${id?`/${id}`:''}`,json(id?'PUT':'POST',data)),
  deleteMeal: id => request(`/member/nutrition/meals/${id}`,{method:'DELETE'}),
  wellnessSettings: () => request('/member/nutrition/settings'),
  saveWellnessSettings: data => request('/member/nutrition/settings',json('PUT',data)),
  addWater: data => request('/member/nutrition/water',json('POST',data)),
  deleteWater: id => request(`/member/nutrition/water/${id}`,{method:'DELETE'}),
  memberNotifications: () => request('/member/notifications'),
  readMemberNotification: id => request(`/member/notifications/${id}/read`,json('POST',{})),
  readAllMemberNotifications: () => request('/member/notifications/read-all',{method:'POST'}),
  announcements: () => request('/admin/announcements'),
  saveAnnouncement: (data,id) => request(`/admin/announcements${id?`/${id}`:''}`,json(id?'PUT':'POST',data)),
  deactivateAnnouncement: id => request(`/admin/announcements/${id}`,{method:'DELETE'}),
  dashboard: () => request('/reports/dashboard'),
  members: (search = '') => request(`/members${search ? `?search=${encodeURIComponent(search)}` : ''}`),
  member: id => request(`/members/${id}`),
  saveMember: (data, id) => request(`/members${id ? `/${id}` : ''}`, json(id ? 'PUT' : 'POST', data)),
  deleteMember: id => request(`/members/${id}`, { method: 'DELETE' }),
  plans: () => request('/plans'),
  savePlan: (data, id) => request(`/plans${id ? `/${id}` : ''}`, json(id ? 'PUT' : 'POST', data)),
  deactivatePlan: id => request(`/plans/${id}`, { method: 'DELETE' }),
  trainers: (search = '', active) => request(`/trainers?${new URLSearchParams({ ...(search ? { search } : {}), ...(active === undefined ? {} : { active }) })}`),
  saveTrainer: (data, id) => request(`/trainers${id ? `/${id}` : ''}`, json(id ? 'PUT' : 'POST', data)),
  deactivateTrainer: id => request(`/trainers/${id}`, { method: 'DELETE' }),
  attendance: (params = {}) => request(`/attendance?${new URLSearchParams(params)}`),
  checkIn: memberId => request('/attendance/check-in', json('POST', { memberId: Number(memberId) })),
  checkOut: memberId => request('/attendance/check-out', json('POST', { memberId: Number(memberId) })),
  payments: (params = {}) => request(`/payments?${new URLSearchParams(params)}`),
  createPayment: data => request('/payments', json('POST', data)),
  processPayment: (id, approved) => request(`/payments/${id}/process`, json('POST', { approved })),
  cancelPayment: id => request(`/payments/${id}/cancel`, { method: 'POST' }),
  payment: id => request(`/payments/${id}`),
  receipt: id => request(`/payments/${id}/receipt`, { responseType: 'text' }),
  paymentReport: (from, to) => request(`/reports/payments?${new URLSearchParams({ from, to })}`),
  dailyCollections: date => request(`/reports/payments/daily?date=${date}`),
  monthlyCollections: month => request(`/reports/payments/monthly?month=${month}`),
  attendanceToday: () => request('/reports/attendance/today')
};
