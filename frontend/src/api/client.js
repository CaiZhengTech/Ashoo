import axios from 'axios';

// In dev, VITE_API_BASE_URL is unset and we use a relative base so Vite's proxy
// (vite.config.js) forwards /api → :8080. In production we set VITE_API_BASE_URL
// to the deployed backend origin (e.g. the Fly.io URL) at build time.
const baseURL = import.meta.env.VITE_API_BASE_URL || '';

export const http = axios.create({
  baseURL,
  headers: { 'Content-Type': 'application/json' },
  timeout: 20_000,
});

/**
 * Normalizes backend/network errors into a single human-readable message.
 *
 * The backend's GlobalExceptionHandler returns a consistent { message } shape,
 * so we prefer that; otherwise we fall back to the axios message. Components show
 * this string in their error state without caring about the transport details.
 */
export function errorMessage(err) {
  const data = err?.response?.data;
  // The backend uses { message } for the global handler and { error } for some
  // controller-level 400s (geocoding, conditions). Surface whichever is present
  // so the user sees the real reason, not a bare status code.
  if (data?.message) return data.message;
  if (data?.error) return data.error;
  if (err?.response?.status) return `Request failed (${err.response.status}).`;
  if (err?.code === 'ECONNABORTED') return 'The backend took too long to respond.';
  return 'Could not reach the Ashoo backend. Is it running on :8080?';
}
