// =============================================================================
// auth.js – session-based login guard
// Credentials: change AUTH_USER and AUTH_PASS before deploying.
// =============================================================================

const AUTH_USER = "admin";
const AUTH_PASS = "Zexxity@2024";
const AUTH_KEY  = "ips_auth";

function doLogin(event) {
  event.preventDefault();
  const user  = document.getElementById('username').value.trim();
  const pass  = document.getElementById('password').value;
  const errEl = document.getElementById('login-error');

  if (user === AUTH_USER && pass === AUTH_PASS) {
    sessionStorage.setItem(AUTH_KEY, 'true');
    window.location.href = '/index.html';
  } else {
    errEl.style.display = 'block';
    document.getElementById('password').value = '';
    document.getElementById('password').focus();
  }
}

function requireLogin() {
  if (sessionStorage.getItem(AUTH_KEY) !== 'true') {
    window.location.href = '/login.html';
  }
}

function logout() {
  sessionStorage.removeItem(AUTH_KEY);
  window.location.href = '/login.html';
}
