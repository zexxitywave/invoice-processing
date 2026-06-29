import { useCallback, useEffect, useState } from "react";

import {
  getInvoices,
} from "../services/dashboardService";

export default function useDashboard() {
  const [invoices, setInvoices] = useState([]);

  const [loading, setLoading] = useState(true);

  const [error, setError] = useState("");

  const [stats, setStats] = useState({
    approved: 0,
    reviewRequired: 0,
    duplicate: 0,
    total: 0,
  });

  const calculateStats = useCallback((data) => {
    const counts = {
      approved: 0,
      reviewRequired: 0,
      duplicate: 0,
      total: data.length,
    };

    data.forEach((invoice) => {
      switch (invoice.validationStatus) {
        case "APPROVED":
          counts.approved++;
          break;

        case "REVIEW_REQUIRED":
          counts.reviewRequired++;
          break;

        case "DUPLICATE":
          counts.duplicate++;
          break;

        default:
          break;
      }
    });

    return counts;
  }, []);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError("");

    try {
      const data = await getInvoices();

      setInvoices(data);

      setStats(
        calculateStats(data)
      );

    } catch (err) {
      setError(
        err.message ||
        "Unable to load dashboard."
      );
    } finally {
      setLoading(false);
    }
  }, [calculateStats]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return {
    invoices,

    stats,

    loading,

    error,

    refresh,
  };
}