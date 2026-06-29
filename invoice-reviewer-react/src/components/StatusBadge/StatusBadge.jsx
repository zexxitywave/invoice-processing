import clsx from "clsx";

import "./StatusBadge.css";

const statusDots = {
  APPROVED: "🟢",
  REVIEW_REQUIRED: "🟡",
  DUPLICATE: "🟣",
  LOW: "🟢",
  MEDIUM: "🟠",
  HIGH: "🔴",
  REJECTED: "🔴",
  PENDING: "⚪",
};

export default function StatusBadge({
  type = "default",
  value,
}) {
  const badgeClass = clsx(
    "status-badge",
    `status-badge--${type}`,
    `status-badge--${String(value)
      .toLowerCase()
      .replace(/\s+/g, "-")}`
  );

  const formattedValue =
    value?.replaceAll("_", " ") ?? "";

  const label =
    formattedValue.charAt(0).toUpperCase() +
    formattedValue.slice(1).toLowerCase();

  return (
    <span className={badgeClass}>
      <span className="status-dot">
        {statusDots[value] || "•"}
      </span>

      <span>
        {label || "—"}
      </span>
    </span>
  );
}