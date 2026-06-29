import { useEffect } from "react";

import "./Review.css";

import Navbar from "../../components/Navbar";
import ReviewTable from "../../components/ReviewTable";
import InvoiceDetail from "../../components/InvoiceDetail";
import DecisionForm from "../../components/DecisionForm";
import ErrorBanner from "../../components/ErrorBanner";
import LoadingSpinner from "../../components/LoadingSpinner";

import useReview from "../../hooks/useReview";

export default function Review() {
  const {
    queue,
    selectedInvoice,
    loading,
    submitting,
    error,
    refresh,
    openInvoice,
    closeInvoice,
    submitDecision,
  } = useReview();

  useEffect(() => {
    document.title =
      "Review Queue | Invoice Processing System";

    return () => {
      document.title =
        "Invoice Processing System";
    };
  }, []);

  return (
    <>
      <Navbar />

      <main className="container">
        <div className="review-header">
          <div>
            <h1 className="page-title">
              Review Queue
            </h1>

            <p className="page-subtitle">
              Human validation of AI flagged invoices
            </p>
          </div>

          <button
            className="btn btn-primary"
            onClick={refresh}
            disabled={loading}
          >
            {loading
              ? "Refreshing..."
              : "🔄 Refresh"}
          </button>
        </div>

        <ErrorBanner
          message={error}
        />

        {selectedInvoice && (
          <InvoiceDetail
            invoice={selectedInvoice}
            onClose={closeInvoice}
          >
            {!selectedInvoice.reviewDecision ? (
              <DecisionForm
                invoice={selectedInvoice}
                submitting={submitting}
                onSubmit={submitDecision}
              />
            ) : (
              <div
                className={`decision-complete ${
                  selectedInvoice.reviewDecision ===
                  "APPROVED"
                    ? "approved"
                    : "rejected"
                }`}
              >
                {selectedInvoice.reviewDecision ===
                "APPROVED"
                  ? "✅ This invoice has already been approved."
                  : "❌ This invoice has already been rejected."}
              </div>
            )}
          </InvoiceDetail>
        )}

        {loading ? (
          <LoadingSpinner
            text="Loading review queue..."
          />
        ) : (
          <section className="card review-table-card">
            <div className="card-header">
              <h2>
                Pending Review
              </h2>

              <span className="table-count">
                {queue.length} Invoice
                {queue.length !== 1
                  ? "s"
                  : ""}
              </span>
            </div>

            <ReviewTable
              queue={queue}
              onOpen={openInvoice}
            />
          </section>
        )}
      </main>
    </>
  );
}