/**
 * ======================================================
 * Dashboard Service
 * ======================================================
 */

const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL || "";

async function request(path, options = {}) {
  const response = await fetch(
    `${API_BASE_URL}${path}`,
    {
      headers: {
        "Content-Type": "application/json",
        ...(options.headers || {}),
      },
      ...options,
    }
  );

  const data = await response.json();

  if (!response.ok) {
    throw new Error(
      data.error ||
      `Request failed (${response.status})`
    );
  }

  return data;
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