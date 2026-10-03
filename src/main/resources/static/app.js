const AI_KEYS = {
  name: "k8s-dashboard-ai-provider",
  baseUrl: "k8s-dashboard-ai-base-url",
  model: "k8s-dashboard-ai-model",
  apiKey: "k8s-dashboard-ai-key",
  org: "k8s-dashboard-ai-org"
};

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
  management: { namespaceName: "", clusterName: "", kubeconfig: "", kind: "deployments", editing: null, yaml: "" },
  ai: Object.assign({
    question: "Why is this unhealthy?",
    includeLogs: true,
    answer: "",
    busy: false
  }, loadAiSettings()),
  notice: "",
  source: null,
  timer: 0
};

const MANAGE_KINDS = [
  ["deployments", "Deployments"],
  ["pods", "Pods"],
  ["services", "Services"],
  ["configmaps", "ConfigMaps"]
];

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

function storageGet(store, key) {
  try {
    return store.getItem(key);
  } catch (error) {
    return null;
  }
}

function storageSet(store, key, value) {
  try {
    store.setItem(key, value);
  } catch (error) {
    /* Private browsing can reject localStorage. The fields still work for this page. */
  }
}

const THEME_KEY = "k8s-dashboard-theme";

function storedTheme() {
  const saved = storageGet(localStorage, THEME_KEY);
  return saved === "dark" || saved === "light" || saved === "system" ? saved : "system";
}

function applyTheme(theme) {
  const choice = theme === "dark" || theme === "light" || theme === "system" ? theme : "system";
  document.documentElement.setAttribute("data-theme", choice);
  const select = document.getElementById("theme");
  if (select && select.value !== choice) select.value = choice;
  storageSet(localStorage, THEME_KEY, choice);
}

function loadAiSettings() {
  if (storageGet(localStorage, AI_KEYS.name) === null
      && storageGet(localStorage, AI_KEYS.baseUrl) === null
      && storageGet(localStorage, AI_KEYS.model) === null
      && storageGet(localStorage, AI_KEYS.apiKey) === null) {
    migrateAiSettings();
  }
  return {
    providerName: storageGet(localStorage, AI_KEYS.name) || "",
    baseUrl: storageGet(localStorage, AI_KEYS.baseUrl) || "",
    model: storageGet(localStorage, AI_KEYS.model) || "",
    apiKey: storageGet(localStorage, AI_KEYS.apiKey) || "",
    orgId: storageGet(localStorage, AI_KEYS.org) || ""
  };
}

