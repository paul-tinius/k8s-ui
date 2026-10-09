function csrfCookie() {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : "";
}

async function ensureCsrfCookie() {
  if (csrfCookie()) return;
  await fetch("/api/csrf", { credentials: "same-origin" });
}

document.getElementById("login-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const errorEl = document.getElementById("login-error");
  errorEl.textContent = "";
  try {
    await ensureCsrfCookie();
    const body = new URLSearchParams({
      username: document.getElementById("login-username").value,
      password: document.getElementById("login-password").value
    });
    const response = await fetch("/login", {
      method: "POST",
      credentials: "same-origin",
      headers: { "X-XSRF-TOKEN": csrfCookie() },
      body
    });
    if (response.ok) {
      window.location.href = "/";
      return;
    }
    let message = "Username or password was rejected.";
    const text = await response.text();
    try {
      const payload = text ? JSON.parse(text) : null;
      if (payload && payload.error) message = payload.error;
    } catch (error) {
      // Keep the default message when the response wasn't JSON.
    }
    errorEl.textContent = message;
  } catch (error) {
    errorEl.textContent = "Could not reach the dashboard.";
  }
});
