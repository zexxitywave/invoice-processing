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
 * Fetches ALL pages to ensure no REVIEW_REQUIRED invoices are missed
 */
export async function getReviewQueue() {
  let allInvoices = [];
  let nextToken = null;

  // Paginate through all pages
  do {
    const url = nextToken
      ? `/invoices?pageSize=100&nextToken=${encodeURIComponent(nextToken)}`
      : `/invoices?pageSize=100`;
    const data = await request(url);
    const items = Array.isArray(data) ? data : (data.items ?? []);
    allInvoices = allInvoices.concat(items);
    nextToken = data.nextToken ?? null;
  } while (nextToken);

  return allInvoices.filter(
    (invoice) =>
      invoice.validationStatus === "REVIEW_REQUIRED" &&
      (
        !invoice.reviewDecision ||
        invoice.reviewDecision === "" ||
        invoice.reviewDecision === "undefined" ||
        invoice.reviewDecision === "PENDING"
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