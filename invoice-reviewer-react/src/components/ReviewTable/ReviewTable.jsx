import StatusBadge from "../StatusBadge";
import ConfidenceBar from "../ConfidenceBar";

import "./ReviewTable.css";

export default function ReviewTable({
  queue = [],
  onOpen,
}) {
  if (!queue.length) {
    return (
      <div className="table-empty">
        🎉 No invoices awaiting review.
      </div>
    );
  }

  return (
    <div className="table-wrapper">

      <table className="review-table">

        <thead>

          <tr>
            <th>Invoice ID</th>
            <th>Vendor</th>
            <th>Total</th>
            <th>Confidence</th>
            <th>Risk</th>
            <th>Comments</th>
            <th>Action</th>
          </tr>

        </thead>

        <tbody>

          {queue.map((invoice) => (

            <tr key={invoice.invoiceId}>

              <td>
                <code>
                  {invoice.invoiceId}
                </code>
              </td>

              <td>
                {invoice.vendorName || "—"}
              </td>

              <td>
                <strong>
                  {invoice.total || "—"}
                </strong>
              </td>

              <td>

                <ConfidenceBar
                  value={invoice.totalConfidence}
                />

              </td>

              <td>

                <StatusBadge
                  type="risk"
                  value={invoice.risk}
                />

              </td>

              <td
                className="review-comment"
                title={invoice.comments}
              >
                {invoice.comments || "—"}
              </td>

              <td>

                <button
                  className="btn btn-primary btn-sm"
                  onClick={() =>
                    onOpen(invoice)
                  }
                >
                  Open
                </button>

              </td>

            </tr>

          ))}

        </tbody>

      </table>

    </div>
  );
}