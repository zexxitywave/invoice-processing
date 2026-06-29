import { useEffect, useMemo } from "react";
import { useNavigate } from "react-router-dom";

import "./Dashboard.css";

import Navbar from "../../components/Navbar";
import MetricCard from "../../components/MetricCard";
import InvoiceTable from "../../components/InvoiceTable";
import ErrorBanner from "../../components/ErrorBanner";
import LoadingSpinner from "../../components/LoadingSpinner";

import useDashboard from "../../hooks/useDashboard";

export default function Dashboard() {
  const navigate = useNavigate();

  const {
    invoices,
    stats,
    loading,
    error,
    refresh,
  } = useDashboard();

  useEffect(() => {
    document.title =
      "Dashboard | Invoice Processing System";

    return () => {
      document.title =
        "Invoice Processing System";
    };
  }, []);

  const metricCards = useMemo(
    () => [
      {
        title: "AI Approved",
        value: stats.approved,
        icon: "✅",
        variant: "approved",
      },
      {
        title: "Review Required",
        value: stats.reviewRequired,
        icon: "⚠️",
        variant: "review",
      },
      {
        title: "Duplicates",
        value: stats.duplicate,
        icon: "📄",
        variant: "duplicate",
      },
      {
        title: "Total Invoices",
        value: stats.total,
        icon: "📊",
        variant: "total",
      },
    ],
    [stats]
  );

  const handleReview = (invoice) => {
    navigate("/review", {
      state: {
        invoice,
      },
    });
  };

  return (
    <>
      <Navbar />

      <main className="container">

        <div className="dashboard-header">

          <div>

            <h1 className="page-title">
              Dashboard
            </h1>

            <p className="page-subtitle">
              AI Invoice Validation Overview
            </p>

          </div>

          <button
            className="btn btn-primary"
            onClick={refresh}
            disabled={loading}
          >
            {loading
              ? "Refreshing..."
              : "↻ Refresh"}
          </button>

        </div>

        <ErrorBanner
          message={error}
        />

        {loading ? (

          <LoadingSpinner
            text="Loading dashboard..."
          />

        ) : (

          <>

            <section className="metrics-grid">

              {metricCards.map((card) => (

                <MetricCard
                  key={card.title}
                  {...card}
                />

              ))}

            </section>

            <section className="dashboard-table">

              <div className="card">

                <div className="card-header">

                  <h2>
                    Recent Invoices
                  </h2>

                  <span className="table-count">
                    {stats.total} Invoice
                    {stats.total !== 1
                      ? "s"
                      : ""}
                  </span>

                </div>

                <InvoiceTable
                  invoices={invoices}
                  onReview={handleReview}
                />

              </div>

            </section>

          </>

        )}

      </main>

    </>
  );
}