import "./ResultBanner.css";

export default function ResultBanner({ result }) {
  if (!result?.message) return null;

  return (
    <div
      className={`result-banner ${
        result.type === "success"
          ? "success"
          : "error"
      }`}
    >
      {result.message}
    </div>
  );
}