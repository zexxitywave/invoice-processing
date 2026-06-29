import { useCallback, useState } from "react";

import {
  getUploadURL,
  uploadFileToS3,
  validatePDF,
} from "../services/uploadService";

let nextId = 0;

export default function useUpload() {
  const [files, setFiles] = useState([]);

  const [result, setResult] = useState({
    type: "",
    message: "",
  });

  /**
   * --------------------------------------------------------
   * Update one file inside the queue
   * --------------------------------------------------------
   */

  const updateFile = useCallback((id, changes) => {
    setFiles((previous) =>
      previous.map((file) =>
        file.id === id
          ? {
              ...file,
              ...changes,
            }
          : file
      )
    );
  }, []);

  /**
   * --------------------------------------------------------
   * Add selected files
   * --------------------------------------------------------
   */

  const addFiles = useCallback((fileList) => {
    const selectedFiles = Array.from(fileList);

    setResult({
      type: "",
      message: "",
    });

    setFiles((previous) => {
      const queue = [...previous];

      selectedFiles.forEach((file) => {
        try {
          validatePDF(file);

          const duplicate = queue.find(
            (item) =>
              item.file.name === file.name &&
              item.file.size === file.size
          );

          if (duplicate) {
            return;
          }

          queue.push({
            id: nextId++,

            file,

            status: "pending",

            progress: 0,

            message: "Ready",
          });
        } catch (error) {
          console.warn(error.message);
        }
      });

      return queue;
    });
  }, []);

  /**
   * --------------------------------------------------------
   * Remove one file
   * --------------------------------------------------------
   */

  const removeFile = useCallback((id) => {
    setFiles((previous) =>
      previous.filter((file) => file.id !== id)
    );
  }, []);

  /**
   * --------------------------------------------------------
   * Clear queue
   * --------------------------------------------------------
   */

  const clearAll = useCallback(() => {
    setFiles([]);

    setResult({
      type: "",
      message: "",
    });
  }, []);

  /**
   * --------------------------------------------------------
   * Retry upload
   * --------------------------------------------------------
   */

  const retryUpload = useCallback((id) => {
    setFiles((previous) =>
      previous.map((file) =>
        file.id === id
          ? {
              ...file,
              status: "pending",
              progress: 0,
              message: "Ready",
            }
          : file
      )
    );
  }, []);

  /**
   * --------------------------------------------------------
   * Upload a single invoice
   * --------------------------------------------------------
   */

  const uploadOne = useCallback(
    async (entry) => {
      try {
        updateFile(entry.id, {
          status: "uploading",
          progress: 5,
          message: "Getting upload URL...",
        });

        const uploadUrl = await getUploadURL(
          entry.file.name
        );

        updateFile(entry.id, {
          progress: 20,
          message: "Uploading...",
        });

        await uploadFileToS3(
          uploadUrl,
          entry.file,
          (percentage) => {
            updateFile(entry.id, {
              progress:
                20 +
                Math.round(
                  percentage * 0.8
                ),

              message:
                percentage + "%",
            });
          }
        );

        updateFile(entry.id, {
          status: "done",

          progress: 100,

          message: "Upload Complete",
        });

        return true;
      } catch (error) {
        updateFile(entry.id, {
          status: "error",

          progress: 0,

          message:
            error.message ||
            "Upload failed",
        });

        return false;
      }
    },
    [updateFile]
  );

    /**
   * --------------------------------------------------------
   * Upload all pending files
   * --------------------------------------------------------
   */

  const uploadAll = useCallback(async () => {
    setResult({
      type: "",
      message: "",
    });

    // Snapshot of pending files
    const pendingFiles = files.filter(
      (file) => file.status === "pending"
    );

    if (!pendingFiles.length) {
      return;
    }

    const uploadResults = await Promise.all(
      pendingFiles.map(uploadOne)
    );

    const successCount = uploadResults.filter(Boolean).length;
    const failedCount = uploadResults.length - successCount;

    if (failedCount === 0) {
      setResult({
        type: "success",
        message:
          `✅ ${successCount} invoice${
            successCount > 1 ? "s" : ""
          } uploaded successfully!\n\n` +
          "Processing pipeline started.\n" +
          "Results will appear on Dashboard in approximately 30 seconds.",
      });
    } else {
      setResult({
        type: "error",
        message:
          `⚠ ${successCount} uploaded successfully.\n` +
          `${failedCount} failed.`,
      });
    }
  }, [files, uploadOne]);

  /**
   * --------------------------------------------------------
   * Remove completed uploads
   * --------------------------------------------------------
   */

  const removeCompleted = useCallback(() => {
    setFiles((previous) =>
      previous.filter(
        (file) => file.status !== "done"
      )
    );
  }, []);

  /**
   * --------------------------------------------------------
   * Clear failed uploads
   * --------------------------------------------------------
   */

  const clearFailed = useCallback(() => {
    setFiles((previous) =>
      previous.filter(
        (file) => file.status !== "error"
      )
    );
  }, []);

  /**
   * --------------------------------------------------------
   * Queue statistics
   * --------------------------------------------------------
   */

  const queueStats = {
    total: files.length,

    pending: files.filter(
      (file) => file.status === "pending"
    ).length,

    uploading: files.filter(
      (file) => file.status === "uploading"
    ).length,

    completed: files.filter(
      (file) => file.status === "done"
    ).length,

    failed: files.filter(
      (file) => file.status === "error"
    ).length,
  };

  /**
   * --------------------------------------------------------
   * Return
   * --------------------------------------------------------
   */

  return {
    files,

    result,

    queueStats,

    addFiles,

    removeFile,

    clearAll,

    retryUpload,

    removeCompleted,

    clearFailed,

    uploadAll,
  };
}