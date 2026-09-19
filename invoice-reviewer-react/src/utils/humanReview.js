/**
 * Resolves the value to display in the "Human Review" column / badge.
 *
 * The pipeline only writes `reviewDecision` when a human actually clicks
 * Approve/Reject. Invoices the AI auto-approves never get a human decision,
 * so we must not mislabel them as "PENDING" (which implies waiting on a
 * reviewer). Only REVIEW_REQUIRED invoices are genuinely pending.
 *
 *   reviewDecision set        -> APPROVED / REJECTED
 *   REVIEW_REQUIRED, no human -> PENDING
 *   anything else (auto/dup)  -> NOT_REQUIRED
 */
export function humanReviewValue(invoice) {
  const decision = invoice?.reviewDecision;

  if (decision && decision !== "PENDING") {
    return decision;
  }

  return invoice?.validationStatus === "REVIEW_REQUIRED"
    ? "PENDING"
    : "NOT_REQUIRED";
}
