import { useEffect } from "react";

import "./Audit.css";

import Navbar from "../../components/Navbar";
import AuditSummary from "../../components/AuditSummary";
import AuditTable from "../../components/AuditTable";
import ErrorBanner from "../../components/ErrorBanner";
import LoadingSpinner from "../../components/LoadingSpinner";

import useAudit from "../../hooks/useAudit";

import { exportAuditCSV } from "../../services/auditService";

export default function Audit() {
  const {
    filteredInvoices,
    filters,
    setFilters,
    summary,
    loading,
    error,
    refresh,
  } = useAudit();

  useEffect(() => {
    document.title =
      "Audit Report | Invoice Processing System";

    return () => {
      document.title =
        "Invoice Processing System";
    };
  }, []);

  return (
    <>
      <Navbar />

      <main className="container">
        <div className="audit-header">
          <div>
            <h1 className="page-title">
              Audit Report
            </h1>

            <p className="page-subtitle">
              Search, filter and export processed invoices
            </p>
          </div>

          <div className="audit-actions">
            <button
              className="btn btn-secondary"
              onClick={refresh}
              disabled={loading}
            >
              🔄 Refresh
            </button>

            <button
              className="btn btn-primary"
              onClick={() =>
                exportAuditCSV(filteredInvoices)
              }
              disabled={!filteredInvoices.length}
            >
              ⬇ Export CSV
            </button>
          </div>
        </div>

        <ErrorBanner
          message={error}
        />

        <AuditSummary
          summary={summary}
        />

        <section className="card audit-filters">
          <div className="card-header">
            <h2>
              Filters
            </h2>
          </div>

          <div className="filter-grid">
            <input
              type="text"
              className="filter-input"
              placeholder="Search Invoice ID or Vendor..."
              value={filters.search}
              onChange={(e) =>
                setFilters((prev) => ({
                  ...prev,
                  search: e.target.value,
                }))
              }
            />

            <select
              className="filter-select"
              value={filters.status}
              onChange={(e) =>
                setFilters((prev) => ({
                  ...prev,
                  status: e.target.value,
                }))
              }
            >
              <option value="">
                All AI Status
              </option>

              <option value="APPROVED">
                Approved
              </option>

              <option value="REVIEW_REQUIRED">
                Review Required
              </option>

              <option value="DUPLICATE">
                Duplicate
              </option>
            </select>

            <select
              className="filter-select"
              value={filters.risk}
              onChange={(e) =>
                setFilters((prev) => ({
                  ...prev,
                  risk: e.target.value,
                }))
              }
            >
              <option value="">
                All Risk Levels
              </option>

              <option value="LOW">
                Low
              </option>

              <option value="MEDIUM">
                Medium
              </option>

              <option value="HIGH">
                High
              </option>
            </select>
          </div>
        </section>

        {loading ? (
          <LoadingSpinner
            text="Loading audit report..."
          />
        ) : (
          <section className="card audit-table-card">
            <div className="card-header">
              <h2>
                Audit History
              </h2>

              <span className="table-count">
                {filteredInvoices.length} Invoice
                {filteredInvoices.length !== 1
                  ? "s"
                  : ""}
              </span>
            </div>

            <AuditTable
              invoices={filteredInvoices}
            />
          </section>
        )}
      </main>
    </>
  );
}