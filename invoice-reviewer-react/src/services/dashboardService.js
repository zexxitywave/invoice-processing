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
 * Returns all invoices.
 */
export async function getInvoices() {
  return request("/invoices");
}

/**
 * Dashboard refresh.
 */
export async function refreshDashboard() {
  return getInvoices();
}