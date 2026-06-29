import "./MetricCard.css";

const subtitles = {
  approved: "AI validated successfully",
  review: "Requires human review",
  duplicate: "Potential duplicate invoice",
  total: "Invoices processed",
  default: "System metric",
};

export default function MetricCard({
  title,
  value,
  icon,
  variant = "default",
}) {
  return (
    <div className={`metric-card ${variant}`}>

      <div className="metric-header">

        <div>

          <div className="metric-title">
            {title}
          </div>

          <div className="metric-subtitle">
            {subtitles[variant] || subtitles.default}
          </div>

        </div>

        <div className="metric-icon">
          {icon}
        </div>

      </div>

      <div className="metric-value">
        {value}
      </div>

    </div>
  );
}