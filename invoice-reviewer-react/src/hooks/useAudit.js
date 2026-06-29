import { useCallback, useEffect, useMemo, useState } from "react";

import {
  getAuditInvoices,
} from "../services/auditService";

export default function useAudit() {

  const [invoices, setInvoices] = useState([]);

  const [loading, setLoading] = useState(true);

  const [error, setError] = useState("");

  const [filters, setFilters] = useState({
    status: "",
    risk: "",
    search: "",
  });

  const refresh = useCallback(async () => {

    setLoading(true);

    setError("");

    try {

      const data = await getAuditInvoices();

      setInvoices(data);

    } catch (err) {

      setError(
        err.message ||
        "Unable to load audit report."
      );

    } finally {

      setLoading(false);

    }

  }, []);

  useEffect(() => {

    refresh();

  }, [refresh]);

  const filteredInvoices = useMemo(() => {

    return invoices.filter((invoice) => {

      if (
        filters.status &&
        invoice.validationStatus !== filters.status
      ) {
        return false;
      }

      if (
        filters.risk &&
        invoice.risk !== filters.risk
      ) {
        return false;
      }

      if (filters.search) {

        const keyword = filters.search.toLowerCase();

        const searchable =
          `${invoice.invoiceId ?? ""} ${invoice.vendorName ?? ""}`
            .toLowerCase();

        if (!searchable.includes(keyword)) {
          return false;
        }

      }

      return true;

    });

  }, [filters, invoices]);

  const summary = useMemo(() => {

    const stats = {

      total: filteredInvoices.length,

      approved: 0,

      reviewRequired: 0,

      duplicate: 0,

      humanApproved: 0,

      humanRejected: 0,

      averageConfidence: "—",

    };

    let confidenceTotal = 0;

    let confidenceCount = 0;

    filteredInvoices.forEach((invoice) => {

      switch (invoice.validationStatus) {

        case "APPROVED":
          stats.approved++;
          break;

        case "REVIEW_REQUIRED":
          stats.reviewRequired++;
          break;

        case "DUPLICATE":
          stats.duplicate++;
          break;

        default:
          break;

      }

      if (invoice.reviewDecision === "APPROVED") {
        stats.humanApproved++;
      }

      if (invoice.reviewDecision === "REJECTED") {
        stats.humanRejected++;
      }

      const confidence = Number(
        invoice.avgConfidence ??
        invoice.totalConfidence
      );

      if (!Number.isNaN(confidence)) {

        confidenceTotal += confidence;

        confidenceCount++;

      }

    });

    if (confidenceCount > 0) {

      stats.averageConfidence =
        `${(confidenceTotal / confidenceCount).toFixed(1)}%`;

    }

    return stats;

  }, [filteredInvoices]);

  return {

    invoices,

    filteredInvoices,

    filters,

    setFilters,

    summary,

    loading,

    error,

    refresh,

  };

}