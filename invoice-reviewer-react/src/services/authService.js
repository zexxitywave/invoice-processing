/**
 * ======================================================
 * Authentication Service
 * ======================================================
 */

export async function login(username, password) {
  // Temporary authentication
  // Replace with backend API later

  if (
    username === "admin" &&
    password === "Zexxity@2024"
  ) {
    sessionStorage.setItem(
      "ips_auth",
      "true"
    );

    return {
      success: true,
    };
  }

  return {
    success: false,
    message:
      "Invalid username or password.",
  };
}

export function logout() {
  sessionStorage.removeItem(
    "ips_auth"
  );
}

export function isAuthenticated() {
  return (
    sessionStorage.getItem(
      "ips_auth"
    ) === "true"
  );
}