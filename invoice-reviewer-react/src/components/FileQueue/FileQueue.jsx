import "./FileQueue.css";
import { formatFileSize } from "../../services/uploadService";

export default function FileQueue({ upload }) {

  const {
    files = [],
    removeFile = () => {},
    retryUpload = () => {},
  } = upload || {};
  if (!files.length) return null;

  return (
    <div className="file-queue">

      {files.map((item) => (

        <div
          className="file-row"
          key={item.id}
        >

          <div className="file-icon">
            📄
          </div>

          <div className="file-details">

            <div
              className="file-name"
              title={item.file.name}
            >
              {item.file.name}
            </div>

            <div className="file-size">
              {formatFileSize(item.file.size)}
            </div>

          </div>

          <div className="progress-section">

            <div className="progress-track">

              <div
                className={`progress-fill ${item.status}`}
                style={{
                  width: `${item.progress}%`,
                }}
              />

            </div>

            <div
              className={`status ${item.status}`}
            >
              {item.message}
            </div>

          </div>

          {item.status === "pending" && (

            <button
              className="icon-btn remove-btn"
              onClick={() =>removeFile(item.id)}
            >
              ✕
            </button>

          )}

          {item.status === "error" && (

            <button
              className="icon-btn retry-btn"
              onClick={() =>retryUpload(item.id)}
            >
              Retry
            </button>

          )}

        </div>

      ))}

    </div>
  );
}