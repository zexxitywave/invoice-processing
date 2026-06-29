import "./ConfidenceBar.css";

export default function ConfidenceBar({
  value = 0,
  showPercentage = true,
}) {
  const confidence = Number(value);

  if (Number.isNaN(confidence)) {
    return <span className="confidence-empty">—</span>;
  }

  const percentage = Math.max(
    0,
    Math.min(confidence, 100)
  );

  let colorClass = "low";

  if (percentage >= 95) {
    colorClass = "high";
  } else if (percentage >= 80) {
    colorClass = "medium";
  }

  return (
    <div className="confidence-wrapper">

      <div className="confidence-track">

        <div
          className={`confidence-fill ${colorClass}`}
          style={{
            width: `${percentage}%`,
          }}
        />

      </div>

      {showPercentage && (
        <span className="confidence-label">
          {percentage.toFixed(1)}%
        </span>
      )}

    </div>
  );
}