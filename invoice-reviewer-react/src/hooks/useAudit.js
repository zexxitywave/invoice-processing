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
  const [totalCount, setTotalCount]     = useState(0);
  const [filters, setFilters]           = useState({ status: "", risk: "", search: "" });

  const fetchPage = useCallback(async (token) => {
    setLoading(true);
    setError("");
    try {
      const data = await getAuditInvoices(PAGE_SIZE, token);
      setInvoices(data.items ?? data);
      setNextToken(data.nextToken ?? null);
      if (data.totalCount > 0) setTotalCount(data.totalCount);
    } catch (err) {
      setError(err.message || "Unable to load audit report.");
    } finally {
      setLoading(false);
    }
  }, []);

  // Initial load
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

  const summary = useMemo(() => {
    const stats = {
      total: filteredInvoices.length,
      approved: 0, reviewRequired: 0, duplicate: 0,
      humanApproved: 0, humanRejected: 0, averageConfidence: "—",
    };
    let confidenceTotal = 0, confidenceCount = 0;
    filteredInvoices.forEach((invoice) => {
      if (invoice.validationStatus === "APPROVED")        stats.approved++;
      if (invoice.validationStatus === "REVIEW_REQUIRED") stats.reviewRequired++;
      if (invoice.validationStatus === "DUPLICATE")       stats.duplicate++;
      if (invoice.reviewDecision   === "APPROVED")        stats.humanApproved++;
      if (invoice.reviewDecision   === "REJECTED")        stats.humanRejected++;
      const c = Number(invoice.avgConfidence ?? invoice.totalConfidence);
      if (!Number.isNaN(c)) { confidenceTotal += c; confidenceCount++; }
    });
    if (confidenceCount > 0)
      stats.averageConfidence = `${(confidenceTotal / confidenceCount).toFixed(1)}%`;
    return stats;
  }, [filteredInvoices]);

  return {
    invoices, filteredInvoices, filters, setFilters,
    summary, loading, error,
    refresh, goNext, goPrev,
    hasNext: !!nextToken,
    hasPrev: prevTokens.length > 0,
    pageNumber: prevTokens.length + 1,
    totalPages: totalCount > 0 ? Math.ceil(totalCount / PAGE_SIZE) : null,
  };
}
