import { useMemo } from "react";

import MetricCard from "../MetricCard";

import "./AuditSummary.css";

export default function AuditSummary({ summary }) {

  const cards = useMemo(
    () => [
      {
        title: "Total Invoices",
        value: summary.total,
        icon: "📊",
        variant: "total",
      },
      {
        title: "AI Approved",
        value: summary.approved,
        icon: "✅",
        variant: "approved",
      },
      {
        title: "Review Required",
        value: summary.reviewRequired,
        icon: "⚠️",
        variant: "review",
      },
      {
        title: "Duplicates",
        value: summary.duplicate,
        icon: "📄",
        variant: "duplicate",
      },
      {
        title: "Human Approved",
        value: summary.humanApproved,
        icon: "👍",
        variant: "approved",
      },
      {
        title: "Human Rejected",
        value: summary.humanRejected,
        icon: "❌",
        variant: "duplicate",
      },
      {
        title: "Avg Confidence",
        value: summary.averageConfidence,
        icon: "🎯",
        variant: "total",
      },
    ],
    [summary]
  );

  return (
    <section className="audit-summary">
      {cards.map((card) => (
        <MetricCard
          key={card.title}
          {...card}
        />
      ))}
    </section>
  );
}