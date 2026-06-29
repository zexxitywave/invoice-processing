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
export async function getAuditInvoices() {
  return request("/invoices");
}

/**
 * Export CSV
 */
export function exportAuditCSV(invoices) {

  const headers = [
    "Invoice ID",
    "Vendor",
    "Date",
    "Total",
    "Risk",
    "AI Status",
    "Human Decision",
    "TOTAL Confidence",
    "Average Confidence",
    "Comments",
  ];

  const rows = invoices.map((invoice) => [

    invoice.invoiceId,

    invoice.vendorName,

    invoice.invoiceDate,

    invoice.total,

    invoice.risk,

    invoice.validationStatus,

    invoice.reviewDecision || "PENDING",

    invoice.totalConfidence,

    invoice.avgConfidence,

    invoice.comments,

  ]);

  const csv = [
    headers.join(","),

    ...rows.map((row) =>
      row
        .map((value) =>
          `"${String(value ?? "").replace(/"/g, '""')}"`
        )
        .join(",")
    ),
  ].join("\n");

  const blob = new Blob(
    [csv],
    {
      type: "text/csv",
    }
  );

  const url =
    URL.createObjectURL(blob);

  const link =
    document.createElement("a");

  link.href = url;

  link.download =
    `audit-report-${
      new Date()
        .toISOString()
        .slice(0, 10)
    }.csv`;

  link.click();

  URL.revokeObjectURL(url);
}