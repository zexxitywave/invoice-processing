/**
 * ============================================================================
 * uploadService.js
 *
 * Handles communication with the existing Java backend.
 * Backend APIs remain unchanged.
 * ============================================================================
 */

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "";

const REQUEST_TIMEOUT = 30000;

// How many PDFs are pushed to AWS at the same time. Kept very low so
// parallel uploads stay well under the account Lambda concurrency
// quota (10) instead of getting throttled.
export const MAX_CONCURRENT_UPLOADS = 2;

// Retries are applied to transient failures only.
const RETRYABLE_STATUS_CODES = [429, 500, 502, 503, 504];

const RETRYABLE_ERROR_PATTERN =
  /network|timed out|timeout|unavailable|failed to fetch/i;

export const MAX_FILE_SIZE = 10 * 1024 * 1024;

/**
 * ============================================================================
 * Retry Helpers
 * ============================================================================
 */

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export function isRetryableError(error) {
  if (!error) return false;

  if (RETRYABLE_STATUS_CODES.includes(error.status)) {
    return true;
  }

  if (error.name === "AbortError") {
    return true;
  }

  return RETRYABLE_ERROR_PATTERN.test(error.message || "");
}

/**
 * Run `operation` and retry on transient failures with jittered
 * exponential backoff so many clients never retry in lockstep.
 * Permanent errors (validation, 4xx other than 429) fail fast.
 */
export async function withRetry(
  operation,
  { retries = 5, baseDelay = 1000 } = {}
) {
  let attempt = 0;

  for (;;) {
    try {
      return await operation();
    } catch (error) {
      attempt += 1;

      if (attempt > retries || !isRetryableError(error)) {
        throw error;
      }

      // Full jitter: random between 0 and base * 2^(attempt-1)
      const window = baseDelay * 2 ** (attempt - 1);

      await sleep(Math.floor(Math.random() * window));
    }
  }
}

export const ALLOWED_FILE_TYPES = [
  "application/pdf",
];

/**
 * ============================================================================
 * Generic Request Wrapper
 * ============================================================================
 */

export async function request(endpoint, options = {}) {
  const controller = new AbortController();

  const timeout = setTimeout(() => {
    controller.abort();
  }, REQUEST_TIMEOUT);

  try {
    const response = await fetch(
      `${API_BASE_URL}${endpoint}`,
      {
        signal: controller.signal,
        ...options,
      }
    );

    clearTimeout(timeout);

    let data = {};

    const contentType =
      response.headers.get("content-type") || "";

    if (contentType.includes("application/json")) {
      data = await response.json();
    } else {
      const text = await response.text();
      data = {
        message: text,
      };
    }

    if (!response.ok) {
      const error = new Error(
        data.error ||
          data.message ||
          `HTTP ${response.status}`
      );

      error.status = response.status;

      throw error;
    }

    return data;
  } catch (error) {
    clearTimeout(timeout);

    if (error.name === "AbortError") {
      throw new Error(
        "Request timed out. Please try again."
      );
    }

    throw error;
  }
}

/**
 * ============================================================================
 * Validate PDF File
 * ============================================================================
 */

export function validatePDF(file) {
  if (!file) {
    throw new Error("No file selected.");
  }

  const isPDF =
    ALLOWED_FILE_TYPES.includes(file.type) ||
    file.name.toLowerCase().endsWith(".pdf");

  if (!isPDF) {
    throw new Error(
      "Only PDF files are allowed."
    );
  }

  if (file.size > MAX_FILE_SIZE) {
    throw new Error(
      "Maximum allowed file size is 10 MB."
    );
  }

  return true;
}

/**
 * ============================================================================
 * Get Presigned Upload URL
 *
 * POST /invoices/upload-url
 * ============================================================================
 */

export async function getUploadURL(fileName) {
  if (!fileName) {
    throw new Error("Filename is required.");
  }

  const data = await withRetry(() =>
    request(
      "/invoices/upload-url",
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          fileName,
        }),
      }
    )
  );

  if (!data.uploadUrl) {
    throw new Error(
      "Backend did not return an upload URL."
    );
  }

  return data.uploadUrl;
}

/**
 * ============================================================================
 * Upload PDF directly to S3
 * ============================================================================
 *
 * @param {string} uploadUrl
 * @param {File} file
 * @param {(progress:number)=>void} onProgress
 * @returns {Promise<void>}
 */

export function uploadFileToS3(
  uploadUrl,
  file,
  onProgress = () => {}
) {
  validatePDF(file);

  if (!uploadUrl) {
    throw new Error("Invalid upload URL.");
  }

  return withRetry(() =>
    uploadOnceToS3(uploadUrl, file, onProgress)
  );
}

/**
 * Single PUT attempt via XHR. Retried by uploadFileToS3 on
 * transient network / 5xx failures.
 */
function uploadOnceToS3(
  uploadUrl,
  file,
  onProgress = () => {}
) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();

    xhr.upload.addEventListener(
      "progress",
      (event) => {
        if (!event.lengthComputable) return;

        const percent = Math.round(
          (event.loaded / event.total) * 100
        );

        onProgress(percent);
      }
    );

    xhr.addEventListener("load", () => {
      if (
        xhr.status >= 200 &&
        xhr.status < 300
      ) {
        resolve();
      } else {
        const error = new Error(
          `S3 upload failed (${xhr.status})`
        );

        error.status = xhr.status;

        reject(error);
      }
    });

    xhr.addEventListener("error", () => {
      reject(
        new Error(
          "Network error while uploading."
        )
      );
    });

    xhr.addEventListener("abort", () => {
      reject(
        new Error(
          "Upload cancelled."
        )
      );
    });

    xhr.open("PUT", uploadUrl);

    xhr.setRequestHeader(
      "Content-Type",
      "application/pdf"
    );

    xhr.send(file);
  });
}

/**
 * ============================================================================
 * Format File Size
 * ============================================================================
 */

export function formatFileSize(bytes) {
  if (bytes < 1024) {
    return `${bytes} B`;
  }

  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }

  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}