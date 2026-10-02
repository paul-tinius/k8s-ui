const state = {
  token: sessionStorage.getItem("k8s-dashboard-token") || "",
  clusters: [],
  clusterId: "",
  namespaces: [],
  namespaceKey: "",
  selectedNamespaces: new Set(),
  tab: "overview",
  shellTab: "",
  resources: [],
  overview: null,
  selection: null,
  detail: null,
  live: true,
  logs: { deployment: "", pod: "", container: "", tail: "100", q: "", lines: [] },
  forwards: [],
  ai: { status: null, presets: [], question: "Why is this unhealthy?", includeLogs: true, answer: "", busy: false },
  notice: "",
  source: null,
  timer: 0
};

const tabs = {
  overview: "Overview",
  pods: "Pod",
  deployments: "Deployment",
  services: "Service",
  configmaps: "ConfigMap",
  nodes: "Node",
  events: "Event",
  logs: "Logs",
  forwards: "Forwards",
  assist: "Assist"
};

function esc(value) {
  return String(value ?? "").replace(/[&<>"']/g, (char) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  }[char]));
}

function age(created) {
  if (!created) return "";
  const then = Date.parse(created);
  if (Number.isNaN(then)) return created;
  const minutes = Math.max(0, Math.round((Date.now() - then) / 60000));
  if (minutes < 60) return minutes + "m";
  const hours = Math.round(minutes / 60);
  if (hours < 48) return hours + "h";
  return Math.round(hours / 24) + "d";
}

function tone(status) {
  const value = String(status || "").toLowerCase();
  if (/crash|error|fail|backoff|notready|warn/.test(value)) return "bad";
  if (/pending|progress/.test(value)) return "warn";
  return "ok";
}

function chip(status) {
  return `<span class="chip ${tone(status)}">${esc(status || "—")}</span>`;
}

function notice(message) {
  state.notice = message || "";
  document.getElementById("notice").textContent = state.notice;
}

async function api(path, options = {}) {
  const headers = { Accept: "application/json" };
  if (options.body) headers["Content-Type"] = "application/json";
  if (state.token) headers.Authorization = "Bearer " + state.token;
  const response = await fetch(path, { method: options.method || "GET", headers, body: options.body });
  if (response.status === 401) {
    const error = new Error("unauthorized");
    error.status = 401;
    throw error;
  }
  const text = await response.text();
  const payload = text ? JSON.parse(text) : null;
  if (!response.ok) throw new Error((payload && payload.error) || ("HTTP " + response.status));
  return payload;
}

function clusterQuery() {
  return "cluster=" + encodeURIComponent(state.clusterId);
}

function namespaceQuery() {
  const names = [...state.selectedNamespaces];
  if (!names.length) return "";
  return "&namespaces=" + encodeURIComponent(names.join(","));
}

function filterQuery() {
  const query = document.getElementById("query").value.trim();
  const label = document.getElementById("label").value.trim();
  const image = document.getElementById("image").value.trim();
  const node = document.getElementById("node").value.trim();
  let extra = "";
  if (query) extra += "&q=" + encodeURIComponent(query);
  if (label) extra += "&label=" + encodeURIComponent(label);
  if (image) extra += "&image=" + encodeURIComponent(image);
  if (node) extra += "&node=" + encodeURIComponent(node);
  return extra;
}

async function loadClusters() {
  state.clusters = await api("/api/clusters");
  const select = document.getElementById("cluster");
  const previous = state.clusterId;
  select.innerHTML = state.clusters.map((cluster) =>
    `<option value="${esc(cluster.id)}">${esc(cluster.name)}${cluster.demo ? " · demo" : ""}</option>`
  ).join("");
  const active = state.clusters.find((cluster) => cluster.active) || state.clusters[0];
  state.clusterId = state.clusters.some((cluster) => cluster.id === previous) ? previous : (active ? active.id : "");
  select.value = state.clusterId;
}

async function loadNamespaces(selectAll) {
  state.namespaces = await api("/api/namespaces?" + clusterQuery());
  const key = state.namespaces.join("|");
  if (selectAll || key !== state.namespaceKey || state.selectedNamespaces.size === 0) {
    state.selectedNamespaces = new Set(state.namespaces);
  }
  state.namespaceKey = key;
  paintNamespaces();
}

function paintNamespaces() {
  const host = document.getElementById("namespace-list");
  host.innerHTML = state.namespaces.map((name) => `
    <label class="ns">
      <input type="checkbox" value="${esc(name)}" ${state.selectedNamespaces.has(name) ? "checked" : ""}>
      ${esc(name)}
    </label>
  `).join("") || `<p class="muted">No namespaces</p>`;
  host.querySelectorAll("input").forEach((input) => {
    input.addEventListener("change", () => {
      if (input.checked) state.selectedNamespaces.add(input.value);
      else state.selectedNamespaces.delete(input.value);
      refresh();
    });
  });
}

function paintShell() {
  const view = document.getElementById("view");
  if (state.tab === "logs") {
    view.innerHTML = `
      <div class="inline">
        <label>Deployment <input id="log-deployment" value="${esc(state.logs.deployment)}" placeholder="storefront"></label>
        <label>Pod <input id="log-pod" value="${esc(state.logs.pod)}" placeholder="optional"></label>
        <label>Container <input id="log-container" value="${esc(state.logs.container)}"></label>
        <label>Tail <select id="log-tail">
          ${["50", "100", "500"].map((value) => `<option ${state.logs.tail === value ? "selected" : ""}>${value}</option>`).join("")}
        </select></label>
        <label>Find <input id="log-q" value="${esc(state.logs.q)}" placeholder="error"></label>
      </div>
      <pre id="log-output"></pre>`;
    ["log-deployment", "log-pod", "log-container", "log-q"].forEach((id) => {
      document.getElementById(id).addEventListener("change", captureLogs);
    });
    document.getElementById("log-tail").addEventListener("change", captureLogs);
  } else if (state.tab === "forwards") {
    view.innerHTML = `
      <form id="forward-form" class="inline">
        <select id="forward-kind"><option>pod</option><option>service</option></select>
        <input id="forward-namespace" placeholder="namespace" required>
        <input id="forward-name" placeholder="name" required>
        <input id="forward-remote" type="number" min="1" max="65535" placeholder="container port" required>
        <input id="forward-local" type="number" min="0" max="65535" placeholder="local port">
        <button class="primary" type="submit">Forward</button>
      </form>
      <div id="forward-list"></div>`;
    document.getElementById("forward-form").addEventListener("submit", openForward);
  } else if (state.tab === "assist") {
    view.innerHTML = `
      <div class="panel">
        <h2>Assist</h2>
        <p id="ai-status" class="muted"></p>
        <label>Question <textarea id="ai-question">${esc(state.ai.question)}</textarea></label>
        <label class="inline"><input id="ai-logs" type="checkbox" ${state.ai.includeLogs ? "checked" : ""}> Include recent logs</label>
        <div class="actions"><button class="primary" id="ai-ask" type="button">Ask</button></div>
        <pre id="ai-answer" class="answer"></pre>
        <h3>Server-side providers</h3>
        <p class="muted">Keys stay on the server. Change DASHBOARD_AI_BASE_URL, DASHBOARD_AI_MODEL, and the API key environment variable, then restart.</p>
        <table class="presets" id="preset-table"></table>
      </div>`;
    document.getElementById("ai-ask").addEventListener("click", ask);
  } else if (state.tab === "overview") {
    view.innerHTML = `<div id="overview-cards" class="cards"></div><div class="split"><div class="panel"><h2>Attention</h2><div id="attention"></div></div><div class="panel"><h2>Pod metrics</h2><div id="metrics"></div></div></div>`;
  } else {
    view.innerHTML = `<table><thead id="grid-head"></thead><tbody id="grid-body"></tbody></table>`;
  }
  state.shellTab = state.tab;
  document.querySelectorAll("#tabs button").forEach((button) => {
    button.classList.toggle("active", button.dataset.tab === state.tab);
  });
}

function captureLogs() {
  state.logs.deployment = document.getElementById("log-deployment").value.trim();
  state.logs.pod = document.getElementById("log-pod").value.trim();
  state.logs.container = document.getElementById("log-container").value.trim();
  state.logs.tail = document.getElementById("log-tail").value;
  state.logs.q = document.getElementById("log-q").value.trim();
  refresh();
}

async function refresh() {
  if (!state.clusterId) {
    document.getElementById("view").innerHTML = `<p>Add a kubeconfig to begin. The demo cluster is included unless it was disabled.</p>`;
    return;
  }
  try {
    if (state.shellTab !== state.tab) paintShell();
    if (state.tab === "overview") await paintOverview();
    else if (state.tab === "logs") await paintLogs();
    else if (state.tab === "forwards") await paintForwards();
    else if (state.tab === "assist") paintAssist();
    else await paintResources();
    notice("");
  } catch (error) {
    if (error.status === 401) return showGate();
    notice(error.message);
  }
}

async function paintOverview() {
  state.overview = await api("/api/overview?" + clusterQuery() + namespaceQuery());
  const overview = state.overview;
  const cards = [
    ["Namespaces", overview.namespaces],
    ["Pods", overview.readyPods + "/" + overview.pods],
    ["Deployments", overview.deployments],
    ["Services", overview.services],
    ["ConfigMaps", overview.configMaps],
    ["Nodes", overview.nodes],
    ["Warnings", overview.warnings],
    ["Version", overview.version || "—"]
  ];
  document.getElementById("overview-cards").innerHTML = cards.map(([label, value]) =>
    `<article class="card"><strong>${esc(value)}</strong><span>${esc(label)}</span></article>`
  ).join("");
  document.getElementById("attention").innerHTML = overview.attention.length
    ? `<table><tbody>${overview.attention.map((item) => `<tr><td>${esc(item.namespace)}</td><td>${esc(item.kind)}</td><td>${esc(item.name)}</td><td>${chip(item.status)}</td><td>${esc(item.summary)}</td></tr>`).join("")}</tbody></table>`
    : `<p class="muted">Nothing needs attention in this view.</p>`;
  document.getElementById("metrics").innerHTML = overview.metrics.length
    ? `<table><tbody>${overview.metrics.map((row) => `<tr><td>${esc(row.namespace)}/${esc(row.name)}</td><td>${esc(row.cpu)}</td><td>${esc(row.memory)}</td></tr>`).join("")}</tbody></table>`
    : `<p class="muted">No metrics yet. metrics-server is optional on a live cluster.</p>`;
  document.getElementById("counts").textContent = overview.clusterName + (overview.demo ? " · demo data" : "");
}

async function paintResources() {
  const kind = tabs[state.tab];
  state.resources = await api("/api/resources?" + clusterQuery() + "&kind=" + encodeURIComponent(kind) + namespaceQuery() + filterQuery());
  const columns = columnsFor(state.tab);
  document.getElementById("grid-head").innerHTML = `<tr>${columns.map((column) => `<th>${esc(column.label)}</th>`).join("")}</tr>`;
  const groups = new Map();
  state.resources.forEach((resource) => {
    const key = resource.namespace || "cluster";
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(resource);
  });
  const multiple = groups.size > 1;
  let html = "";
  groups.forEach((resources, namespace) => {
    if (multiple) {
      html += `<tr class="group"><td colspan="${columns.length}">${esc(namespace)} · ${resources.length}</td></tr>`;
    }
    resources.forEach((resource) => {
      const selected = state.selection && state.selection.kind === resource.kind && state.selection.name === resource.name && state.selection.namespace === resource.namespace;
      html += `<tr class="clickable ${selected ? "selected" : ""}" data-kind="${esc(resource.kind)}" data-namespace="${esc(resource.namespace)}" data-name="${esc(resource.name)}">`;
      columns.forEach((column) => { html += `<td>${column.cell(resource)}</td>`; });
      html += "</tr>";
    });
  });
  document.getElementById("grid-body").innerHTML = html || `<tr><td>No resources match.</td></tr>`;
  document.querySelectorAll("#grid-body tr.clickable").forEach((row) => {
    row.addEventListener("click", () => openDetail(row.dataset.kind, row.dataset.namespace, row.dataset.name));
  });
  document.getElementById("counts").textContent = state.resources.length + " " + state.tab + " in " + state.selectedNamespaces.size + " namespaces";
}

function columnsFor(tab) {
  const name = { label: "Name", cell: (resource) => esc(resource.name) };
  const namespace = { label: "Namespace", cell: (resource) => esc(resource.namespace || "—") };
  const status = { label: "Status", cell: (resource) => chip(resource.status) };
  const ready = { label: "Ready", cell: (resource) => resource.desired ? esc(resource.ready + "/" + resource.desired) : "" };
  const node = { label: "Node", cell: (resource) => esc(resource.node) };
  const images = { label: "Images", cell: (resource) => esc((resource.images || []).join(", ")) };
  const created = { label: "Age", cell: (resource) => esc(age(resource.created)) };
  const summary = { label: "Summary", cell: (resource) => esc(resource.summary) };
  const attr = (label, key) => ({ label, cell: (resource) => esc((resource.attributes || {})[key] || "") });
  if (tab === "pods") return [name, namespace, status, ready, node, images, created];
  if (tab === "deployments") return [name, namespace, status, ready, images, created];
  if (tab === "services") return [name, namespace, attr("Type", "type"), attr("Cluster IP", "clusterIP"), attr("Ports", "ports")];
  if (tab === "configmaps") return [name, namespace, attr("Keys", "keys"), created];
  if (tab === "nodes") return [name, status, attr("Roles", "roles"), attr("CPU", "cpu"), attr("Memory", "memory")];
  return [namespace, name, status, attr("Reason", "reason"), summary];
}

async function paintLogs() {
  const logs = state.logs;
  let path = "/api/logs?" + clusterQuery() + namespaceQuery() + "&tail=" + encodeURIComponent(logs.tail);
  if (logs.deployment) path += "&deployment=" + encodeURIComponent(logs.deployment);
  if (logs.pod) path += "&pod=" + encodeURIComponent(logs.pod);
  if (logs.container) path += "&container=" + encodeURIComponent(logs.container);
  if (logs.q) path += "&q=" + encodeURIComponent(logs.q);
  const page = await api(path);
  const output = document.getElementById("log-output");
  if (!output) return;
  output.textContent = page.lines.length
    ? page.lines.map((line) => line.namespace + "/" + line.pod + "/" + line.container + "  " + line.text).join("\n")
    : "No log lines match.";
  document.getElementById("counts").textContent = page.lines.length + (page.truncated ? " lines, truncated" : " lines");
}

async function paintForwards() {
  state.forwards = await api("/api/port-forwards?" + clusterQuery());
  const host = document.getElementById("forward-list");
  if (!host) return;
  host.innerHTML = state.forwards.length ? state.forwards.map((forward) => `
    <div class="forward-row">
      <div>
        <strong>${esc(forward.namespace)}/${esc(forward.targetKind)}/${esc(forward.targetName)}</strong>
        <div class="muted">${esc(forward.url)} · pod ${esc(forward.podName)} · ${forward.simulated ? "simulated" : "listening"}</div>
        <div class="muted">${esc(forward.note)}</div>
      </div>
      <button type="button" data-id="${esc(forward.id)}">Stop</button>
    </div>
  `).join("") : `<p class="muted">No forwards are open. A service forward uses the first ready pod that matches the selector.</p>`;
  host.querySelectorAll("button").forEach((button) => {
    button.addEventListener("click", async () => {
      await api("/api/port-forwards/" + encodeURIComponent(button.dataset.id), { method: "DELETE" });
      refresh();
    });
  });
}

async function openForward(event) {
  event.preventDefault();
  try {
    await api("/api/port-forwards", {
      method: "POST",
      body: JSON.stringify({
        cluster: state.clusterId,
        namespace: document.getElementById("forward-namespace").value.trim(),
        targetKind: document.getElementById("forward-kind").value,
        targetName: document.getElementById("forward-name").value.trim(),
        remotePort: Number(document.getElementById("forward-remote").value),
        localPort: Number(document.getElementById("forward-local").value || 0)
      })
    });
    refresh();
  } catch (error) {
    notice(error.message);
  }
}

function paintAssist() {
  const status = state.ai.status;
  const statusHost = document.getElementById("ai-status");
  if (statusHost && status) {
    statusHost.textContent = status.configured
      ? status.provider + " · " + status.model + " · " + status.baseUrlHost
      : "No API key on the server. Local checks still run. Default model is " + status.provider + " " + status.model + ".";
  }
  const answer = document.getElementById("ai-answer");
  if (answer) answer.textContent = state.ai.answer;
  const table = document.getElementById("preset-table");
  if (table) {
    table.innerHTML = `<thead><tr><th>Provider</th><th>Base URL</th><th>Model</th><th>Key env</th></tr></thead><tbody>`
      + state.ai.presets.map((preset) => `<tr><td>${esc(preset.label)}${preset.compatible ? "" : " (unavailable)"}</td><td>${esc(preset.baseUrl || preset.note)}</td><td>${esc(preset.model)}</td><td>${esc(preset.apiKeyEnv)}</td></tr>`).join("")
      + `</tbody>`;
  }
  document.getElementById("counts").textContent = state.selection ? state.selection.kind + " " + state.selection.name : "No resource selected";
}

async function ask() {
  state.ai.question = document.getElementById("ai-question").value.trim();
  state.ai.includeLogs = document.getElementById("ai-logs").checked;
  if (!state.ai.question) return;
  state.ai.busy = true;
  state.ai.answer = "Asking…";
  paintAssist();
  try {
    const response = await api("/api/ai/ask", {
      method: "POST",
      body: JSON.stringify({
        cluster: state.clusterId,
        namespace: state.selection ? state.selection.namespace : "",
        kind: state.selection ? state.selection.kind : "",
        name: state.selection ? state.selection.name : "",
        question: state.ai.question,
        includeLogs: state.ai.includeLogs
      })
    });
    state.ai.answer = (response.modelUsed ? response.provider + " · " + response.model + "\n\n" : "") + response.answer;
  } catch (error) {
    state.ai.answer = error.message;
  } finally {
    state.ai.busy = false;
    paintAssist();
  }
}

async function openDetail(kind, namespace, name) {
  state.selection = { kind, namespace, name };
  try {
    state.detail = await api("/api/resources/" + encodeURIComponent(kind) + "/" + encodeURIComponent(namespace || "_") + "/" + encodeURIComponent(name) + "?" + clusterQuery());
    paintDetail();
    if (state.tab !== "overview" && state.tab !== "logs" && state.tab !== "forwards" && state.tab !== "assist") {
      document.querySelectorAll("#grid-body tr.clickable").forEach((row) => {
        row.classList.toggle("selected", row.dataset.name === name && row.dataset.namespace === namespace);
      });
    }
  } catch (error) {
    notice(error.message);
  }
}

function paintDetail() {
  const detail = state.detail;
  const host = document.getElementById("detail");
  if (!detail) {
    host.innerHTML = `<p class="muted">Select a resource to see its manifest and actions.</p>`;
    return;
  }
  const resource = detail.resource;
  const where = resource.namespace ? resource.namespace + "/" + resource.name : resource.name;
  let actions = "";
  if (resource.kind === "Deployment") {
    actions += `<form id="scale-form" class="inline"><input id="replicas" type="number" min="0" max="100" value="${resource.desired}"><button type="submit">Scale</button></form>`;
    actions += `<button type="button" id="restart">Rollout restart</button>`;
  }
  if (resource.kind === "Pod") {
    actions += `<button type="button" id="delete-pod" class="danger">Restart pod</button>`;
    actions += `<button type="button" id="detail-forward">Port forward</button>`;
  }
  if (resource.kind === "Service") actions += `<button type="button" id="detail-forward">Port forward</button>`;
  actions += `<button type="button" id="use-logs">Logs</button>`;
  actions += `<button type="button" id="use-assist">Ask about this</button>`;
  host.innerHTML = `
    <div class="detail-head"><h2>${esc(resource.kind)}</h2>${chip(resource.status)}</div>
    <p>${esc(where)}</p>
    <p class="muted">${esc(resource.summary)}</p>
    <div class="actions">${actions}</div>
    <pre class="yaml" id="yaml"></pre>`;
  document.getElementById("yaml").textContent = detail.yaml;
  const scale = document.getElementById("scale-form");
  if (scale) scale.addEventListener("submit", submitScale);
  const restart = document.getElementById("restart");
  if (restart) restart.addEventListener("click", submitRestart);
  const remove = document.getElementById("delete-pod");
  if (remove) remove.addEventListener("click", submitDeletePod);
  const forward = document.getElementById("detail-forward");
  if (forward) forward.addEventListener("click", () => {
    state.tab = "forwards";
    refresh().then(() => {
      const namespace = document.getElementById("forward-namespace");
      const name = document.getElementById("forward-name");
      const kind = document.getElementById("forward-kind");
      if (namespace) namespace.value = resource.namespace;
      if (name) name.value = resource.name;
      if (kind) kind.value = resource.kind === "Service" ? "service" : "pod";
    });
  });
  document.getElementById("use-logs").addEventListener("click", () => {
    if (resource.kind === "Pod") {
      state.logs.pod = resource.name;
      state.logs.deployment = "";
    } else if (resource.kind === "Deployment") {
      state.logs.deployment = resource.name;
      state.logs.pod = "";
    }
    state.tab = "logs";
    state.shellTab = "";
    refresh();
  });
  document.getElementById("use-assist").addEventListener("click", () => {
    state.tab = "assist";
    state.shellTab = "";
    refresh();
  });
}

async function submitScale(event) {
  event.preventDefault();
  const resource = state.detail.resource;
  await act(() => api("/api/deployments/" + encodeURIComponent(resource.namespace) + "/" + encodeURIComponent(resource.name) + "/scale?" + clusterQuery(), {
    method: "POST",
    body: JSON.stringify({ replicas: Number(document.getElementById("replicas").value) })
  }));
}

async function submitRestart() {
  const resource = state.detail.resource;
  await act(() => api("/api/deployments/" + encodeURIComponent(resource.namespace) + "/" + encodeURIComponent(resource.name) + "/restart?" + clusterQuery(), { method: "POST" }));
}

async function submitDeletePod() {
  const resource = state.detail.resource;
  await act(async () => {
    await api("/api/pods/" + encodeURIComponent(resource.namespace) + "/" + encodeURIComponent(resource.name) + "?" + clusterQuery(), { method: "DELETE" });
    state.detail = null;
    state.selection = null;
    paintDetail();
  });
}

async function act(work) {
  try {
    await work();
    await refresh();
    if (state.selection) await openDetail(state.selection.kind, state.selection.namespace, state.selection.name);
  } catch (error) {
    notice(error.message);
  }
}

function showModal(html) {
  const modal = document.getElementById("modal");
  modal.innerHTML = `<div class="dialog">${html}</div>`;
  modal.classList.remove("hidden");
  modal.addEventListener("click", (event) => {
    if (event.target === modal) modal.classList.add("hidden");
  }, { once: true });
}

function openApply() {
  showModal(`
    <h2>Apply manifest</h2>
    <p class="muted">Sent to the selected cluster. The demo cluster accepts ConfigMap, Deployment, Service, and Pod.</p>
    <textarea id="manifest" placeholder="apiVersion: v1"></textarea>
    <div class="actions"><button class="primary" id="manifest-apply" type="button">Apply</button><button type="button" id="modal-close">Close</button></div>`);
  document.getElementById("modal-close").addEventListener("click", closeModal);
  document.getElementById("manifest-apply").addEventListener("click", async () => {
    try {
      await api("/api/apply?" + clusterQuery(), { method: "POST", body: JSON.stringify({ yaml: document.getElementById("manifest").value }) });
      closeModal();
      await loadNamespaces(false);
      refresh();
    } catch (error) {
      notice(error.message);
    }
  });
}

function openClusters() {
  const rows = state.clusters.map((cluster) => `
    <div class="cluster-row">
      <div><strong>${esc(cluster.name)}</strong><div class="muted">${esc(cluster.server)} · ${esc(cluster.source)}${cluster.active ? " · active" : ""}</div></div>
      <div class="inline">
        <button type="button" data-activate="${esc(cluster.id)}">Use</button>
        ${cluster.demo ? "" : `<button type="button" class="danger" data-delete="${esc(cluster.id)}">Remove</button>`}
      </div>
    </div>`).join("");
  showModal(`
    <h2>Clusters</h2>
    ${rows || "<p>No clusters.</p>"}
    <h3>Add kubeconfig</h3>
    <p class="muted">Stored under the server data directory with user-only permissions. Every context becomes a cluster. The file is not sent to a model.</p>
    <input id="cluster-name" placeholder="Display name for a single context">
    <textarea id="cluster-kubeconfig" placeholder="apiVersion: v1&#10;kind: Config"></textarea>
    <div class="actions"><button class="primary" id="cluster-add" type="button">Add</button><button type="button" id="modal-close">Close</button></div>`);
  document.getElementById("modal-close").addEventListener("click", closeModal);
  document.querySelectorAll("[data-activate]").forEach((button) => button.addEventListener("click", async () => {
    await api("/api/clusters/" + encodeURIComponent(button.dataset.activate) + "/activate", { method: "POST" });
    state.clusterId = button.dataset.activate;
    document.getElementById("cluster").value = state.clusterId;
    closeModal();
    await loadNamespaces(true);
    state.detail = null;
    paintDetail();
    refresh();
  }));
  document.querySelectorAll("[data-delete]").forEach((button) => button.addEventListener("click", async () => {
    await api("/api/clusters/" + encodeURIComponent(button.dataset.delete), { method: "DELETE" });
    closeModal();
    await loadClusters();
    await loadNamespaces(true);
    refresh();
  }));
  document.getElementById("cluster-add").addEventListener("click", async () => {
    try {
      await api("/api/clusters", {
        method: "POST",
        body: JSON.stringify({
          name: document.getElementById("cluster-name").value.trim(),
          kubeconfig: document.getElementById("cluster-kubeconfig").value
        })
      });
      closeModal();
      await loadClusters();
      await loadNamespaces(true);
      refresh();
    } catch (error) {
      notice(error.message);
    }
  });
}

function closeModal() {
  document.getElementById("modal").classList.add("hidden");
}

function connectLive() {
  if (state.source) state.source.close();
  const token = state.token ? "?access_token=" + encodeURIComponent(state.token) : "";
  const source = new EventSource("/api/live" + token);
  const kick = () => {
    if (!state.live || document.hidden) return;
    window.clearTimeout(state.timer);
    state.timer = window.setTimeout(refresh, 200);
  };
  source.addEventListener("tick", kick);
  source.addEventListener("changed", kick);
  state.source = source;
}

function showGate() {
  document.getElementById("gate").classList.remove("hidden");
}

function hideGate() {
  document.getElementById("gate").classList.add("hidden");
}

async function boot() {
  hideGate();
  state.ai.status = await api("/api/ai/status");
  state.ai.presets = await api("/api/ai/presets");
  await loadClusters();
  if (state.clusterId) await loadNamespaces(true);
  await refresh();
  connectLive();
}

document.getElementById("tabs").addEventListener("click", (event) => {
  const button = event.target.closest("button");
  if (!button) return;
  state.tab = button.dataset.tab;
  state.shellTab = "";
  refresh();
});

document.getElementById("cluster").addEventListener("change", async () => {
  state.clusterId = document.getElementById("cluster").value;
  state.detail = null;
  paintDetail();
  try {
    await api("/api/clusters/" + encodeURIComponent(state.clusterId) + "/activate", { method: "POST" });
    await loadNamespaces(true);
    refresh();
  } catch (error) {
    notice(error.message);
  }
});

["query", "label", "image", "node"].forEach((id) => {
  document.getElementById(id).addEventListener("input", () => {
    window.clearTimeout(state.timer);
    state.timer = window.setTimeout(refresh, 250);
  });
});

document.getElementById("live").addEventListener("change", (event) => {
  state.live = event.target.checked;
});

document.getElementById("ns-all").addEventListener("click", () => {
  const allSelected = state.selectedNamespaces.size === state.namespaces.length;
  state.selectedNamespaces = allSelected ? new Set() : new Set(state.namespaces);
  paintNamespaces();
  refresh();
});

document.getElementById("apply-open").addEventListener("click", openApply);
document.getElementById("clusters-open").addEventListener("click", openClusters);
document.getElementById("gate-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  state.token = document.getElementById("gate-token").value;
  sessionStorage.setItem("k8s-dashboard-token", state.token);
  try {
    await boot();
  } catch (error) {
    document.getElementById("gate-error").textContent = error.status === 401 ? "Token was rejected." : error.message;
  }
});

boot().catch((error) => {
  if (error.status === 401) showGate();
  else notice(error.message);
});
