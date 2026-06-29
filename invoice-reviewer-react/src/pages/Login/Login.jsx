import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";

import "./Login.css";

import {
  login,
  isAuthenticated,
} from "../../services/authService";

export default function Login() {
  const navigate = useNavigate();

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");

  const [loading, setLoading] = useState(false);

  const [error, setError] = useState("");

  useEffect(() => {
    document.title =
      "Login | Invoice Processing System";

    if (isAuthenticated()) {
      navigate("/upload");
    }
  }, [navigate]);

  const handleSubmit = async (event) => {
    event.preventDefault();

    setError("");
    setLoading(true);

    try {
      const response = await login(
        username.trim(),
        password
      );

      if (response.success) {
        navigate("/upload");
      } else {
        setError(
          response.message ||
            "Invalid username or password."
        );
      }
    } catch (err) {
      setError(
        err.message ||
          "Something went wrong."
      );
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-page">
      <div className="login-box">

        <div className="login-logo">
          📄
        </div>

        <h1 className="login-title">
          Invoice Processing System
        </h1>

        <p className="login-sub">
          Sign in to access the reviewer dashboard
        </p>

        {error && (
          <div className="login-error show">
            ❌ {error}
          </div>
        )}

        <form onSubmit={handleSubmit}>

          <div className="form-row">

            <label htmlFor="username">
              Username
            </label>

            <input
              id="username"
              type="text"
              placeholder="Enter username"
              autoComplete="username"
              autoFocus
              required
              value={username}
              onChange={(e) =>
                setUsername(e.target.value)
              }
            />

          </div>

          <div className="form-row">

            <label htmlFor="password">
              Password
            </label>

            <input
              id="password"
              type="password"
              placeholder="Enter password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) =>
                setPassword(e.target.value)
              }
            />

          </div>

          <button
            type="submit"
            className="login-btn"
            disabled={loading}
          >
            {loading
              ? "Signing In..."
              : "Sign In →"}
          </button>

        </form>

        <div className="login-footer">
          zexxity.online · Invoice Processing System
        </div>

      </div>
    </div>
  );
}