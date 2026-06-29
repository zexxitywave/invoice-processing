import { useEffect, useMemo } from "react";

import "./Upload.css";

import Navbar from "../../components/Navbar";
import DropZone from "../../components/DropZone";
import FileQueue from "../../components/FileQueue";
import ResultBanner from "../../components/ResultBanner";

import useUpload from "../../hooks/useUpload";

export default function Upload() {
  const {
    files,
    result,
    queueStats,
    addFiles,
    removeFile,
    retryUpload,
    clearAll,
    uploadAll,
  } = useUpload();

  useEffect(() => {
    document.title =
      "Upload Invoice | Invoice Processing System";

    return () => {
      document.title =
        "Invoice Processing System";
    };
  }, []);

  const hasFiles = files.length > 0;

  const fileQueueProps = useMemo(
    () => ({
      files,
      removeFile,
      retryUpload,
    }),
    [files, removeFile, retryUpload]
  );

  const pipelineSteps = useMemo(
    () => [
      {
        icon: "1️⃣",
        title: "PDF lands in S3",
        text: "EventBridge detects the new file",
      },
      {
        icon: "2️⃣",
        title: "Step Functions",
        text: "Orchestrates the complete workflow",
      },
      {
        icon: "3️⃣",
        title: "Amazon Textract",
        text: "Extracts invoice fields and confidence",
      },
      {
        icon: "4️⃣",
        title: "Amazon Bedrock",
        text: "Validates invoice using AI",
      },
      {
        icon: "5️⃣",
        title: "DynamoDB",
        text: "Stores extracted invoice data",
      },
      {
        icon: "6️⃣",
        title: "SES Notification",
        text: "Alerts reviewer if confidence is below 95%",
      },
    ],
    []
  );

  return (
    <>
      <Navbar />

      <main className="container">

        <div className="upload-page-header">

          <div>

            <h1 className="page-title">
              Upload Invoices
            </h1>

            <p className="page-subtitle">
              Upload invoice PDFs and let AI validate them automatically.
            </p>

          </div>

        </div>

        <section className="upload-grid">

          {/* Upload Card */}

          <div className="upload-card card">

            <div className="card-header">

              <h2>Upload PDF Files</h2>

              <span className="queue-count">

                {hasFiles
                  ? `${files.length} file${files.length !== 1 ? "s" : ""} selected`
                  : "No files selected"}

              </span>

            </div>

            <DropZone
              onFilesSelected={addFiles}
              hasFiles={hasFiles}
            />

          </div>

          {/* Pipeline */}

          <div className="card upload-info">

            <div className="card-header">

              <h2>AI Processing Pipeline</h2>

            </div>

            <div className="info-box">

              {pipelineSteps.map((step) => (

                <div
                  key={step.icon}
                  className="pipeline-step"
                >

                  <span className="pipeline-icon">
                    {step.icon}
                  </span>

                  <div>

                    <strong>
                      {step.title}
                    </strong>

                    <p>
                      {step.text}
                    </p>

                  </div>

                </div>

              ))}

              <div className="pipeline-footer">

                Results will appear on the Dashboard in approximately

                <strong> 30 seconds.</strong>

              </div>

            </div>

          </div>

        </section>

        {/* Selected Files */}

        <section className="card upload-files">

          <div className="card-header">

            <h2>Selected Files</h2>

            <span className="queue-count">

              {hasFiles
                ? `${files.length} file${files.length !== 1 ? "s" : ""}`
                : "Empty"}

            </span>

          </div>

          <FileQueue upload={fileQueueProps} />

          <ResultBanner result={result} />

          <div className="upload-actions">

            <button
              className="btn btn-primary"
              onClick={uploadAll}
              disabled={
                queueStats.pending === 0 ||
                queueStats.uploading > 0
              }
            >
              {queueStats.uploading > 0
                ? "Uploading..."
                : "⬆ Upload All"}
            </button>

            <button
              className="btn btn-secondary"
              onClick={clearAll}
              disabled={queueStats.uploading > 0}
            >
              Clear All
            </button>

            <span className="upload-summary">

              {queueStats.completed > 0 &&
                `${queueStats.completed} Completed`}

              {queueStats.uploading > 0 &&
                ` • ${queueStats.uploading} Uploading`}

              {queueStats.failed > 0 &&
                ` • ${queueStats.failed} Failed`}

            </span>

          </div>

        </section>

      </main>
    </>
  );
}