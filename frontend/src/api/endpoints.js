import { http } from './client';

// Thin, named wrappers around every backend endpoint the UI uses. Keeping them
// in one file means the API contract lives in exactly one place, if a route or
// shape changes on the backend, there is a single spot to update.

// `user` is the optional persona key (e.g. "morgan"); omitted/undefined means the
// default real user. The backend allow-lists and resolves it.

// ---- Risk ----------------------------------------------------------------
export const getRiskCurrent = (user) =>
  http.get('/api/v1/risk/current', { params: { user } }).then((r) => r.data);
export const getRiskHistory = (from, to, user) =>
  http.get('/api/v1/risk/history', { params: { from, to, user } }).then((r) => r.data);

// ---- Briefing ------------------------------------------------------------
export const getBriefing = (demo = false, user) =>
  http.get('/api/v1/briefing/today', { params: { demo, user } }).then((r) => r.data);

// ---- Correlation ---------------------------------------------------------
export const computeCorrelation = (user) =>
  http.post('/api/v1/correlation/compute', null, { params: { user } }).then((r) => r.data);
export const getCorrelationResults = (user) =>
  http.get('/api/v1/correlation/results', { params: { user } }).then((r) => r.data);
export const getMismatches = (user) =>
  http.get('/api/v1/correlation/mismatches', { params: { user } }).then((r) => r.data);

// ---- Symptoms ------------------------------------------------------------
export const getSymptoms = (from, to) =>
  http.get('/api/v1/symptoms', { params: { from, to } }).then((r) => r.data);
export const createSymptom = (body) =>
  http.post('/api/v1/symptoms', body).then((r) => r.data);
export const updateSymptom = (id, body) =>
  http.put(`/api/v1/symptoms/${id}`, body).then((r) => r.data);
export const deleteSymptom = (id) => http.delete(`/api/v1/symptoms/${id}`);

// ---- Locations -----------------------------------------------------------
export const getLocations = () => http.get('/api/v1/locations').then((r) => r.data);
export const addLocation = (body) =>
  http.post('/api/v1/locations', body).then((r) => r.data);
export const updateLocation = (id, body) =>
  http.put(`/api/v1/locations/${id}`, body).then((r) => r.data);
export const deleteLocation = (id) => http.delete(`/api/v1/locations/${id}`);
export const getRecentSearches = () =>
  http.get('/api/v1/locations/recent-searches').then((r) => r.data);
export const suggestPlaces = (q) =>
  http.get('/api/v1/locations/suggest', { params: { q } }).then((r) => r.data);

// ---- Conditions (ad-hoc lookup) -----------------------------------------
export const getConditionsByCity = (city) =>
  http.get('/api/v1/conditions', { params: { city } }).then((r) => r.data);
export const getConditionsByCoords = (lat, lon) =>
  http.get('/api/v1/conditions', { params: { lat, lon } }).then((r) => r.data);

// ---- Consent -------------------------------------------------------------
export const getConsent = () => http.get('/api/v1/consent').then((r) => r.data);
export const acceptConsent = () => http.post('/api/v1/consent').then((r) => r.data);

// ---- Medications ---------------------------------------------------------
export const getMedications = () => http.get('/api/v1/medications').then((r) => r.data);
export const addMedication = (body) =>
  http.post('/api/v1/medications', body).then((r) => r.data);
export const deleteMedication = (id) => http.delete(`/api/v1/medications/${id}`);
export const getMedicationUsage = () =>
  http.get('/api/v1/medications/usage').then((r) => r.data);

// ---- Reminder rules + current reminders ----------------------------------
// `user` is the optional persona key; omitted means the real default user. Only the
// read paths are persona-aware; creating/deleting always act on the real user.
export const getReminderRules = (user) =>
  http.get('/api/v1/reminder-rules', { params: { user } }).then((r) => r.data);
export const addReminderRule = (body) =>
  http.post('/api/v1/reminder-rules', body).then((r) => r.data);
export const deleteReminderRule = (id) => http.delete(`/api/v1/reminder-rules/${id}`);
export const getCurrentReminders = (user) =>
  http.get('/api/v1/reminders/current', { params: { user } }).then((r) => r.data);

// ---- Demo ----------------------------------------------------------------
export const getDemoProfiles = () => http.get('/api/v1/demo/profiles').then((r) => r.data);
export const seedDemo = () => http.post('/api/v1/demo/seed').then((r) => r.data);

// ---- Ingestion (manual triggers, used by Settings/Places) ----------------
export const triggerIngestion = () =>
  http.post('/api/v1/ingestion/trigger').then((r) => r.data);

// ---- Export (returns raw CSV text) --------------------------------------
export const exportCsvUrl = (from, to) => {
  const base = import.meta.env.VITE_API_BASE_URL || '';
  const q = new URLSearchParams({ from, to }).toString();
  return `${base}/api/v1/export?${q}`;
};

// ---- Health (backend status pill) ---------------------------------------
export const getHealth = () => http.get('/actuator/health').then((r) => r.data);
