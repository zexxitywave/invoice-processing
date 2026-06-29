import StatusBadge from "../StatusBadge";
import ConfidenceBar from "../ConfidenceBar";

import "./AuditTable.css";

export default function AuditTable({
  invoices = [],
}) {

  if (!invoices.length) {

    return (
      <div className="table-empty">
        No invoices found.
      </div>
    );

  }

  return (

    <div className="table-wrapper">

      <table className="audit-table">

        <thead>

          <tr>

            <th>Invoice ID</th>

            <th>Vendor</th>

            <th>Total</th>

            <th>AI Status</th>

            <th>Human Review</th>

            <th>Risk</th>

            <th>Confidence</th>

          </tr>

        </thead>

        <tbody>

          {invoices.map((invoice) => (

            <tr key={invoice.invoiceId}>

              <td>
                <code>{invoice.invoiceId}</code>
              </td>

              <td>
                {invoice.vendorName || "—"}
              </td>

              <td>
                {invoice.total || "—"}
              </td>

              <td>

                <StatusBadge
                  type="ai"
                  value={invoice.validationStatus}
                />

              </td>

              <td>

                <StatusBadge
                  type="human"
                  value={
                    invoice.reviewDecision ||
                    "PENDING"
                  }
                />

              </td>

              <td>

                <StatusBadge
                  type="risk"
                  value={invoice.risk}
                />

              </td>

              <td>

                <ConfidenceBar
                  value={
                    invoice.avgConfidence ??
                    invoice.totalConfidence
                  }
                />

              </td>

            </tr>

          ))}

        </tbody>

      </table>

    </div>

  );

}