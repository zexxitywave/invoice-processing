import { useCallback, useEffect, useState } from "react";

import {
  getReviewQueue,
  submitReview,
} from "../services/reviewService";

export default function useReview() {
  const [queue, setQueue] = useState([]);

  const [selectedInvoice, setSelectedInvoice] =
    useState(null);

  const [loading, setLoading] =
    useState(true);

  const [submitting, setSubmitting] =
    useState(false);

  const [error, setError] =
    useState("");

  const refresh = useCallback(async () => {
    setLoading(true);
    setError("");

    try {
      const data =
        await getReviewQueue();

      setQueue(data);
    } catch (err) {
      setError(
        err.message ||
        "Unable to load review queue."
      );
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const openInvoice = useCallback(
    (invoice) => {
      setSelectedInvoice(invoice);
    },
    []
  );

  const closeInvoice = useCallback(() => {
    setSelectedInvoice(null);
  }, []);

  const submitDecision = useCallback(
    async ({
      decision,
      reviewer,
      reason,
    }) => {
      if (!selectedInvoice) return;

      setSubmitting(true);

      try {
        await submitReview({
          invoiceId:
            selectedInvoice.invoiceId,
          decision,
          reviewer,
          reason,
        });

        await refresh();

        setSelectedInvoice(null);
      } catch (err) {
        setError(
          err.message ||
          "Unable to submit review."
        );
      } finally {
        setSubmitting(false);
      }
    },
    [selectedInvoice, refresh]
  );

  return {
    queue,

    selectedInvoice,

    loading,

    submitting,

    error,

    refresh,

    openInvoice,

    closeInvoice,

    submitDecision,
  };
}