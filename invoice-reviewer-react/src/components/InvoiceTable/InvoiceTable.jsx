import StatusBadge from "../StatusBadge";
import ConfidenceBar from "../ConfidenceBar";
import { humanReviewValue } from "../../utils/humanReview";
import "./InvoiceTable.css";

function formatUploaded(raw) {
  if (!raw) return "—";
  const date = new Date(raw);
  if (isNaN(date.getTime())) return raw;
  return date.toLocaleString("en-US", {
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export default function InvoiceTable({
  invoices = [],
  onReview = () => {},
  hasNext = false,
  hasPrev = false,
  pageNumber = 1,
  totalPages = null,
  onNext = () => {},
  onPrev = () => {},
}) {
  if (!invoices.length) {
    return <div className="table-empty">No invoices found.</div>;
  }

  return (
    <div>
      <div className="table-wrapper">
        <table className="invoice-table">
          <thead>
            <tr>
              <th>Invoice ID</th>
              <th>Vendor</th>
              <th>Uploaded</th>
              <th>Total</th>
              <th>Risk</th>
              <th>AI Status</th>
              <th>Human Review</th>
              <th>Confidence</th>
              <th>Action</th>
            </tr>
          </thead>
          <tbody>
            {invoices.map((invoice) => {
              const canReview =
                invoice.validationStatus === "REVIEW_REQUIRED" &&
                !invoice.reviewDecision;

              return (
                <tr key={invoice.invoiceId}>
                  <td><code>{invoice.invoiceId || "—"}</code></td>
                  <td>{invoice.vendorName || "—"}</td>
                  <td>{formatUploaded(invoice.createdAt)}</td>
                  <td><strong>{invoice.total || "—"}</strong></td>
                  <td><StatusBadge type="risk" value={invoice.risk} /></td>
                  <td><StatusBadge type="ai" value={invoice.validationStatus} /></td>
                  <td><StatusBadge type="human" value={humanReviewValue(invoice)} /></td>
                  <td><ConfidenceBar value={invoice.totalConfidence ?? invoice.avgConfidence} /></td>
                  <td>
                    {canReview ? (
                      <button
                        className="btn btn-primary btn-sm"
                        onClick={() => onReview(invoice)}
                      >
                        Review
                      </button>
                    ) : (
                      <span className="no-action">—</span>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <div className="pagination-bar">
        <button
          className="btn btn-secondary btn-sm"
          onClick={onPrev}
          disabled={!hasPrev}
        >
          ← Previous
        </button>

        <span className="pagination-info">
          Page {pageNumber}{totalPages ? ` of ${totalPages}` : ""}
        </span>

        <button
          className="btn btn-secondary btn-sm"
          onClick={onNext}
          disabled={!hasNext}
        >
          Next →
        </button>
      </div>
    </div>
  );
}