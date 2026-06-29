import "./LoadingSpinner.css";

export default function LoadingSpinner({
  text = "Loading...",
}) {
  return (
    <div className="loading-spinner">

      <div className="spinner" />

      <p>{text}</p>

    </div>
  );
}