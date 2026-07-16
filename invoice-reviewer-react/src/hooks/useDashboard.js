import { useCallback, useEffect, useState } from "react";
import { getInvoices } from "../services/dashboardService";

const PAGE_SIZE = 20;

export default function useDashboard() {
  const [invoices, setInvoices]         = useState([]);
  const [loading, setLoading]           = useState(true);
  const [error, setError]               = useState("");
  const [nextToken, setNextToken]       = useState(null);
  const [prevTokens, setPrevTokens]     = useState([]);
  const [currentToken, setCurrentToken] = useState(null);
  const [stats, setStats]               = useState({
    approved: 0, reviewRequired: 0, duplicate: 0, total: 0,
  });

  const calculateStats = useCallback((data) => {
    const counts = { approved: 0, reviewRequired: 0, duplicate: 0, total: data.length };
    data.forEach((invoice) => {
      if (invoice.validationStatus === "APPROVED")        counts.approved++;
      if (invoice.validationStatus === "REVIEW_REQUIRED") counts.reviewRequired++;
      if (invoice.validationStatus === "DUPLICATE")       counts.duplicate++;
    });
    return counts;
  }, []);

  const fetchPage = useCallback(async (token) => {
    setLoading(true);
    setError("");
    try {
      const data     = await getInvoices(PAGE_SIZE, token);
      const items    = data.items ?? data;
      setInvoices(items);
      setNextToken(data.nextToken ?? null);
      setStats(calculateStats(items));
    } catch (err) {
      setError(err.message || "Unable to load dashboard.");
    } finally {
      setLoading(false);
    }
  }, [calculateStats]);

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

  return {
    invoices, stats, loading, error,
    refresh, goNext, goPrev,
    hasNext: !!nextToken,
    hasPrev: prevTokens.length > 0,
    pageNumber: prevTokens.length + 1,
  };
}
