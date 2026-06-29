const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL || "";

async function request(
  path,
  options = {}
) {
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
 * Returns all invoices
 */
export async function getInvoices() {
  return request("/invoices");
}

/**
 * Returns only invoices waiting for review
 */
export async function getReviewQueue() {
  const invoices = await getInvoices();

  return invoices.filter(
    (invoice) =>
      invoice.validationStatus ===
        "REVIEW_REQUIRED" &&
      (
        !invoice.reviewDecision ||
        invoice.reviewDecision === "" ||
        invoice.reviewDecision ===
          "undefined" ||
        invoice.reviewDecision ===
          "PENDING"
      )
  );
}

/**
 * Submit human decision
 */
export async function submitReview({
  invoiceId,
  decision,
  reviewer,
  reason,
}) {
  return request(
    "/invoices/review",
    {
      method: "POST",

      body: JSON.stringify({
        invoiceId,
        decision,
        reviewer,
        reason,
      }),
    }
  );
}