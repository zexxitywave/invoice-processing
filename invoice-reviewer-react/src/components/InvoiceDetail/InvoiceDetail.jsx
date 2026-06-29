import StatusBadge from "../StatusBadge";
import ConfidenceBar from "../ConfidenceBar";

import "./InvoiceDetail.css";

export default function InvoiceDetail({
  invoice,
  onClose,
  children,
}) {
  if (!invoice) return null;

  const missingFields = Array.isArray(
    invoice.missingFields
  )
    ? invoice.missingFields.length
      ? invoice.missingFields.join(", ")
      : "None"
    : invoice.missingFields || "None";

  return (
    <div className="card invoice-detail">

      <div className="card-header">

        <h2>
          Invoice: {invoice.invoiceId}
        </h2>

        <button
          className="btn btn-secondary"
          onClick={onClose}
        >
          ✕ Close
        </button>

      </div>

      <div className="detail-grid">

        <DetailItem
          label="Invoice ID"
          value={invoice.invoiceId}
        />

        <DetailItem
          label="Vendor"
          value={invoice.vendorName}
        />

        <DetailItem
          label="Invoice Date"
          value={invoice.invoiceDate}
        />

        <DetailItem
          label="Subtotal"
          value={invoice.subtotal}
        />

        <DetailItem
          label="Total"
          value={invoice.total}
        />

        <DetailItem
          label="TOTAL Confidence"
        >
          <ConfidenceBar
            value={invoice.totalConfidence}
          />
        </DetailItem>

        <DetailItem
          label="Average Confidence"
        >
          <ConfidenceBar
            value={invoice.avgConfidence}
          />
        </DetailItem>

        <DetailItem
          label="Missing Fields"
          value={missingFields}
          wide
        />

        <DetailItem
          label="Bedrock Comments"
          value={invoice.comments || "—"}
          wide
        />

      </div>

      <div className="verdict-row">

        <div className="verdict-box">

          <div className="verdict-title">
            🤖 AI Validation
          </div>

          <StatusBadge
            type="ai"
            value={invoice.validationStatus}
          />

          <div className="verdict-extra">

            Risk

            <StatusBadge
              type="risk"
              value={invoice.risk}
            />

          </div>

        </div>

        <div className="verdict-box">

          <div className="verdict-title">
            👤 Human Decision
          </div>

          <StatusBadge
            type="human"
            value={
              invoice.reviewDecision ||
              "PENDING"
            }
          />

          <div className="verdict-extra">

            Reviewed by

            <strong>
              {" "}
              {invoice.reviewedBy || "—"}
            </strong>

          </div>

          <div className="verdict-extra">

            {invoice.reviewedAt
              ? new Date(
                  invoice.reviewedAt
                ).toLocaleString()
              : "—"}

          </div>

        </div>

      </div>

      {children}

    </div>
  );
}

function DetailItem({
  label,
  value,
  children,
  wide = false,
}) {
  return (
    <div
      className={`detail-item ${
        wide ? "wide" : ""
      }`}
    >
      <span className="detail-label">
        {label}
      </span>

      <div className="detail-value">

        {children || value || "—"}

      </div>

    </div>
  );
}