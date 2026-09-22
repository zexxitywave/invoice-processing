/**
 * ======================================================
 * Dashboard Service
 * ======================================================
 */

const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL || "";

const RETRYABLE_STATUS_CODES = [429, 500, 502, 503, 504];

async function request(path, options = {}, retries = 2) {
  const finalOptions = {
    headers: {
      "Content-Type": "application/json",
      ...(options.headers || {}),
    },
    ...options,
  };

  let lastError;
  for (let attempt = 0; attempt <= retries; attempt++) {
    if (attempt > 0) {
      await new Promise((resolve) => setTimeout(resolve, 300 * attempt));
    }
    try {
      const response = await fetch(
        `${API_BASE_URL}${path}`,
        finalOptions
      );

      const data = await response.json();

      if (!response.ok) {
        const error = new Error(
          data.error ||
          `Request failed (${response.status})`
        );
        error.status = response.status;
        throw error;
      }

      return data;
    } catch (error) {
      lastError = error;
      // Transient gateway / Lambda hiccups recover on retry (GET is idempotent).
      if (!RETRYABLE_STATUS_CODES.includes(error.status) || attempt === retries) {
        throw error;
      }
    }
  }

  throw lastError;
}

/**
 * Returns one page of invoices.
 * @param {number} pageSize
 * @param {string|null} nextToken
 */
export async function getInvoices(pageSize = 20, nextToken = null) {
  const params = new URLSearchParams({ pageSize });
  if (nextToken) params.set("nextToken", nextToken);
  return request(`/invoices?${params.toString()}`);
}

/**
 * Dashboard refresh.
 */
export async function refreshDashboard(pageSize = 20, nextToken = null) {
  return getInvoices(pageSize, nextToken);
}