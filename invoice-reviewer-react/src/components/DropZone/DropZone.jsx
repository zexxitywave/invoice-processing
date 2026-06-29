import { useRef, useState } from "react";
import clsx from "clsx";

import "./DropZone.css";

export default function DropZone({
  onFilesSelected,
  hasFiles,
}) {
  const inputRef = useRef(null);

  const [dragging, setDragging] = useState(false);

  const openFilePicker = () => {
    inputRef.current?.click();
  };

  const handleFiles = (files) => {
    if (!files || files.length === 0) return;

    onFilesSelected(files);
  };

  const handleChange = (event) => {
    handleFiles(event.target.files);

    // Allow selecting the same file again
    event.target.value = "";
  };

  const handleDragOver = (event) => {
    event.preventDefault();
    setDragging(true);
  };

  const handleDragLeave = (event) => {
    event.preventDefault();
    setDragging(false);
  };

  const handleDrop = (event) => {
    event.preventDefault();

    setDragging(false);

    handleFiles(event.dataTransfer.files);
  };

  return (
    <>
      <div
        className={clsx("drop-zone", {
          dragover: dragging,
          "has-files": hasFiles,
        })}
        onClick={openFilePicker}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
      >

        <div className="drop-icon">

          {hasFiles ? "✅" : "📄"}

        </div>

        <h3 className="drop-title">

          {hasFiles
            ? "Files Ready for Upload"
            : "Drag & Drop Invoice PDFs"}

        </h3>

        <p className="drop-sub">

          {hasFiles
            ? "You can add more files or upload the current selection."
            : "Drop PDF files here or browse from your computer. Multiple files are supported."}

        </p>

        <button
          type="button"
          className="btn btn-primary"
          onClick={(e) => {
            e.stopPropagation();
            openFilePicker();
          }}
        >
          📂 Browse Files
        </button>

        <small className="drop-note">

          PDF only • Maximum 10 MB per file

        </small>

      </div>

      <input
        ref={inputRef}
        type="file"
        accept=".pdf,application/pdf"
        multiple
        hidden
        onChange={handleChange}
      />
    </>
  );
}