// Older builds stored a preset id in sessionStorage. Copy it once into localStorage.
function migrateAiSettings() {
  const known = {
    xai: ["Grok", "https://api.x.ai/v1", "grok-4.7"],
    openai: ["OpenAI", "https://api.openai.com/v1", "gpt-4.1"],
    "openrouter-claude": ["Claude", "https://openrouter.ai/api/v1", "anthropic/claude-sonnet-4"],
    "openrouter-gemini": ["Gemini", "https://openrouter.ai/api/v1", "google/gemini-2.5-pro"],
    deepseek: ["DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"],
    qwen: ["Qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"],
    ollama: ["Ollama", "http://127.0.0.1:11434/v1", "llama3.1"],
    lmstudio: ["LM Studio", "http://127.0.0.1:1234/v1", "local-model"],
    devin: ["Devin", "https://api.devin.ai/v3", "lite"]
  };
  const preset = storageGet(sessionStorage, "k8s-dashboard-ai-preset");
  const match = preset ? known[preset] : null;
  storageSet(localStorage, AI_KEYS.name, match ? match[0] : "");
  storageSet(localStorage, AI_KEYS.baseUrl, match ? match[1] : "");
  storageSet(localStorage, AI_KEYS.model, match ? match[2] : "");
  storageSet(localStorage, AI_KEYS.apiKey, match ? (storageGet(sessionStorage, "k8s-dashboard-ai-key") || "") : "");
  storageSet(localStorage, AI_KEYS.org, preset === "devin" ? (storageGet(sessionStorage, "k8s-dashboard-ai-org") || "") : "");
}

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
  if (/pending|progress|terminat/.test(value)) return "warn";
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
  if (!state.clusterId) {
    state.namespaces = [];
    state.selectedNamespaces = new Set();
    state.namespaceKey = "";
    paintNamespaces();
    return;
  }
  state.namespaces = await api("/api/namespaces?" + clusterQuery());
  const key = state.namespaces.join("|");
  if (selectAll || state.selectedNamespaces.size === 0) {
    state.selectedNamespaces = new Set(state.namespaces);
  } else if (key !== state.namespaceKey) {
    const known = new Set(state.namespaceKey.split("|").filter(Boolean));
    const next = new Set([...state.selectedNamespaces].filter((name) => state.namespaces.includes(name)));
    state.namespaces.forEach((name) => {
      if (!known.has(name)) next.add(name);
    });
    state.selectedNamespaces = next;
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
      <div class="panel assist">
        <h2>Assist</h2>
        <p id="ai-status" class="muted"></p>
        <label>Provider name <input id="ai-provider" autocomplete="off" maxlength="80" placeholder="Grok" value="${esc(state.ai.providerName)}"></label>
        <label>Base URL <input id="ai-base-url" autocomplete="off" maxlength="500" placeholder="https://api.x.ai/v1" value="${esc(state.ai.baseUrl)}"></label>
        <label>Model <input id="ai-model" autocomplete="off" maxlength="120" placeholder="grok-4.7" value="${esc(state.ai.model)}"></label>
        <label>API key
          <div class="secret">
            <input id="ai-key" type="password" autocomplete="off" maxlength="512" value="${esc(state.ai.apiKey)}">
            <button type="button" id="ai-key-toggle" aria-label="Show API key" aria-pressed="false">
              <svg class="eye-on" viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12z"/><circle cx="12" cy="12" r="2.5"/></svg>
              <svg class="eye-off" viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M3 3l18 18"/><path d="M10.5 6.2A10.6 10.6 0 0 1 12 6c6.5 0 10 6 10 6a18 18 0 0 1-3.1 3.7"/><path d="M6.2 6.8C3.9 8.4 2 12 2 12s3.5 6 10 6c1.1 0 2.2-.2 3.2-.6"/><path d="M9.9 9.9a2.5 2.5 0 0 0 3.6 3.6"/></svg>
            </button>
          </div>
        </label>
        <label id="ai-org-field" class="${usingDevin() ? "" : "hidden"}">Devin organization id <input id="ai-org" autocomplete="off" placeholder="org-..." value="${esc(state.ai.orgId)}">
          <span class="muted">From Settings, then Devin API.</span>
        </label>
        <p class="muted">The provider name, base URL, model, and API key stay in this browser and are sent only with Ask. Leave them blank for a local check.</p>
        <label>Question <textarea id="ai-question">${esc(state.ai.question)}</textarea></label>
        <label class="inline"><input id="ai-logs" type="checkbox" ${state.ai.includeLogs ? "checked" : ""}> Include recent logs</label>
        <div class="actions">
          <button class="primary" id="ai-ask" type="button">Ask</button>
          <button type="button" id="ai-copy">Copy answer</button>
        </div>
        <textarea id="ai-answer" class="answer" readonly aria-label="Assist answer"></textarea>
      </div>`;
    document.getElementById("ai-provider").addEventListener("input", saveAiChoice);
    document.getElementById("ai-base-url").addEventListener("input", saveAiChoice);
    document.getElementById("ai-model").addEventListener("input", saveAiChoice);
    document.getElementById("ai-key").addEventListener("input", saveAiChoice);
    document.getElementById("ai-key-toggle").addEventListener("click", toggleApiKey);
    document.getElementById("ai-org").addEventListener("input", saveAiChoice);
    document.getElementById("ai-ask").addEventListener("click", ask);
    document.getElementById("ai-copy").addEventListener("click", copyAnswer);
    toggleAiFields();
  } else if (state.tab === "management") {
    view.innerHTML = `
      <div class="stack">
        <div class="panel manage">
          <h2>Resources</h2>
          <p class="muted">Edit applies the manifest. Delete removes that resource. A pod owned by a deployment is replaced.</p>
          <label class="inline">Kind
            <select id="manage-kind">
              ${MANAGE_KINDS.map(([value, label]) => `<option value="${value}"${state.management.kind === value ? " selected" : ""}>${label}</option>`).join("")}
            </select>
          </label>
          <div id="manage-resources"></div>
          <form id="manage-edit" class="${state.management.editing ? "" : "hidden"}">
            <h3 id="manage-edit-title"></h3>
            <textarea id="manage-yaml">${esc(state.management.yaml)}</textarea>
            <div class="actions"><button class="primary" type="submit">Save</button><button type="button" id="manage-edit-cancel">Cancel</button></div>
          </form>
        </div>
        <div class="panel manage">
          <h2>Namespaces</h2>
          <p id="manage-cluster" class="muted"></p>
          <form id="namespace-form" class="inline">
            <input id="namespace-name" placeholder="billing" maxlength="63" autocomplete="off" value="${esc(state.management.namespaceName)}" required>
            <button class="primary" type="submit">Create</button>
          </form>
          <p class="muted">Deleting a namespace removes everything in it. System namespaces stay.</p>
          <div id="namespace-rows"></div>
        </div>
        <div class="panel manage">
          <h2>Clusters</h2>
          <div id="cluster-rows"></div>
          <h3>Add kubeconfig</h3>
          <p class="muted">Paste the YAML or choose a file. Stored under the server data directory with user-only permissions. Every context becomes a cluster. The file is not sent to a model.</p>
          <label class="cluster-file">Kubeconfig file <input id="cluster-file" type="file" accept=".yml,.yaml,.conf,.kubeconfig,text/yaml,application/yaml,text/plain"></label>
          <input id="cluster-name" placeholder="Display name for a single context" value="${esc(state.management.clusterName)}">
          <textarea id="cluster-kubeconfig" placeholder="apiVersion: v1&#10;kind: Config">${esc(state.management.kubeconfig)}</textarea>
          <div class="actions"><button class="primary" id="cluster-add" type="button">Add</button></div>
        </div>
      </div>`;
    document.getElementById("manage-kind").addEventListener("change", (event) => {
      state.management.kind = event.target.value;
      closeManagedEdit();
      refresh();
    });
    document.getElementById("manage-yaml").addEventListener("input", (event) => {
      state.management.yaml = event.target.value;
    });
    document.getElementById("manage-edit").addEventListener("submit", saveManagedResource);
    document.getElementById("manage-edit-cancel").addEventListener("click", closeManagedEdit);
    if (state.management.editing) setManagedEditTitle(state.management.editing);
    document.getElementById("namespace-name").addEventListener("input", (event) => {
      state.management.namespaceName = event.target.value;
    });
    document.getElementById("namespace-form").addEventListener("submit", createNamespace);
    document.getElementById("cluster-name").addEventListener("input", (event) => {
      state.management.clusterName = event.target.value;
    });
    document.getElementById("cluster-kubeconfig").addEventListener("input", (event) => {
      state.management.kubeconfig = event.target.value;
    });
    document.getElementById("cluster-file").addEventListener("change", (event) => {
      const file = event.target.files && event.target.files[0];
      loadKubeconfigFile(file);
    });
    document.getElementById("cluster-add").addEventListener("click", addCluster);
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
  if (!state.clusterId && state.tab !== "management") {
    document.getElementById("view").innerHTML = `<p>Add a kubeconfig from Management to begin. The demo cluster is included unless it was disabled.</p>`;
    return;
  }
  try {
    if (state.shellTab !== state.tab) paintShell();
    if (state.tab === "overview") await paintOverview();
    else if (state.tab === "logs") await paintLogs();
    else if (state.tab === "forwards") await paintForwards();
    else if (state.tab === "assist") paintAssist();
    else if (state.tab === "management") await paintManagement();
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

function saveAiChoice() {
  const provider = document.getElementById("ai-provider");
  const baseUrl = document.getElementById("ai-base-url");
  const model = document.getElementById("ai-model");
  const key = document.getElementById("ai-key");
  const org = document.getElementById("ai-org");
  if (!provider || !baseUrl || !model || !key || !org) return;
  state.ai.providerName = provider.value.trim();
  state.ai.baseUrl = baseUrl.value.trim();
  state.ai.model = model.value.trim();
  state.ai.apiKey = key.value;
  state.ai.orgId = org.value.trim();
  storageSet(localStorage, AI_KEYS.name, state.ai.providerName);
  storageSet(localStorage, AI_KEYS.baseUrl, state.ai.baseUrl);
  storageSet(localStorage, AI_KEYS.model, state.ai.model);
  storageSet(localStorage, AI_KEYS.apiKey, state.ai.apiKey);
  storageSet(localStorage, AI_KEYS.org, state.ai.orgId);
  toggleAiFields();
  paintAssist();
}

function usingDevin() {
  if (state.ai.providerName.trim().toLowerCase() === "devin") return true;
  try {
    return new URL(state.ai.baseUrl).hostname.toLowerCase() === "api.devin.ai";
  } catch (error) {
    return false;
  }
}

function toggleApiKey() {
  const input = document.getElementById("ai-key");
  const button = document.getElementById("ai-key-toggle");
  if (!input || !button) return;
  const show = input.type === "password";
  input.type = show ? "text" : "password";
  button.setAttribute("aria-pressed", show ? "true" : "false");
  button.setAttribute("aria-label", show ? "Hide API key" : "Show API key");
}

function toggleAiFields() {
  const devin = usingDevin();
  const orgField = document.getElementById("ai-org-field");
  const org = document.getElementById("ai-org");
  if (orgField) orgField.classList.toggle("hidden", !devin);
  if (org) org.required = devin;
}

function paintAssist() {
  const statusHost = document.getElementById("ai-status");
  if (statusHost) {
    if (!state.ai.providerName && !state.ai.baseUrl && !state.ai.apiKey) {
      statusHost.textContent = "Assist is off. Enter a provider name, base URL, and API key. They stay in this browser.";
    } else if (!state.ai.baseUrl) {
      statusHost.textContent = "Base URL is required. The provider name, base URL, and API key stay in this browser.";
    } else {
      let host = "";
      try {
        host = new URL(state.ai.baseUrl).host;
      } catch (error) {
        host = "";
      }
      const name = state.ai.providerName || "Custom";
      const model = state.ai.model || "server model";
      statusHost.textContent = name + " · " + model + (host ? " · " + host : "") + ". Saved in this browser.";
    }
  }
  showAnswer();
  const copy = document.getElementById("ai-copy");
  if (copy) copy.disabled = state.ai.busy || !state.ai.answer;
  document.getElementById("counts").textContent = state.selection ? state.selection.kind + " " + state.selection.name : "No resource selected";
}

function showAnswer() {
  const answer = document.getElementById("ai-answer");
  if (!answer || answer.value === state.ai.answer) return;
  answer.value = state.ai.answer;
  answer.style.height = "auto";
  answer.style.height = Math.max(answer.scrollHeight, 144) + "px";
}

async function copyAnswer() {
  const text = state.ai.answer;
  if (!text || state.ai.busy) return;
  const box = document.getElementById("ai-answer");
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(text);
    } else {
      if (!box) return;
      box.focus();
      box.select();
      document.execCommand("copy");
    }
    const button = document.getElementById("ai-copy");
    if (!button) return;
    button.textContent = "Copied";
    window.setTimeout(() => {
      if (button.textContent === "Copied") button.textContent = "Copy answer";
    }, 1200);
  } catch (error) {
    if (box) {
      box.focus();
      box.select();
    }
    notice("Select the answer and copy it");
  }
}

async function ask() {
  saveAiChoice();
  state.ai.question = document.getElementById("ai-question").value.trim();
  state.ai.includeLogs = document.getElementById("ai-logs").checked;
  if (!state.ai.question) return;
  if (usingDevin() && !state.ai.orgId) {
    state.ai.answer = "Devin organization id is required";
    paintAssist();
    return;
  }
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
        includeLogs: state.ai.includeLogs,
        provider: state.ai.providerName,
        baseUrl: state.ai.baseUrl,
        model: state.ai.model,
        apiKey: state.ai.apiKey,
        orgId: usingDevin() ? state.ai.orgId : ""
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
    if (panelCollapsed("detail")) setPanelCollapsed("detail", false, true);
    paintDetail();
    if (state.tab !== "overview" && state.tab !== "logs" && state.tab !== "forwards" && state.tab !== "assist" && state.tab !== "management") {
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
  const host = document.getElementById("detail-body");
  if (!host) return;
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

function managedWhere(resource) {
  return resource.namespace ? resource.namespace + "/" + resource.name : resource.name;
}

function setManagedEditTitle(editing) {
  const title = document.getElementById("manage-edit-title");
  if (!title || !editing) return;
  const where = editing.namespace ? editing.namespace + "/" + editing.name : editing.name;
  title.textContent = "Edit " + editing.kind + " " + where;
}

function closeManagedEdit() {
  state.management.editing = null;
  state.management.yaml = "";
  const form = document.getElementById("manage-edit");
  const box = document.getElementById("manage-yaml");
  if (form) form.classList.add("hidden");
  if (box) box.value = "";
}

function closeManagedEditOutside(clusterId) {
  if (state.management.editing && state.management.editing.clusterId !== clusterId) closeManagedEdit();
}

async function editManagedResource(kind, namespace, name) {
  const clusterId = state.clusterId;
  try {
    const detail = await api("/api/resources/" + encodeURIComponent(kind) + "/" + encodeURIComponent(namespace || "_") + "/" + encodeURIComponent(name) + "?cluster=" + encodeURIComponent(clusterId));
    if (clusterId !== state.clusterId) return;
    const resource = detail.resource;
    state.management.editing = { clusterId, kind: resource.kind, namespace: resource.namespace, name: resource.name };
    state.management.yaml = detail.yaml || "";
    const form = document.getElementById("manage-edit");
    const box = document.getElementById("manage-yaml");
    if (form) form.classList.remove("hidden");
    if (box) box.value = state.management.yaml;
    setManagedEditTitle(state.management.editing);
  } catch (error) {
    notice(error.message);
  }
}

async function saveManagedResource(event) {
  event.preventDefault();
  const editing = state.management.editing;
  if (!editing) return;
  const box = document.getElementById("manage-yaml");
  try {
    await api("/api/apply?cluster=" + encodeURIComponent(editing.clusterId), {
      method: "POST",
      body: JSON.stringify({ yaml: box ? box.value : "" })
    });
    closeManagedEdit();
    await loadNamespaces(false);
    if (editing.clusterId === state.clusterId && state.detail && state.detail.resource.kind === editing.kind
        && state.detail.resource.name === editing.name
        && state.detail.resource.namespace === editing.namespace) {
      await openDetail(editing.kind, editing.namespace || "_", editing.name);
    }
    refresh();
  } catch (error) {
    notice(error.message);
  }
}

async function deleteManagedResource(kind, namespace, name) {
  const where = namespace ? namespace + "/" + name : name;
  const ownedPod = kind === "Pod" ? " If a deployment owns it, a new pod is started." : "";
  if (!window.confirm("Delete " + kind + " " + where + "?" + ownedPod)) return;
  try {
    await api("/api/resources/" + encodeURIComponent(kind) + "/" + encodeURIComponent(namespace || "_") + "/" + encodeURIComponent(name) + "?" + clusterQuery(), { method: "DELETE" });
    if (state.management.editing
        && state.management.editing.kind === kind
        && state.management.editing.namespace === namespace
        && state.management.editing.name === name) {
      closeManagedEdit();
    }
    if (state.selection && state.selection.kind === kind && state.selection.name === name && state.selection.namespace === namespace) {
      state.selection = null;
      state.detail = null;
      paintDetail();
    }
    await loadNamespaces(false);
    refresh();
  } catch (error) {
    notice(error.message);
  }
}

async function paintManagement() {
  const clusterHost = document.getElementById("manage-cluster");
  const namespaceHost = document.getElementById("namespace-rows");
  const clusterRows = document.getElementById("cluster-rows");
  const resourceHost = document.getElementById("manage-resources");
  if (!namespaceHost || !clusterRows || !resourceHost) return;
  const kindSelect = document.getElementById("manage-kind");
  if (kindSelect) kindSelect.disabled = !state.clusterId;
  let resources = [];
  if (state.clusterId) {
    resources = await api("/api/resources?kind=" + encodeURIComponent(state.management.kind || "deployments") + "&" + clusterQuery() + namespaceQuery());
  }
  resourceHost.innerHTML = resources.length ? resources.map((resource) => `
    <div class="cluster-row">
      <div><strong>${esc(managedWhere(resource))}</strong><div class="muted">${chip(resource.status)} ${esc(resource.summary)}</div></div>
      <div class="inline">
        <button type="button" data-edit-kind="${esc(resource.kind)}" data-edit-namespace="${esc(resource.namespace)}" data-edit-name="${esc(resource.name)}">Edit</button>
        <button type="button" class="danger" data-remove-kind="${esc(resource.kind)}" data-remove-namespace="${esc(resource.namespace)}" data-remove-name="${esc(resource.name)}">Delete</button>
      </div>
    </div>`).join("") : `<p class="muted">${state.clusterId ? "No resources in the selected namespaces." : "Add a cluster before editing resources."}</p>`;
  resourceHost.querySelectorAll("[data-edit-kind]").forEach((button) => {
    button.addEventListener("click", () => editManagedResource(button.dataset.editKind, button.dataset.editNamespace, button.dataset.editName));
  });
  resourceHost.querySelectorAll("[data-remove-kind]").forEach((button) => {
    button.addEventListener("click", () => deleteManagedResource(button.dataset.removeKind, button.dataset.removeNamespace, button.dataset.removeName));
  });
  const selected = state.clusters.find((cluster) => cluster.id === state.clusterId);
  if (clusterHost) {
    clusterHost.textContent = selected
      ? selected.name + " · " + selected.server
      : "Add a cluster before creating namespaces.";
  }
  let details = [];
  if (state.clusterId) {
    details = await api("/api/namespace-details?" + clusterQuery());
  }
  namespaceHost.innerHTML = details.length ? details.map((item) => `
    <div class="cluster-row">
      <div><strong>${esc(item.name)}</strong><div class="muted">${chip(item.status)}</div></div>
      ${item.deletable && item.status !== "Terminating"
        ? `<button type="button" class="danger" data-delete-namespace="${esc(item.name)}">Delete</button>`
        : `<span class="muted">${item.deletable ? "Removing" : "System"}</span>`}
    </div>`).join("") : `<p class="muted">${state.clusterId ? "No namespaces." : "No cluster selected."}</p>`;
  namespaceHost.querySelectorAll("[data-delete-namespace]").forEach((button) => {
    button.addEventListener("click", () => deleteNamespace(button.dataset.deleteNamespace));
  });
  clusterRows.innerHTML = state.clusters.length ? state.clusters.map((cluster) => `
    <div class="cluster-row">
      <div><strong>${esc(cluster.name)}</strong><div class="muted">${esc(cluster.server)} · ${esc(cluster.source)}${cluster.id === state.clusterId ? " · selected" : ""}</div></div>
      <div class="inline">
        <button type="button" data-activate="${esc(cluster.id)}">Use</button>
        ${cluster.demo ? "" : `<button type="button" class="danger" data-delete="${esc(cluster.id)}">Remove</button>`}
      </div>
    </div>`).join("") : `<p class="muted">No clusters.</p>`;
  clusterRows.querySelectorAll("[data-activate]").forEach((button) => {
    button.addEventListener("click", () => activateCluster(button.dataset.activate));
  });
  clusterRows.querySelectorAll("[data-delete]").forEach((button) => {
    button.addEventListener("click", () => removeCluster(button.dataset.delete));
  });
  const form = document.getElementById("namespace-form");
  if (form) {
    form.querySelector("button").disabled = !state.clusterId;
  }
  const kindLabel = (MANAGE_KINDS.find(([value]) => value === state.management.kind) || MANAGE_KINDS[0])[1];
  document.getElementById("counts").textContent = selected
    ? selected.name + " · " + resources.length + " " + kindLabel.toLowerCase()
    : "No cluster selected";
}

async function createNamespace(event) {
  event.preventDefault();
  const input = document.getElementById("namespace-name");
  const name = input ? input.value.trim() : "";
  if (!name || !state.clusterId) return;
  try {
    await api("/api/namespaces?" + clusterQuery(), {
      method: "POST",
      body: JSON.stringify({ name })
    });
    state.management.namespaceName = "";
    if (input) input.value = "";
    await loadNamespaces(false);
    refresh();
  } catch (error) {
    notice(error.message);
  }
}

async function deleteNamespace(name) {
  if (!name || !state.clusterId) return;
  if (!window.confirm("Delete namespace " + name + " and everything in it?")) return;
  try {
    await api("/api/namespaces/" + encodeURIComponent(name) + "?" + clusterQuery(), { method: "DELETE" });
    if (state.selection && state.selection.namespace === name) {
      state.selection = null;
      state.detail = null;
      paintDetail();
    }
    await loadNamespaces(false);
    refresh();
  } catch (error) {
    notice(error.message);
  }
}

async function activateCluster(id) {
  try {
    await api("/api/clusters/" + encodeURIComponent(id) + "/activate", { method: "POST" });
    state.clusterId = id;
    closeManagedEditOutside(state.clusterId);
    document.getElementById("cluster").value = state.clusterId;
    await loadNamespaces(true);
    state.detail = null;
    paintDetail();
    refresh();
  } catch (error) {
    notice(error.message);
  }
}

async function removeCluster(id) {
  try {
    await api("/api/clusters/" + encodeURIComponent(id), { method: "DELETE" });
    if (state.management.editing && state.management.editing.clusterId === id) closeManagedEdit();
    if (state.clusterId === id) {
      state.detail = null;
      paintDetail();
    }
    await loadClusters();
    closeManagedEditOutside(state.clusterId);
    await loadNamespaces(true);
    refresh();
  } catch (error) {
    notice(error.message);
  }
}

async function addCluster() {
  const nameInput = document.getElementById("cluster-name");
  const kubeconfig = document.getElementById("cluster-kubeconfig");
  try {
    await api("/api/clusters", {
      method: "POST",
      body: JSON.stringify({
        name: nameInput ? nameInput.value.trim() : "",
        kubeconfig: kubeconfig ? kubeconfig.value : ""
      })
    });
    state.management.clusterName = "";
    state.management.kubeconfig = "";
    if (nameInput) nameInput.value = "";
    if (kubeconfig) kubeconfig.value = "";
    await loadClusters();
    await loadNamespaces(true);
    refresh();
  } catch (error) {
    notice(error.message);
  }
}

async function loadKubeconfigFile(file) {
  if (!file) return;
  let text = "";
  try {
    text = await file.text();
  } catch (error) {
    notice("Could not read the kubeconfig file");
    return;
  }
  if (!text.trim()) {
    notice("That kubeconfig file is empty");
    return;
  }
  if (text.length > 1000000) {
    notice("That kubeconfig is larger than 1 MB");
    return;
  }
  const box = document.getElementById("cluster-kubeconfig");
  const name = document.getElementById("cluster-name");
  if (box) box.value = text;
  state.management.kubeconfig = text;
  const stem = file.name.replace(/\.[^.]+$/, "");
  if (name && !name.value.trim() && stem && stem.toLowerCase() !== "config") {
    name.value = stem;
    state.management.clusterName = stem;
  }
  notice("");
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
  await loadClusters();
  if (state.clusterId) await loadNamespaces(true);
  await refresh();
  connectLive();
}

const PANEL_RAIL = 44;
const PANEL_COLLAPSE = {
  namespaces: {
    key: "k8s-dashboard-namespaces-collapsed",
    className: "namespaces-collapsed",
    button: "namespace-collapse",
    splitter: "split-namespaces",
    show: "Show namespaces",
    hide: "Hide namespaces"
  },
  detail: {
    key: "k8s-dashboard-detail-collapsed",
    className: "detail-collapsed",
    button: "detail-collapse",
    splitter: "split-detail",
    show: "Show manifest",
    hide: "Hide manifest"
  }
};

function panelCollapsed(side) {
  const workspace = document.getElementById("workspace");
  return Boolean(workspace && workspace.classList.contains(PANEL_COLLAPSE[side].className));
}

function setPanelCollapsed(side, collapsed, persist) {
  const spec = PANEL_COLLAPSE[side];
  const workspace = document.getElementById("workspace");
  const button = document.getElementById(spec.button);
  const splitter = document.getElementById(spec.splitter);
  if (workspace) workspace.classList.toggle(spec.className, collapsed);
  if (button) {
    button.setAttribute("aria-expanded", collapsed ? "false" : "true");
    button.setAttribute("aria-label", collapsed ? spec.show : spec.hide);
  }
  if (splitter) {
    splitter.hidden = collapsed && !stackedLayout();
    splitter.tabIndex = collapsed ? -1 : 0;
  }
  if (persist) {
    try {
      sessionStorage.setItem(spec.key, collapsed ? "1" : "0");
    } catch (error) {
      /* Private browsing can reject sessionStorage. The panel still toggles. */
    }
  }
  if (workspace && !stackedLayout()) {
    const other = side === "namespaces" ? "detail" : "namespaces";
    applyPanelWidth(workspace, other, panels[other]);
  }
}

const PANEL_LIMITS = {
  namespaces: { min: 160, max: 520, fallback: 230, key: "k8s-dashboard-namespaces-width" },
  detail: { min: 240, max: 760, fallback: 360, key: "k8s-dashboard-detail-width" }
};
const MAIN_MIN = 280;
const panels = {
  namespaces: PANEL_LIMITS.namespaces.fallback,
  detail: PANEL_LIMITS.detail.fallback
};

function installSplitters() {
  const workspace = document.getElementById("workspace");
  if (!workspace) return;
  restorePanelWidths(workspace);
  setPanelCollapsed("namespaces", sessionStorage.getItem(PANEL_COLLAPSE.namespaces.key) === "1", false);
  setPanelCollapsed("detail", sessionStorage.getItem(PANEL_COLLAPSE.detail.key) === "1", false);
  bindSplitter(document.getElementById("split-namespaces"), "namespaces");
  bindSplitter(document.getElementById("split-detail"), "detail");
  window.addEventListener("resize", () => {
    if (stackedLayout()) return;
    applyPanelWidth(workspace, "namespaces", panels.namespaces);
    applyPanelWidth(workspace, "detail", panels.detail);
    rememberPanelWidths();
  });
}

function bindSplitter(splitter, side) {
  if (!splitter) return;
  splitter.addEventListener("pointerdown", (event) => {
    if (event.button !== 0 || stackedLayout()) return;
    event.preventDefault();
    const workspace = document.getElementById("workspace");
    splitter.classList.add("dragging");
    document.body.classList.add("resizing");
    if (splitter.setPointerCapture) splitter.setPointerCapture(event.pointerId);
    const onMove = (move) => resizePanel(workspace, side, move.clientX);
    const onUp = () => {
      splitter.classList.remove("dragging");
      document.body.classList.remove("resizing");
      splitter.removeEventListener("pointermove", onMove);
      splitter.removeEventListener("pointerup", onUp);
      splitter.removeEventListener("pointercancel", onUp);
      rememberPanelWidths();
    };
    splitter.addEventListener("pointermove", onMove);
    splitter.addEventListener("pointerup", onUp);
    splitter.addEventListener("pointercancel", onUp);
  });
  splitter.addEventListener("keydown", (event) => {
    if (stackedLayout()) return;
    if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
    event.preventDefault();
    const step = event.shiftKey ? 48 : 16;
    const delta = event.key === "ArrowLeft" ? -step : step;
    const current = panels[side];
    const next = side === "namespaces" ? current + delta : current - delta;
    applyPanelWidth(document.getElementById("workspace"), side, next);
    rememberPanelWidths();
  });
  splitter.addEventListener("dblclick", () => {
    applyPanelWidth(document.getElementById("workspace"), side, PANEL_LIMITS[side].fallback);
    rememberPanelWidths();
  });
}

function resizePanel(workspace, side, clientX) {
  const rect = workspace.getBoundingClientRect();
  const width = side === "namespaces" ? clientX - rect.left : rect.right - clientX;
  applyPanelWidth(workspace, side, width);
}

function applyPanelWidth(workspace, side, width) {
  panels[side] = clampPanel(workspace, side, width);
  const property = side === "namespaces" ? "--namespaces-width" : "--detail-width";
  workspace.style.setProperty(property, panels[side] + "px");
  const splitter = document.getElementById(side === "namespaces" ? "split-namespaces" : "split-detail");
  if (!splitter) return;
  splitter.setAttribute("aria-valuemin", String(PANEL_LIMITS[side].min));
  splitter.setAttribute("aria-valuemax", String(PANEL_LIMITS[side].max));
  splitter.setAttribute("aria-valuenow", String(panels[side]));
}

function clampPanel(workspace, side, width) {
  const limit = PANEL_LIMITS[side];
  const rounded = Math.round(Number(width));
  if (!Number.isFinite(rounded)) return limit.fallback;
  let max = limit.max;
  const rect = workspace.getBoundingClientRect();
  if (rect.width > 0) {
    const other = side === "namespaces"
      ? (panelCollapsed("detail") ? PANEL_RAIL : panels.detail)
      : (panelCollapsed("namespaces") ? PANEL_RAIL : panels.namespaces);
    max = Math.min(max, Math.max(limit.min, rect.width - other - MAIN_MIN));
  }
  return Math.min(Math.max(rounded, limit.min), max);
}

function restorePanelWidths(workspace) {
  applyPanelWidth(workspace, "namespaces", storedPanelWidth("namespaces"));
  applyPanelWidth(workspace, "detail", storedPanelWidth("detail"));
}

function storedPanelWidth(side) {
  const saved = Number(sessionStorage.getItem(PANEL_LIMITS[side].key));
  return Number.isFinite(saved) && saved > 0 ? saved : PANEL_LIMITS[side].fallback;
}

function rememberPanelWidths() {
  sessionStorage.setItem(PANEL_LIMITS.namespaces.key, String(panels.namespaces));
  sessionStorage.setItem(PANEL_LIMITS.detail.key, String(panels.detail));
}

function stackedLayout() {
  return window.matchMedia("(max-width: 980px)").matches;
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
  closeManagedEditOutside(state.clusterId);
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

applyTheme(storedTheme());
document.getElementById("theme").addEventListener("change", (event) => {
  applyTheme(event.target.value);
});

document.getElementById("ns-all").addEventListener("click", () => {
  const allSelected = state.selectedNamespaces.size === state.namespaces.length;
  state.selectedNamespaces = allSelected ? new Set() : new Set(state.namespaces);
  paintNamespaces();
  refresh();
});

document.getElementById("namespace-collapse").addEventListener("click", () => {
  setPanelCollapsed("namespaces", !panelCollapsed("namespaces"), true);
});
document.getElementById("detail-collapse").addEventListener("click", () => {
  setPanelCollapsed("detail", !panelCollapsed("detail"), true);
});
document.getElementById("apply-open").addEventListener("click", openApply);
document.getElementById("clusters-open").addEventListener("click", () => {
  if (state.tab !== "management") {
    state.tab = "management";
    state.shellTab = "";
  }
  refresh();
});
installSplitters();
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
