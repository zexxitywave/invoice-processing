import { NavLink, useNavigate } from "react-router-dom";

import "./Navbar.css";

export default function Navbar() {
  const navigate = useNavigate();

  const logout = () => {
    sessionStorage.removeItem("ips_auth");
    navigate("/login", { replace: true });
  };

  const navItems = [
    {
      label: "Dashboard",
      path: "/dashboard",
      icon: "📊",
    },
    {
      label: "Upload",
      path: "/upload",
      icon: "📤",
    },
    {
      label: "Review",
      path: "/review",
      icon: "📝",
    },
    {
      label: "Audit",
      path: "/audit",
      icon: "📋",
    },
  ];

  return (
    <nav className="navbar">

      <div
        className="nav-brand"
        role="button"
        tabIndex={0}
        onClick={() => navigate("/dashboard")}
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            navigate("/dashboard");
          }
        }}
      >
        🤖
        <span>InvoiceAI Pro</span>
      </div>

      <div className="nav-links">

        {navItems.map((item) => (

          <NavLink
            key={item.path}
            to={item.path}
            className={({ isActive }) =>
              isActive
                ? "nav-link active"
                : "nav-link"
            }
          >
            <span>{item.icon}</span>
            <span>{item.label}</span>
          </NavLink>

        ))}

        <button
          className="btn btn-primary btn-sm"
          onClick={logout}
        >
          🚪 Logout
        </button>

      </div>

    </nav>
  );
}