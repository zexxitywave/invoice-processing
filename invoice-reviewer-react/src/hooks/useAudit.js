import { useCallback, useEffect, useMemo, useState } from "react";
import { getAuditInvoices } from "../services/auditService";

const PAGE_SIZE = 20;

export default function useAudit() {
  const [invoices, setInvoices]         = useState([]);
  const [loading, setLoading]           = useState(true);
  const [error, setError]               = useState("");
  const [nextToken, setNextToken]       = useState(null);
  const [prevTokens, setPrevTokens]     = useState([]);
  const [currentToken, setCurrentToken] = useState(null);
  const [filters, setFilters]           = useState({ status: "", risk: "", search: "" });

  // Stable global counts from backend — never change with page
  const [globalStats, setGlobalStats] = useState({
    total: 0,
    approved: 0,
    reviewRequired: 0,
    duplicate: 0,
    humanApproved: 0,
    humanRejected: 0,
    totalPages: null,
  });

  const fetchPage = useCallback(async (token) => {
    setLoading(true);
    setError("");
    try {
      const data  = await getAuditInvoices(PAGE_SIZE, token);
      const items = Array.isArray(data) ? data : (data.items ?? []);
      setInvoices(items);
      setNextToken(data.nextToken ?? null);

      // Update global stable stats only when backend provides them
      if (data.totalCount > 0) {
        setGlobalStats({
          total:         data.totalCount,
          approved:      data.totalApproved      ?? 0,
          reviewRequired:data.totalReview        ?? 0,
          duplicate:     data.totalDuplicate     ?? 0,
          humanApproved: data.totalHumanApproved ?? 0,
          humanRejected: data.totalHumanRejected ?? 0,
          totalPages:    Math.ceil(data.totalCount / PAGE_SIZE),
        });
      }
    } catch (err) {
      setError(err.message || "Unable to load audit report.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { fetchPage(null); }, [fetchPage]);

  const refresh = useCallback(() => {
    setPrevTokens([]);
    setCurrentToken(null);
    fetchPage(null);
  }, [fetchPage]);

  const goNext = useCallback(() => {
    if (!nextToken) return;
    setPrevTokens((prev) => [...prev, currentToken]);
    setCurrentToken(nextToken);
    fetchPage(nextToken);
  }, [nextToken, currentToken, fetchPage]);

  const goPrev = useCallback(() => {
    if (!prevTokens.length) return;
    const stack = [...prevTokens];
    const token = stack.pop();
    setPrevTokens(stack);
    setCurrentToken(token);
    fetchPage(token);
  }, [prevTokens, fetchPage]);

  const filteredInvoices = useMemo(() => {
    return invoices.filter((invoice) => {
      if (filters.status && invoice.validationStatus !== filters.status) return false;
      if (filters.risk   && invoice.risk !== filters.risk)               return false;
      if (filters.search) {
        const keyword    = filters.search.toLowerCase();
        const searchable = `${invoice.invoiceId ?? ""} ${invoice.vendorName ?? ""}`.toLowerCase();
        if (!searchable.includes(keyword)) return false;
      }
      return true;
    });
  }, [filters, invoices]);

  // Average confidence calculated from current page (display only)
  const averageConfidence = useMemo(() => {
    let total = 0, count = 0;
    filteredInvoices.forEach((inv) => {
      const c = Number(inv.avgConfidence ?? inv.totalConfidence);
      if (!Number.isNaN(c)) { total += c; count++; }
    });
    return count > 0 ? `${(total / count).toFixed(1)}%` : "—";
  }, [filteredInvoices]);

  // Summary uses stable backend counts — never changes with page
  const summary = {
    total:            globalStats.total,
    approved:         globalStats.approved,
    reviewRequired:   globalStats.reviewRequired,
    duplicate:        globalStats.duplicate,
    humanApproved:    globalStats.humanApproved,
    humanRejected:    globalStats.humanRejected,
    averageConfidence,
  };

  return {
    invoices, filteredInvoices, filters, setFilters,
    summary, loading, error,
    refresh, goNext, goPrev,
    hasNext:    !!nextToken,
    hasPrev:    prevTokens.length > 0,
    pageNumber: prevTokens.length + 1,
    totalPages: globalStats.totalPages,
  };
}
