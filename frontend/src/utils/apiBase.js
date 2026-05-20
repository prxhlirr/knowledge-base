const runtimeApiBase = window.__APP_CONFIG__?.API_BASE_URL

export const API_BASE = runtimeApiBase || import.meta.env.VITE_API_BASE_URL || '/api/v1'
