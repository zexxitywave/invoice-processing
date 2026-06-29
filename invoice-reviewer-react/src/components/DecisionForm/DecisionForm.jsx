import { useState } from "react";

import "./DecisionForm.css";

export default function DecisionForm({
  invoice,
  submitting,
  onSubmit,
}) {
  const [reviewer, setReviewer] = useState("");
  const [reason, setReason] = useState("");

  if (!invoice) return null;

  const handleSubmit = (decision) => {
    if (!reviewer.trim()) {
      alert("Please enter your email.");
      return;
    }

    onSubmit({
      decision,
      reviewer: reviewer.trim(),
      reason: reason.trim(),
    });
  };

  return (
    <div className="decision-form">

      <h3>
        Make Your Decision
      </h3>

      <div className="form-row">

        <label>
          Your Email
        </label>

        <input
          type="email"
          value={reviewer}
          onChange={(e) =>
            setReviewer(e.target.value)
          }
          placeholder="reviewer@example.com"
        />

      </div>

      <div className="form-row">

        <label>
          Note / Reason
        </label>

        <textarea
          rows={3}
          value={reason}
          onChange={(e) =>
            setReason(e.target.value)
          }
          placeholder="Optional note..."
        />

      </div>

      <div className="decision-buttons">

        <button
          className="btn btn-approve"
          disabled={submitting}
          onClick={() =>
            handleSubmit("APPROVED")
          }
        >
          {submitting
            ? "Submitting..."
            : "✅ Approve"}
        </button>

        <button
          className="btn btn-reject"
          disabled={submitting}
          onClick={() =>
            handleSubmit("REJECTED")
          }
        >
          {submitting
            ? "Submitting..."
            : "❌ Reject"}
        </button>

      </div>

    </div>
  );
}