const state = {
  clusters: [],
  clusterId: "",
  namespaces: [],
  namespaceKey: "",
  selectedNamespaces: new Set(),
  tab: "overview",
  shellTab: "",
  resources: [],
  sort: {},
  gridPage: {},
  collapsedGroups: {},
  metricsSort: { key: "pod", dir: "asc" },
  metricsPage: 1,
  attentionSort: { key: "namespace", dir: "asc" },
  attentionPage: 1,
  columnWidths: loadColumnWidths(),
  overview: null,
  selection: null,
  detail: null,
  live: true,
  refreshInterval: 5000,
  liveTimer: 0,
  lastAutoRefresh: 0,
  logs: { deployment: "", pod: "", container: "", tail: "100", q: "", lines: [] },
  forwards: [],
  management: {
    tab: "resources", focusClusterName: false,
    namespaceName: "", clusterName: "", kubeconfig: "", kind: "deployments", editing: null, yaml: "",
    ldap: {
      loaded: false,
      saving: false,
      message: "",
      enabled: false,
      host: "",
      port: 3269,
      defaultDomain: "",
      searchBaseDn: "",
      group: "",
      bindUsername: "",
      bindPassword: "",
      clearBindPassword: false,
      hasBindPassword: false,
      restartRequired: false
    }
  },
  ai: {
    question: "Why is this unhealthy?",
    includeLogs: true,
    answer: "",
    busy: false,
    loaded: false,
    saving: false,
    message: "",
    providerName: "",
    baseUrl: "",
    model: "",
    apiKey: "",
    clearApiKey: false,
    hasApiKey: false,
    orgId: ""
  },
  notice: "",
  refreshing: false,
  source: null,
  timer: 0,
  terminal: {
    history: [],
    cursor: 0,
    draft: "",
    run: 0,
    runComplete: 0,
    commandId: "",
    completeAbort: null,
    active: 0,
    suggestions: [],
    replaceFrom: 0,
    replaceTo: 0,
    line: "",
    hint: "Run kubectl or helm on the selected cluster. Tab completes. Enter runs.",
    usage: "",
    timer: 0,
    abort: null,
    busy: false
  }
};

const MANAGE_KINDS = [
  ["deployments", "Deployments"],
  ["pods", "Pods"],
  ["services", "Services"],
  ["configmaps", "ConfigMaps"]
];

const MANAGEMENT_TABS = {
  resources: "Resources",
  namespaces: "Namespaces",
  clusters: "Clusters",
  ai: "AI assist",
  ldap: "LDAP login"
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

const REFRESH_INTERVAL_KEY = "k8s-dashboard-refresh-interval";
const REFRESH_INTERVALS = [2000, 5000, 10000, 15000, 20000, 25000, 30000, 60000];
// "manual" means no timer-driven or change-driven auto-refresh at all: the user has
// to press a refresh button for anything to update.
const REFRESH_MANUAL = "manual";

function storedRefreshInterval() {
  const saved = storageGet(localStorage, REFRESH_INTERVAL_KEY);
  if (saved === REFRESH_MANUAL) return REFRESH_MANUAL;
  const parsed = parseInt(saved, 10);
  return REFRESH_INTERVALS.includes(parsed) ? parsed : 5000;
}

function applyRefreshInterval(interval) {
  const choice = interval === REFRESH_MANUAL || REFRESH_INTERVALS.includes(interval) ? interval : 5000;
  state.refreshInterval = choice;
  const select = document.getElementById("refresh-interval");
  if (select && select.value !== String(choice)) select.value = String(choice);
  storageSet(localStorage, REFRESH_INTERVAL_KEY, String(choice));
  const live = document.getElementById("live");
  if (live) {
    const manual = choice === REFRESH_MANUAL;
    live.disabled = manual;
    live.closest("label").title = manual ? "Live is paused while Refresh is set to Manual" : "";
  }
  startLiveTimer();
}

const COLUMN_WIDTHS_KEY = "k8s-dashboard-column-widths";

// Manually resized column widths, keyed by tab then column label, so a resize
// made on pods doesn't leak onto deployments and survives a page reload.
function loadColumnWidths() {
  try {
    const saved = JSON.parse(storageGet(localStorage, COLUMN_WIDTHS_KEY) || "{}");
    return saved && typeof saved === "object" ? saved : {};
  } catch (error) {
    return {};
  }
}

function saveColumnWidths() {
  storageSet(localStorage, COLUMN_WIDTHS_KEY, JSON.stringify(state.columnWidths));
}

// Drives the periodic auto-refresh while Live is on, at the user-selected cadence
// (independent of the server's SSE "changed" push, which still kicks an immediate
// refresh when something actually changes on the cluster).
function startLiveTimer() {
  window.clearInterval(state.liveTimer);
  if (!state.live || state.refreshInterval === REFRESH_MANUAL) return;
  state.liveTimer = window.setInterval(() => {
    if (document.hidden) return;
    state.lastAutoRefresh = Date.now();
    refresh();
  }, state.refreshInterval);
}

function esc(value) {
  return String(value ?? "").replace(/[&<>"']/g, (char) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  }[char]));
}

const htmlCache = new WeakMap();

// Only touches the DOM when the markup actually changed, so a periodic refresh
// with unchanged data doesn't tear down and rebuild rows (which is what causes
// tables to visibly flicker and lose scroll position / hover state).
// Returns true when the element was updated, false when the write was skipped.
function setHTML(element, html) {
  if (!element) return false;
  if (htmlCache.get(element) === html) return false;
  htmlCache.set(element, html);
  element.innerHTML = html;
  return true;
}

const COPY_ICON = `<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M7 15H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h7a2 2 0 0 1 2 2v1"/></svg>`;
const copyIcons = new WeakMap();
const copyTimers = new WeakMap();

function copyButton(label, mode, id, enabled) {
  const ident = id ? ` id="${id}"` : "";
  const disabled = enabled === false ? " disabled" : "";
  return `<button type="button" class="copy-icon" data-copy="${mode}" aria-label="${esc(label)}" title="Copy"${ident}${disabled}>${COPY_ICON}</button>`;
}

function configMapCopyButton(resource) {
  return `<button type="button" class="copy-icon" data-copy="configmap" data-namespace="${esc(resource.namespace)}" data-name="${esc(resource.name)}" aria-label="Copy ${esc(resource.name)}" title="Copy">${COPY_ICON}</button>`;
}

const REFRESH_ICON = `<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>`;

// A single element per shell (id="tab-refresh") so paintShell only needs to wire the click
// handler once; paintRefreshState() finds it (and any other .refresh-btn) to toggle the spin.
function refreshButton(label) {
  return `<button type="button" class="icon-button refresh-btn" id="tab-refresh" aria-label="${esc(label)}" title="${esc(label)}">${REFRESH_ICON}</button>`;
}

// Reflects whether a refresh is in flight: spins every refresh button currently on screen and
// shows/hides the "Refreshing…" status next to the counts, so a slow fetch (e.g. many namespaces)
// never looks stuck or - the bug this replaces - like a silent flash of new content.
function paintRefreshState() {
  document.querySelectorAll(".refresh-btn").forEach((button) => {
    button.classList.toggle("spinning", state.refreshing);
  });
  const status = document.getElementById("refresh-status");
  if (status) status.innerHTML = state.refreshing ? `${REFRESH_ICON}<span>Refreshing…</span>` : "";
}

function configDataText(yaml) {
  const lines = String(yaml || "").replace(/\r\n/g, "\n").split("\n");
  const start = lines.findIndex((line) => line === "data:" || line === "binaryData:");
  if (start < 0) return "";
  const kept = [];
  for (let index = start; index < lines.length; index++) {
    if (index > start && lines[index] && !/^\s/.test(lines[index])) break;
    kept.push(lines[index]);
  }
  return kept.join("\n").trim();
}

async function textForCopy(button) {
  const mode = button.dataset.copy;
  if (mode === "assist") {
    if (!state.ai.answer || state.ai.busy) return null;
    return state.ai.answer;
  }
  if (mode === "logs") {
    const output = document.getElementById("log-output");
    if (!output || output.dataset.empty === "true") return null;
    return output.textContent;
  }
  if (mode === "terminal") {
    const output = document.getElementById("terminal-output");
    if (!output || !output.querySelector(".term-entry")) return null;
    return output.innerText.trim();
  }
  if (mode === "yaml" || mode === "edit-yaml") {
    if (mode === "edit-yaml") {
      const box = document.getElementById("manage-yaml");
      return box && box.value.trim() ? box.value : null;
    }
    const yaml = document.getElementById("yaml");
    return yaml && yaml.textContent.trim() ? yaml.textContent : null;
  }
  if (mode === "configmap") {
    const name = button.dataset.name || "";
    const namespace = button.dataset.namespace || "";
    if (!name) return null;
    let yaml = "";
    const open = state.detail && state.detail.resource;
    if (open && open.kind === "ConfigMap" && open.name === name && (open.namespace || "") === namespace) {
      yaml = state.detail.yaml || "";
    } else {
      const detail = await api("/api/resources/ConfigMap/" + encodeURIComponent(namespace || "_") + "/" + encodeURIComponent(name) + "?" + clusterQuery());
      yaml = detail.yaml || "";
    }
    return configDataText(yaml) || yaml || null;
  }
  return null;
}

async function writeClipboard(text) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    await navigator.clipboard.writeText(text);
    return;
  }
  const area = document.createElement("textarea");
  area.value = text;
  area.setAttribute("readonly", "");
  area.style.position = "fixed";
  area.style.left = "-9999px";
  document.body.append(area);
  area.select();
  const ok = document.execCommand("copy");
  area.remove();
  if (!ok) throw new Error("copy failed");
}

function showCopied(button) {
  if (!copyIcons.has(button)) copyIcons.set(button, button.innerHTML);
  if (!button.dataset.copyLabel) button.dataset.copyLabel = button.getAttribute("aria-label") || "Copy";
  button.dataset.copied = "true";
  button.textContent = "Copied!";
  button.setAttribute("aria-label", "Copied!");
  const existing = copyTimers.get(button);
  if (existing) window.clearTimeout(existing);
  copyTimers.set(button, window.setTimeout(() => {
    copyTimers.delete(button);
    if (!button.isConnected || button.dataset.copied !== "true") return;
    button.innerHTML = copyIcons.get(button);
    button.setAttribute("aria-label", button.dataset.copyLabel);
    delete button.dataset.copied;
    syncCopyButtons();
  }, 1200));
}

async function copyFromButton(button) {
  const text = await textForCopy(button);
  if (text == null) return;
  try {
    await writeClipboard(text);
    showCopied(button);
  } catch (error) {
    notice("Select the text and copy it");
  }
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

function csrfCookie() {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : "";
}

async function api(path, options = {}) {
  const method = options.method || "GET";
  const headers = { Accept: "application/json" };
  if (options.body) headers["Content-Type"] = "application/json";
  // A session cookie, not a bearer token, carries auth now. Mutating
  // requests still need the CSRF header the cookie-backed token repository
  // expects; GETs are exempt from CSRF checks so this is harmless either way.
  if (method !== "GET" && method !== "HEAD") {
    const token = csrfCookie();
    if (token) headers["X-XSRF-TOKEN"] = token;
  }
  const response = await fetch(path, {
    method,
    headers,
    body: options.body,
    credentials: "same-origin",
    signal: options.signal
  });
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

// True once namespaces have loaded for the cluster and the user has unchecked every one of
// them. Leaving the namespaces param off a request (what namespaceQuery() does when nothing is
// selected) reads server-side as "no filter", i.e. every namespace - so every view that is
// scoped by namespace must check this first and show nothing instead of quietly falling back
// to showing everything.
function namespaceScopeEmpty() {
  return state.namespaces.length > 0 && state.selectedNamespaces.size === 0;
}

// Human-readable summary of the namespace selection, used anywhere a resource tab reports
// how many namespaces it's scoped to. Beyond just the count, this names the namespace(s) so
// a single selection reads as "namespace: foo" rather than the indistinguishable "1 namespace"
// every tab used to show.
function namespaceSummary() {
  const total = state.namespaces.length;
  const selected = state.selectedNamespaces.size;
  if (total === 0) return "no namespaces";
  if (selected === 0) return "no namespaces selected";
  if (selected === total) return "all " + total + " namespaces";
  const names = [...state.selectedNamespaces].sort().join(", ");
  return (selected === 1 ? "namespace" : selected + " namespaces") + " (" + names + ")";
}

function filterQuery() {
  const query = document.getElementById("query").value.trim();
  const label = document.getElementById("label").value.trim();
  const image = document.getElementById("image").value.trim();
  const node = document.getElementById("node").value.trim();
  const name = document.getElementById("name").value.trim();
  let extra = "";
  if (query) extra += "&q=" + encodeURIComponent(query);
  if (label) extra += "&label=" + encodeURIComponent(label);
  if (image) extra += "&image=" + encodeURIComponent(image);
  if (node) extra += "&node=" + encodeURIComponent(node);
  if (name) extra += "&name=" + encodeURIComponent(name);
  if (state.tab === "pods") {
    const status = document.getElementById("status").value.trim();
    if (status) extra += "&status=" + encodeURIComponent(status);
  }
  return extra;
}

async function suggestNamespaces(query, signal) {
  if (!state.clusterId) return [];
  const names = await api("/api/namespaces?" + clusterQuery(), { signal });
  const lower = query.toLowerCase();
  return (names || []).filter((name) => name.toLowerCase().includes(lower));
}

async function suggestResourceNames(kind, namespaces, query, signal) {
  if (!state.clusterId) return [];
  let path = "/api/resources?" + clusterQuery() + "&kind=" + encodeURIComponent(kind);
  if (namespaces && namespaces.length) path += "&namespaces=" + encodeURIComponent(namespaces.join(","));
  const items = await api(path, { signal });
  const lower = query.toLowerCase();
  return (items || []).map((item) => item.name).filter((name) => name.toLowerCase().includes(lower));
}

async function suggestContainers(namespaces, deployment, pod, query, signal) {
  if (!state.clusterId) return [];
  let path = "/api/containers?" + clusterQuery();
  if (namespaces && namespaces.length) path += "&namespaces=" + encodeURIComponent(namespaces.join(","));
  if (deployment) path += "&deployment=" + encodeURIComponent(deployment);
  if (pod) path += "&pod=" + encodeURIComponent(pod);
  const names = await api(path, { signal });
  const lower = query.toLowerCase();
  return (names || []).filter((name) => name.toLowerCase().includes(lower));
}

const RESOURCE_TABS = new Set(["pods", "deployments", "services", "configmaps", "nodes", "events"]);

// Label/image/node suggestions come from the resources currently in view
// (the active tab's kind, in the selected namespaces) rather than from the
// already-filtered grid, so picking one value doesn't hide the others.
async function suggestFromResources(extract, query, signal) {
  if (!state.clusterId || !RESOURCE_TABS.has(state.tab)) return [];
  const kind = tabs[state.tab];
  const path = "/api/resources?" + clusterQuery() + "&kind=" + encodeURIComponent(kind) + namespaceQuery();
  const items = await api(path, { signal });
  const lower = query.toLowerCase();
  const values = new Set();
  (items || []).forEach((item) => extract(item).forEach((value) => {
    if (value) values.add(value);
  }));
  return [...values].filter((value) => value.toLowerCase().includes(lower));
}

function suggestLabels(query, signal) {
  return suggestFromResources(
    (item) => Object.entries(item.labels || {}).map(([key, value]) => key + "=" + value),
    query,
    signal
  );
}

function suggestImages(query, signal) {
  return suggestFromResources((item) => item.images || [], query, signal);
}

function suggestNodes(query, signal) {
  return suggestFromResources((item) => [item.node], query, signal);
}

function suggestStatuses(query, signal) {
  return suggestFromResources((item) => [item.status], query, signal);
}

function suggestNames(query, signal) {
  return suggestFromResources((item) => [item.name], query, signal);
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
  paintTerminalContext();
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
  // namespaceKey is only ever "" before namespaces have been loaded for any cluster, so that -
  // not an empty selection - is what means "first load, default to everything". Once it's been
  // set, an empty selection means the user unchecked every namespace on purpose and a routine
  // reload (e.g. after creating/editing/deleting a resource) must not snap it back to "all".
  const firstLoad = state.namespaceKey === "";
  if (selectAll || (firstLoad && state.selectedNamespaces.size === 0)) {
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
  const html = state.namespaces.map((name) => `
    <div class="ns">
      <input type="checkbox" value="${esc(name)}" aria-label="${esc(name)}" ${state.selectedNamespaces.has(name) ? "checked" : ""}>
      <span class="ns-name">${esc(name)}</span>
    </div>
  `).join("") || `<p class="muted">No namespaces</p>`;
  if (setHTML(host, html)) {
    host.querySelectorAll("input").forEach((input) => {
      input.addEventListener("change", () => {
        if (input.checked) state.selectedNamespaces.add(input.value);
        else state.selectedNamespaces.delete(input.value);
        paintTerminalContext();
        refresh();
      });
    });
  }
  paintTerminalContext();
}

function paintShell() {
  const view = document.getElementById("view");
  if (state.tab === "logs") {
    view.innerHTML = `
      <div class="tab-tools">${copyButton("Copy logs", "logs", "", false)}${refreshButton("Refresh logs")}</div>
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
    attachPrediction(document.getElementById("log-deployment"), (query, signal) =>
      suggestResourceNames("deployments", [...state.selectedNamespaces], query, signal));
    attachPrediction(document.getElementById("log-pod"), (query, signal) =>
      suggestResourceNames("pods", [...state.selectedNamespaces], query, signal));
    attachPrediction(document.getElementById("log-container"), (query, signal) =>
      suggestContainers(
        [...state.selectedNamespaces],
        document.getElementById("log-deployment").value.trim(),
        document.getElementById("log-pod").value.trim(),
        query,
        signal
      ));
  } else if (state.tab === "forwards") {
    view.innerHTML = `
      <div class="tab-tools">${refreshButton("Refresh port forwards")}</div>
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
    attachPrediction(document.getElementById("forward-namespace"), (query, signal) =>
      suggestNamespaces(query, signal));
    attachPrediction(document.getElementById("forward-name"), (query, signal) => {
      const kind = document.getElementById("forward-kind").value === "service" ? "services" : "pods";
      const namespace = document.getElementById("forward-namespace").value.trim();
      return suggestResourceNames(kind, namespace ? [namespace] : [], query, signal);
    });
  } else if (state.tab === "assist") {
    view.innerHTML = `
      <div class="panel assist">
        <div class="panel-head"><h2>Assist</h2>${copyButton("Copy answer", "assist", "ai-copy")}</div>
        <p class="muted">Set the provider, model, and API key under Management. <button type="button" id="assist-open-config">Open Management</button></p>
        <label class="inline"><input id="ai-logs" type="checkbox" ${state.ai.includeLogs ? "checked" : ""}> Include recent logs</label>
        <label>Question <textarea id="ai-question">${esc(state.ai.question)}</textarea></label>
        <div class="actions">
          <button class="primary" id="ai-ask" type="button">Ask</button>
        </div>
        <textarea id="ai-answer" class="answer" readonly aria-label="Assist answer"></textarea>
      </div>`;
    document.getElementById("assist-open-config").addEventListener("click", () => {
      state.tab = "management";
      state.management.tab = "ai";
      state.shellTab = "";
      refresh();
    });
    document.getElementById("ai-ask").addEventListener("click", ask);
  } else if (state.tab === "management") {
    const managementTab = state.management.tab;
    view.innerHTML = `
      <nav class="tabs sub-tabs" id="management-tabs">
        ${Object.entries(MANAGEMENT_TABS).map(([value, label]) =>
          `<button type="button" data-management-tab="${value}" class="${managementTab === value ? "active" : ""}">${esc(label)}</button>`).join("")}
      </nav>
      ${managementTab === "resources" ? `
        <div class="tab-tools">${refreshButton("Refresh resources")}</div>
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
              <div class="panel-head"><h3 id="manage-edit-title"></h3>${copyButton("Copy manifest", "edit-yaml", "", false)}</div>
              <textarea id="manage-yaml">${esc(state.management.yaml)}</textarea>
              <div class="actions"><button class="primary" type="submit">Save</button><button type="button" id="manage-edit-cancel">Cancel</button></div>
            </form>
          </div>
        </div>` : ""}
      ${managementTab === "namespaces" ? `
        <div class="tab-tools">${refreshButton("Refresh namespaces")}</div>
        <div class="stack">
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
        </div>` : ""}
      ${managementTab === "clusters" ? `
        <div class="tab-tools">${refreshButton("Refresh clusters")}</div>
        <div class="stack">
          <div class="panel manage" id="cluster-panel">
            <h2>Clusters</h2>
            <div id="cluster-rows"></div>
            <h3>Add kubeconfig</h3>
            <p class="muted">Paste the YAML or choose a file. Stored under the server data directory with user-only permissions. Every context becomes a cluster. The file is not sent to a model.</p>
            <label class="cluster-file">Kubeconfig file <input id="cluster-file" type="file" accept=".yml,.yaml,.conf,.kubeconfig,text/yaml,application/yaml,text/plain"></label>
            <input id="cluster-name" placeholder="Display name for a single context" value="${esc(state.management.clusterName)}">
            <textarea id="cluster-kubeconfig" placeholder="apiVersion: v1&#10;kind: Config">${esc(state.management.kubeconfig)}</textarea>
            <div class="actions"><button class="primary" id="cluster-add" type="button">Add</button></div>
          </div>
        </div>` : ""}
      ${managementTab === "ai" ? `<div class="panel manage" id="ai-config-panel"></div>` : ""}
      ${managementTab === "ldap" ? `<div class="panel manage" id="ldap-panel"></div>` : ""}`;
    document.getElementById("management-tabs").addEventListener("click", (event) => {
      const button = event.target.closest("button[data-management-tab]");
      if (!button) return;
      state.management.tab = button.dataset.managementTab;
      // refresh() only rebuilds the view's HTML when state.shellTab !== state.tab,
      // and state.tab stays "management" across sub-tabs - force that rebuild the
      // same way switching a top-level tab does, or the new sub-tab's panel never
      // actually renders.
      state.shellTab = "";
      refresh();
    });
    if (managementTab === "resources") {
      document.getElementById("manage-kind").addEventListener("change", (event) => {
        state.management.kind = event.target.value;
        closeManagedEdit();
        refresh();
      });
      document.getElementById("manage-yaml").addEventListener("input", (event) => {
        state.management.yaml = event.target.value;
        syncCopyButtons();
      });
      document.getElementById("manage-edit").addEventListener("submit", saveManagedResource);
      document.getElementById("manage-edit-cancel").addEventListener("click", closeManagedEdit);
      if (state.management.editing) setManagedEditTitle(state.management.editing);
    } else if (managementTab === "namespaces") {
      document.getElementById("namespace-name").addEventListener("input", (event) => {
        state.management.namespaceName = event.target.value;
      });
      document.getElementById("namespace-form").addEventListener("submit", createNamespace);
    } else if (managementTab === "clusters") {
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
      if (state.management.focusClusterName) {
        state.management.focusClusterName = false;
        document.getElementById("cluster-name").focus();
      }
    } else if (managementTab === "ai") {
      paintAiConfigPanel();
      loadAiSettings();
    } else if (managementTab === "ldap") {
      paintLdapPanel();
      loadLdapSettings();
    }
  } else if (state.tab === "overview") {
    view.innerHTML = `<div class="tab-tools">${refreshButton("Refresh overview")}</div><div id="overview-cards" class="cards"></div><div class="split"><div class="panel"><h2>Attention</h2><div id="attention"></div></div><div class="panel"><h2>Pod metrics</h2><div id="metrics"></div></div></div>`;
    document.getElementById("metrics").addEventListener("click", onMetricsClick);
    document.getElementById("attention").addEventListener("click", onAttentionClick);
  } else {
    view.innerHTML = `<div class="tab-tools">${refreshButton("Refresh " + tabs[state.tab].toLowerCase() + "s")}</div><div class="table-wrap"><table id="grid-table"><colgroup id="grid-cols"></colgroup><thead id="grid-head"></thead><tbody id="grid-body"></tbody></table></div><div id="grid-pagination"></div>`;
    document.getElementById("grid-pagination").addEventListener("click", onGridPageClick);
  }
  const refreshBtn = document.getElementById("tab-refresh");
  if (refreshBtn) {
    refreshBtn.addEventListener("click", () => refresh());
    refreshBtn.classList.toggle("spinning", state.refreshing);
  }
  const gridHead = document.getElementById("grid-head");
  if (gridHead) {
    gridHead.addEventListener("click", onGridHeaderClick);
    gridHead.addEventListener("mousedown", onGridHeaderMouseDown);
    gridHead.addEventListener("dblclick", onGridHeaderDoubleClick);
  }
  state.shellTab = state.tab;
  document.querySelectorAll("#tabs button").forEach((button) => {
    button.classList.toggle("active", button.dataset.tab === state.tab);
  });
  const gridTabs = ["pods", "deployments", "services", "configmaps", "nodes", "events"];
  const hasStatusColumn = gridTabs.includes(state.tab) && columnsFor(state.tab).some((column) => column.label === "Status");
  document.querySelectorAll(".pods-only").forEach((field) => {
    field.classList.toggle("hidden", !hasStatusColumn);
  });
  syncCopyButtons();
}

function captureLogs() {
  state.logs.deployment = document.getElementById("log-deployment").value.trim();
  state.logs.pod = document.getElementById("log-pod").value.trim();
  state.logs.container = document.getElementById("log-container").value.trim();
  state.logs.tail = document.getElementById("log-tail").value;
  state.logs.q = document.getElementById("log-q").value.trim();
  refresh();
}

let refreshBusy = false;
let refreshQueued = false;

// A live tick arrives every few seconds and queues a refresh. Fetching the
// overview for several namespaces can take longer than that interval, so
// without this guard a slow in-flight refresh and the next tick's refresh
// would both land on the DOM out of order - the visible symptom is the
// overview table flashing/re-rendering right after it loads. Queue at most
// one follow-up refresh instead of running them concurrently.
async function refresh() {
  if (refreshBusy) {
    refreshQueued = true;
    return;
  }
  refreshBusy = true;
  state.refreshing = true;
  paintRefreshState();
  try {
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
      if (error.status === 401) goToLogin();
      else notice(error.message);
    }
  } finally {
    refreshBusy = false;
    if (refreshQueued) {
      refreshQueued = false;
      refresh();
    } else {
      state.refreshing = false;
      paintRefreshState();
    }
  }
}

// Mirrors the shape of /api/overview so the panel still renders (all zeroed) when no
// namespace is selected, instead of calling the API with a filter that would be read as "all".
function emptyOverview() {
  const cluster = state.clusters.find((item) => item.id === state.clusterId);
  return {
    clusterId: state.clusterId,
    clusterName: cluster ? cluster.name : "",
    demo: !!(cluster && cluster.demo),
    version: "",
    namespaces: 0,
    pods: 0,
    readyPods: 0,
    deployments: 0,
    services: 0,
    configMaps: 0,
    nodes: 0,
    warnings: 0,
    phases: [],
    attention: [],
    metrics: []
  };
}

async function paintOverview() {
  state.overview = namespaceScopeEmpty() ? emptyOverview() : await api("/api/overview?" + clusterQuery() + namespaceQuery());
  const cardsHost = document.getElementById("overview-cards");
  const attentionHost = document.getElementById("attention");
  const metricsHost = document.getElementById("metrics");
  if (!cardsHost || !attentionHost || !metricsHost) return;
  const overview = state.overview;
  const cards = [
    ["Namespaces", overview.namespaces, "Number of namespaces in the current view", null],
    ["Pods ready/total", overview.readyPods + "/" + overview.pods, "Running pods out of total pods in the current view", null],
    ["Deployments", overview.deployments, "Number of Deployments in the current view", null],
    ["Services", overview.services, "Number of Services in the current view", null],
    ["ConfigMaps", overview.configMaps, "Number of ConfigMaps in the current view", null],
    ["Nodes", overview.nodes, "Number of Nodes in the cluster", null],
    ["Warnings", overview.warnings, "Number of Warning events in the current view", null],
    ["Version", overview.version || "—", "Kubernetes server version reported by the cluster", null]
  ];
  setHTML(cardsHost, cards.map(([label, value, tooltip, sub]) =>
    `<article class="card" title="${esc(tooltip)}"><strong>${esc(value)}</strong><span>${esc(label)}</span>${sub ? `<small>${esc(sub)}</small>` : ""}</article>`
  ).join(""));
  renderAttention(attentionHost, overview.attention);
  renderMetrics(metricsHost, overview.metrics);
  document.getElementById("counts").textContent = overview.clusterName + (overview.demo ? " · demo data" : "");
}

const GRID_PAGE_SIZE = 25;
const METRICS_PAGE_SIZE = 10;
const METRICS_COLUMNS = [
  { key: "pod", label: "Pod", value: (row) => row.namespace + "/" + row.name },
  { key: "cpu", label: "CPU", value: (row) => metricNumber(row.cpu) },
  { key: "memory", label: "Memory", value: (row) => metricNumber(row.memory) }
];

// CPU/memory are always rendered as a single unit per column ("250m", "128Mi"),
// so the leading number alone is enough to sort them correctly.
function metricNumber(text) {
  const match = String(text || "").match(/[\d.]+/);
  return match ? parseFloat(match[0]) : 0;
}

function sortMetrics(rows, sort) {
  const column = METRICS_COLUMNS.find((candidate) => candidate.key === sort.key);
  if (!column) return rows;
  const sorted = rows.slice().sort((a, b) => compareValues(column.value(a), column.value(b)));
  return sort.dir === "desc" ? sorted.reverse() : sorted;
}

// Renders the Pod metrics panel sorted and paginated client-side so a namespace
// with many pods stays scannable instead of one long unbroken table.
function renderMetrics(host, rows) {
  if (!rows.length) {
    setHTML(host, `<p class="muted">No metrics yet. metrics-server is optional on a live cluster.</p>`);
    return;
  }
  const sort = state.metricsSort;
  const sorted = sortMetrics(rows, sort);
  const pageCount = Math.max(1, Math.ceil(sorted.length / METRICS_PAGE_SIZE));
  state.metricsPage = Math.min(Math.max(1, state.metricsPage), pageCount);
  const start = (state.metricsPage - 1) * METRICS_PAGE_SIZE;
  const page = sorted.slice(start, start + METRICS_PAGE_SIZE);
  const head = METRICS_COLUMNS.map((column) => {
    const active = sort.key === column.key;
    const ariaSort = active ? (sort.dir === "desc" ? "descending" : "ascending") : "none";
    const arrow = active ? `<span class="sort-arrow">${sort.dir === "desc" ? "▼" : "▲"}</span>` : "";
    return `<th class="sortable" data-metrics-sort="${column.key}" aria-sort="${ariaSort}"><span class="th-label">${esc(column.label)}</span>${arrow}</th>`;
  }).join("");
  const body = page.map((row) => `<tr><td>${esc(row.namespace)}/${esc(row.name)}</td><td>${esc(row.cpu)}</td><td>${esc(row.memory)}</td></tr>`).join("");
  const pagination = `<div class="pagination">
    <button type="button" data-metrics-page="prev"${state.metricsPage <= 1 ? " disabled" : ""}>Prev</button>
    <span class="muted">Page ${state.metricsPage} of ${pageCount} · ${sorted.length} pods</span>
    <button type="button" data-metrics-page="next"${state.metricsPage >= pageCount ? " disabled" : ""}>Next</button>
  </div>`;
  setHTML(host, `<div class="panel-table-scroll"><table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div>${pagination}`);
}

const ATTENTION_PAGE_SIZE = 10;
const ATTENTION_COLUMNS = [
  { key: "namespace", label: "Namespace", value: (row) => row.namespace },
  { key: "kind", label: "Kind", value: (row) => row.kind },
  { key: "name", label: "Name", value: (row) => row.name },
  { key: "status", label: "Status", value: (row) => row.status },
  { key: "summary", label: "Summary", value: (row) => row.summary }
];

function sortAttention(rows, sort) {
  const column = ATTENTION_COLUMNS.find((candidate) => candidate.key === sort.key);
  if (!column) return rows;
  const sorted = rows.slice().sort((a, b) => compareValues(column.value(a), column.value(b)));
  return sort.dir === "desc" ? sorted.reverse() : sorted;
}

// Renders the Attention panel sorted and paginated client-side, same as the
// Pod metrics panel, so a namespace (or cluster) with many warnings stays
// scannable instead of one long unbroken table.
function renderAttention(host, rows) {
  if (!rows.length) {
    setHTML(host, `<p class="muted">Nothing needs attention in this view.</p>`);
    return;
  }
  const sort = state.attentionSort;
  const sorted = sortAttention(rows, sort);
  const pageCount = Math.max(1, Math.ceil(sorted.length / ATTENTION_PAGE_SIZE));
  state.attentionPage = Math.min(Math.max(1, state.attentionPage), pageCount);
  const start = (state.attentionPage - 1) * ATTENTION_PAGE_SIZE;
  const page = sorted.slice(start, start + ATTENTION_PAGE_SIZE);
  const head = ATTENTION_COLUMNS.map((column) => {
    const active = sort.key === column.key;
    const ariaSort = active ? (sort.dir === "desc" ? "descending" : "ascending") : "none";
    const arrow = active ? `<span class="sort-arrow">${sort.dir === "desc" ? "▼" : "▲"}</span>` : "";
    return `<th class="sortable" data-attention-sort="${column.key}" aria-sort="${ariaSort}"><span class="th-label">${esc(column.label)}</span>${arrow}</th>`;
  }).join("");
  const body = page.map((item) => `<tr><td>${esc(item.namespace)}</td><td>${esc(item.kind)}</td><td>${esc(item.name)}</td><td>${chip(item.status)}</td><td>${esc(item.summary)}</td></tr>`).join("");
  const pagination = `<div class="pagination">
    <button type="button" data-attention-page="prev"${state.attentionPage <= 1 ? " disabled" : ""}>Prev</button>
    <span class="muted">Page ${state.attentionPage} of ${pageCount} · ${sorted.length} items</span>
    <button type="button" data-attention-page="next"${state.attentionPage >= pageCount ? " disabled" : ""}>Next</button>
  </div>`;
  setHTML(host, `<div class="panel-table-scroll"><table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div>${pagination}`);
}

// Delegated on the persistent #attention container (it's rebuilt via setHTML
// on every refresh), so header-sort and pagination clicks keep working
// without re-binding listeners each render.
function onAttentionClick(event) {
  const sortHeader = event.target.closest("[data-attention-sort]");
  if (sortHeader) {
    const key = sortHeader.dataset.attentionSort;
    const current = state.attentionSort;
    state.attentionSort = { key, dir: current.key === key && current.dir === "asc" ? "desc" : "asc" };
    state.attentionPage = 1;
    renderAttention(event.currentTarget, state.overview ? state.overview.attention : []);
    return;
  }
  const pageButton = event.target.closest("[data-attention-page]");
  if (pageButton && !pageButton.disabled) {
    state.attentionPage += pageButton.dataset.attentionPage === "next" ? 1 : -1;
    renderAttention(event.currentTarget, state.overview ? state.overview.attention : []);
  }
}

// Delegated on the persistent #metrics container (it's rebuilt via setHTML on
// every refresh), so header-sort and pagination clicks keep working without
// re-binding listeners each render.
function onMetricsClick(event) {
  const sortHeader = event.target.closest("[data-metrics-sort]");
  if (sortHeader) {
    const key = sortHeader.dataset.metricsSort;
    const current = state.metricsSort;
    state.metricsSort = { key, dir: current.key === key && current.dir === "asc" ? "desc" : "asc" };
    state.metricsPage = 1;
    renderMetrics(event.currentTarget, state.overview ? state.overview.metrics : []);
    return;
  }
  const pageButton = event.target.closest("[data-metrics-page]");
  if (pageButton && !pageButton.disabled) {
    state.metricsPage += pageButton.dataset.metricsPage === "next" ? 1 : -1;
    renderMetrics(event.currentTarget, state.overview ? state.overview.metrics : []);
  }
}

async function paintResources() {
  const kind = tabs[state.tab];
  state.resources = namespaceScopeEmpty()
    ? []
    : await api("/api/resources?" + clusterQuery() + "&kind=" + encodeURIComponent(kind) + namespaceQuery() + filterQuery());
  renderGrid();
}

// Re-draws the resource grid from already-fetched state.resources - used both
// after a fetch and after a client-side-only change (sort, column resize) so
// those don't need to round-trip to the server.
function renderGrid() {
  const gridHead = document.getElementById("grid-head");
  const gridBody = document.getElementById("grid-body");
  const gridCols = document.getElementById("grid-cols");
  const gridTable = document.getElementById("grid-table");
  if (!gridHead || !gridBody || !gridCols || !gridTable) return;
  const columns = columnsFor(state.tab);
  const widths = state.columnWidths[state.tab] || {};
  const fixed = Object.keys(widths).length > 0;
  gridTable.classList.toggle("fixed-cols", fixed);
  setHTML(gridCols, columns.map((column) =>
    `<col${widths[column.label] ? ` style="width:${widths[column.label]}px"` : ""}>`
  ).join(""));
  const sort = state.sort[state.tab];
  setHTML(gridHead, `<tr>${columns.map((column) => {
    const active = sort && sort.label === column.label;
    const ariaSort = active ? (sort.dir === "desc" ? "descending" : "ascending") : "none";
    const arrow = active ? `<span class="sort-arrow">${sort.dir === "desc" ? "▼" : "▲"}</span>` : "";
    return `<th class="sortable" data-label="${esc(column.label)}" aria-sort="${ariaSort}"${column.title ? ` title="${esc(column.title)}"` : ""}><span class="th-label">${esc(column.label)}</span>${arrow}<span class="col-resizer" title="Drag to resize, double-click to fit"></span></th>`;
  }).join("")}</tr>`);
  const resources = sort ? sortResources(state.resources, columns, sort) : state.resources;
  // Grouping by namespace only reads naturally when the rows are still in
  // server order - once a column sort is active, show one flat sorted list.
  const allGroups = new Map();
  resources.forEach((resource) => {
    const key = resource.namespace || "cluster";
    if (!allGroups.has(key)) allGroups.set(key, []);
    allGroups.get(key).push(resource);
  });
  const multiple = !sort && allGroups.size > 1;
  const collapsed = state.collapsedGroups[state.tab] || (state.collapsedGroups[state.tab] = new Set());
  // Paginated client-side, same as the overview's pod metrics table, so a namespace (or
  // cluster) with hundreds of resources stays scannable instead of one long unbroken table.
  // Groups are allowed to span pages - a namespace bigger than a page just continues onto
  // the next one (flagged below so its header can read "(continued)") - rather than being
  // kept atomic. Keeping a group atomic meant expanding a collapsed namespace could grow it
  // past the room left on the page it was sitting on, bumping it (and the user) onto a
  // different page than the one they were just looking at.
  let pages;
  if (multiple) {
    pages = [];
    let current = [];
    let currentCount = 0;
    const pushPage = () => {
      if (current.length) pages.push(current);
      current = [];
      currentCount = 0;
    };
    allGroups.forEach((groupResources, namespace) => {
      if (collapsed.has(namespace)) {
        if (currentCount >= GRID_PAGE_SIZE) pushPage();
        current.push([namespace, groupResources, false]);
        currentCount += 1;
        return;
      }
      let remaining = groupResources;
      let continuation = false;
      do {
        if (currentCount >= GRID_PAGE_SIZE) pushPage();
        const chunk = remaining.slice(0, GRID_PAGE_SIZE - currentCount);
        remaining = remaining.slice(chunk.length);
        current.push([namespace, chunk, continuation]);
        currentCount += chunk.length;
        continuation = true;
      } while (remaining.length);
    });
    pushPage();
  } else {
    pages = [];
    for (let start = 0; start < resources.length; start += GRID_PAGE_SIZE) {
      pages.push([["", resources.slice(start, start + GRID_PAGE_SIZE)]]);
    }
  }
  if (!pages.length) pages = [[]];
  const pageCount = pages.length;
  const gridPage = Math.min(Math.max(1, state.gridPage[state.tab] || 1), pageCount);
  state.gridPage[state.tab] = gridPage;
  const pageGroups = pages[gridPage - 1];
  let html = "";
  pageGroups.forEach(([namespace, groupResources, continuation]) => {
    if (multiple) {
      const isCollapsed = collapsed.has(namespace);
      const total = allGroups.get(namespace).length;
      html += `<tr class="group clickable" data-group="${esc(namespace)}" aria-expanded="${isCollapsed ? "false" : "true"}"><td colspan="${columns.length}"><span class="group-arrow">${isCollapsed ? "▶" : "▼"}</span>${esc(namespace)}${continuation ? " (continued)" : ""} · ${total}</td></tr>`;
      if (isCollapsed) return;
    }
    groupResources.forEach((resource) => {
      const selected = state.selection && state.selection.kind === resource.kind && state.selection.name === resource.name && state.selection.namespace === resource.namespace;
      html += `<tr class="clickable ${selected ? "selected" : ""}" data-kind="${esc(resource.kind)}" data-namespace="${esc(resource.namespace)}" data-name="${esc(resource.name)}">`;
      columns.forEach((column) => { html += `<td${column.wrap ? ' class="wrap"' : ""}>${column.cell(resource)}</td>`; });
      html += "</tr>";
    });
  });
  if (setHTML(gridBody, html || `<tr><td>No resources match.</td></tr>`)) {
    gridBody.querySelectorAll("tr.group").forEach((row) => {
      row.addEventListener("click", () => {
        const namespace = row.dataset.group;
        if (collapsed.has(namespace)) collapsed.delete(namespace);
        else collapsed.add(namespace);
        renderGrid();
      });
    });
    gridBody.querySelectorAll("tr.clickable[data-kind]").forEach((row) => {
      row.addEventListener("click", (event) => {
        if (event.target.closest(".copy-icon")) return;
        openDetail(row.dataset.kind, row.dataset.namespace, row.dataset.name);
      });
    });
  }
  const pagination = document.getElementById("grid-pagination");
  if (pagination) {
    setHTML(pagination, resources.length > GRID_PAGE_SIZE ? `<div class="pagination">
      <button type="button" data-grid-page="prev"${gridPage <= 1 ? " disabled" : ""}>Prev</button>
      <span class="muted">Page ${gridPage} of ${pageCount} · ${resources.length} ${state.tab}</span>
      <button type="button" data-grid-page="next"${gridPage >= pageCount ? " disabled" : ""}>Next</button>
    </div>` : "");
  }
  document.getElementById("counts").textContent = state.resources.length + " " + state.tab + " in " + namespaceSummary();
}

// Delegated on the persistent #grid-pagination container (it's rebuilt via
// setHTML on every render) so Prev/Next keep working without re-binding.
function onGridPageClick(event) {
  const pageButton = event.target.closest("[data-grid-page]");
  if (!pageButton || pageButton.disabled) return;
  state.gridPage[state.tab] = (state.gridPage[state.tab] || 1) + (pageButton.dataset.gridPage === "next" ? 1 : -1);
  renderGrid();
}

function compareValues(a, b) {
  if (typeof a === "number" && typeof b === "number") return a - b;
  return String(a ?? "").localeCompare(String(b ?? ""), undefined, { numeric: true, sensitivity: "base" });
}

function sortResources(resources, columns, sort) {
  const column = columns.find((candidate) => candidate.label === sort.label);
  if (!column || !column.value) return resources;
  const sorted = resources.slice().sort((a, b) => compareValues(column.value(a), column.value(b)));
  return sort.dir === "desc" ? sorted.reverse() : sorted;
}

// Clicking a header toggles that column's sort direction (asc -> desc -> asc);
// clicking a different column starts a fresh ascending sort on it.
function onGridHeaderClick(event) {
  if (event.target.closest(".col-resizer")) return;
  const th = event.target.closest("th");
  if (!th) return;
  const label = th.dataset.label;
  const current = state.sort[state.tab];
  state.sort[state.tab] = { label, dir: current && current.label === label && current.dir === "asc" ? "desc" : "asc" };
  state.gridPage[state.tab] = 1;
  renderGrid();
}

let activeResize = null;

// Dragging a column's resize handle. Auto layout (the default) sizes every
// column to its widest cell and won't shrink below that, so the first drag on
// a table snapshots every column's current width and switches the table to a
// fixed layout driven by <colgroup>, which is the layout mode that actually
// honors a manually chosen width.
function onGridHeaderMouseDown(event) {
  const handle = event.target.closest(".col-resizer");
  if (!handle) return;
  const th = handle.closest("th");
  const headerRow = th.parentElement;
  const index = Array.prototype.indexOf.call(headerRow.children, th);
  event.preventDefault();
  const tab = state.tab;
  const widths = state.columnWidths[tab] || (state.columnWidths[tab] = {});
  if (Object.keys(widths).length === 0) {
    const columns = columnsFor(tab);
    Array.from(headerRow.children).forEach((cell, i) => {
      widths[columns[i].label] = Math.round(cell.getBoundingClientRect().width);
    });
    renderGrid();
  }
  const label = th.dataset.label;
  const col = document.getElementById("grid-cols").children[index];
  if (!col) return;
  activeResize = { tab, label, col, startX: event.clientX, startWidth: widths[label] || col.getBoundingClientRect().width };
  document.body.classList.add("col-resizing");
}

document.addEventListener("mousemove", (event) => {
  if (!activeResize) return;
  const width = Math.max(60, Math.round(activeResize.startWidth + (event.clientX - activeResize.startX)));
  activeResize.col.style.width = width + "px";
});

document.addEventListener("mouseup", () => {
  if (!activeResize) return;
  const width = parseInt(activeResize.col.style.width, 10) || activeResize.startWidth;
  state.columnWidths[activeResize.tab][activeResize.label] = width;
  saveColumnWidths();
  document.body.classList.remove("col-resizing");
  activeResize = null;
});

// Double-clicking a resize handle fits that column to its widest current
// cell (scrollWidth still reflects the full content even when the column is
// currently clipped by text-overflow: ellipsis).
function onGridHeaderDoubleClick(event) {
  const handle = event.target.closest(".col-resizer");
  if (!handle) return;
  const th = handle.closest("th");
  const headerRow = th.parentElement;
  const cells = Array.from(headerRow.children);
  const index = cells.indexOf(th);
  const tab = state.tab;
  const columns = columnsFor(tab);
  const widths = state.columnWidths[tab] || (state.columnWidths[tab] = {});
  if (Object.keys(widths).length === 0) {
    cells.forEach((cell, i) => { widths[columns[i].label] = Math.round(cell.getBoundingClientRect().width); });
  }
  const table = document.getElementById("grid-table");
  const bodyCells = table.querySelectorAll(`tbody tr:not(.group) > *:nth-child(${index + 1})`);
  let max = th.querySelector(".th-label").scrollWidth;
  bodyCells.forEach((cell) => { max = Math.max(max, cell.scrollWidth); });
  widths[columns[index].label] = Math.max(60, max + 24);
  saveColumnWidths();
  renderGrid();
}

function columnsFor(tab) {
  const name = { label: "Name", value: (resource) => resource.name || "", cell: (resource) => esc(resource.name) };
  const namespace = { label: "Namespace", value: (resource) => resource.namespace || "", cell: (resource) => esc(resource.namespace || "—") };
  const status = { label: "Status", value: (resource) => resource.status || "", cell: (resource) => chip(resource.status) };
  const ready = { label: "Ready", value: (resource) => resource.desired ? Number(resource.ready) || 0 : -1, cell: (resource) => resource.desired ? esc(resource.ready + "/" + resource.desired) : "" };
  const node = { label: "Node", value: (resource) => resource.node || "", cell: (resource) => esc(resource.node) };
  const images = { label: "Images", wrap: true, value: (resource) => (resource.images || []).join(", "), cell: (resource) => esc((resource.images || []).join(", ")) };
  const created = { label: "Age", value: (resource) => Date.parse(resource.created) || 0, cell: (resource) => esc(age(resource.created)) };
  const summary = { label: "Summary", wrap: true, value: (resource) => resource.summary || "", cell: (resource) => esc(resource.summary) };
  const attr = (label, key, wrap, title) => ({ label, wrap, title, value: (resource) => (resource.attributes || {})[key] || "", cell: (resource) => esc((resource.attributes || {})[key] || "") });
  if (tab === "pods") return [name, namespace, status, ready, node, images, created];
  if (tab === "deployments") return [name, namespace, status, ready, images, created];
  if (tab === "services") return [name, namespace, attr("Type", "type"), attr("Cluster IP", "clusterIP"), attr("Ports", "ports")];
  if (tab === "configmaps") {
    const configName = { label: "Name", value: (resource) => resource.name || "", cell: (resource) => `<span class="copy-line">${esc(resource.name)} ${configMapCopyButton(resource)}</span>` };
    return [configName, namespace, attr("Keys", "keys", true), created];
  }
  if (tab === "nodes") return [
    name,
    status,
    attr("Roles", "roles"),
    attr("CPU (used/allocatable)", "cpu", false, "Current CPU usage reported by metrics-server over the node's allocatable CPU capacity"),
    attr("Memory (used/allocatable)", "memory", false, "Current memory usage reported by metrics-server over the node's allocatable memory capacity")
  ];
  return [namespace, name, status, attr("Reason", "reason"), summary];
}

async function paintLogs() {
  const logs = state.logs;
  let page = { lines: [], truncated: false };
  if (!namespaceScopeEmpty()) {
    let path = "/api/logs?" + clusterQuery() + namespaceQuery() + "&tail=" + encodeURIComponent(logs.tail);
    if (logs.deployment) path += "&deployment=" + encodeURIComponent(logs.deployment);
    if (logs.pod) path += "&pod=" + encodeURIComponent(logs.pod);
    if (logs.container) path += "&container=" + encodeURIComponent(logs.container);
    if (logs.q) path += "&q=" + encodeURIComponent(logs.q);
    page = await api(path);
  }
  const output = document.getElementById("log-output");
  if (!output) return;
  output.dataset.empty = page.lines.length ? "false" : "true";
  output.textContent = page.lines.length
    ? page.lines.map((line) => line.namespace + "/" + line.pod + "/" + line.container + "  " + line.text).join("\n")
    : "No log lines match.";
  document.getElementById("counts").textContent = page.lines.length + (page.truncated ? " lines, truncated" : " lines");
  syncCopyButtons();
}

async function paintForwards() {
  const all = await api("/api/port-forwards?" + clusterQuery());
  // The API lists every open forward for the cluster regardless of namespace, so apply the
  // namespace selection client-side to keep this tab consistent with the rest of the dashboard.
  state.forwards = namespaceScopeEmpty() ? [] : all.filter((forward) => state.selectedNamespaces.has(forward.namespace));
  const host = document.getElementById("forward-list");
  if (!host) return;
  const html = state.forwards.length ? state.forwards.map((forward) => `
    <div class="forward-row">
      <div>
        <strong>${esc(forward.namespace)}/${esc(forward.targetKind)}/${esc(forward.targetName)}</strong>
        <div class="muted">${esc(forward.url)} · pod ${esc(forward.podName)} · ${forward.simulated ? "simulated" : "listening"}</div>
        <div class="muted">${esc(forward.note)}</div>
      </div>
      <button type="button" data-id="${esc(forward.id)}">Stop</button>
    </div>
  `).join("") : `<p class="muted">No forwards are open. A service forward uses the first ready pod that matches the selector.</p>`;
  if (setHTML(host, html)) {
    host.querySelectorAll("button").forEach((button) => {
      button.addEventListener("click", async () => {
        await api("/api/port-forwards/" + encodeURIComponent(button.dataset.id), { method: "DELETE" });
        refresh();
      });
    });
  }
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

// Mirrors the AI assist panel's inputs into state.ai as the user types, so
// Ask (on the Assist tab, where these inputs don't exist in the DOM) always
// sends whatever is currently on screen even before it's saved. A no-op
// when the panel isn't mounted. Actually persisting the configuration -
// writing it, API key encrypted, under the server's data directory - only
// happens on saveAiSettings(), via the panel's Save button.
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
  toggleAiFields();
  updateAiStatus();
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

function updateAiStatus() {
  const statusHost = document.getElementById("ai-status");
  if (!statusHost) return;
  if (!state.ai.loaded) {
    statusHost.textContent = "Loading…";
  } else if (!state.ai.providerName && !state.ai.baseUrl && !state.ai.hasApiKey && !state.ai.apiKey) {
    statusHost.textContent = "Assist is off. Enter a provider name, base URL, and API key, then Save.";
  } else if (!state.ai.baseUrl) {
    statusHost.textContent = "Base URL is required.";
  } else {
    let host = "";
    try {
      host = new URL(state.ai.baseUrl).host;
    } catch (error) {
      host = "";
    }
    const name = state.ai.providerName || "Custom";
    const model = state.ai.model || "server model";
    statusHost.textContent = name + " · " + model + (host ? " · " + host : "") + ". Saved on the server.";
  }
}

function renderAiConfigPanel() {
  const ai = state.ai;
  return `
    <h2>AI assist</h2>
    <p class="muted">Defaults come from the server's application.yml / environment variables. Saving here writes an override - the API key encrypted - under the server's data directory (~/.k8s-dashboard by default), so every browser you open the dashboard in picks it up.</p>
    <p id="ai-status" class="muted"></p>
    <label>Provider name <input id="ai-provider" autocomplete="off" maxlength="80" placeholder="Grok" value="${esc(ai.providerName)}"></label>
    <label>Base URL <input id="ai-base-url" autocomplete="off" maxlength="500" placeholder="https://api.x.ai/v1" value="${esc(ai.baseUrl)}"></label>
    <label>Model <input id="ai-model" autocomplete="off" maxlength="120" placeholder="grok-4.7" value="${esc(ai.model)}"></label>
    <label>API key <span class="muted">${ai.hasApiKey ? "(a key is set)" : "(none set)"}</span>
      <div class="secret">
        <input id="ai-key" type="password" autocomplete="new-password" maxlength="512" placeholder="${ai.hasApiKey ? "Leave blank to keep the current key" : ""}" value="${esc(ai.apiKey)}">
        <button type="button" id="ai-key-toggle" aria-label="Show API key" aria-pressed="false">
          <svg class="eye-on" viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12z"/><circle cx="12" cy="12" r="2.5"/></svg>
          <svg class="eye-off" viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M3 3l18 18"/><path d="M10.5 6.2A10.6 10.6 0 0 1 12 6c6.5 0 10 6 10 6a18 18 0 0 1-3.1 3.7"/><path d="M6.2 6.8C3.9 8.4 2 12 2 12s3.5 6 10 6c1.1 0 2.2-.2 3.2-.6"/><path d="M9.9 9.9a2.5 2.5 0 0 0 3.6 3.6"/></svg>
        </button>
      </div>
    </label>
    <label class="inline"><input id="ai-clear-key" type="checkbox" ${ai.clearApiKey ? "checked" : ""} ${ai.hasApiKey ? "" : "disabled"}> Clear the stored API key</label>
    <label id="ai-org-field" class="${usingDevin() ? "" : "hidden"}">Devin organization id <input id="ai-org" autocomplete="off" placeholder="org-..." value="${esc(ai.orgId)}">
      <span class="muted">From Settings, then Devin API.</span>
    </label>
    <div class="actions">
      <button class="primary" id="ai-save" type="button" ${ai.saving ? "disabled" : ""}>${ai.saving ? "Saving…" : "Save"}</button>
    </div>
    <p id="ai-message" class="muted">${esc(ai.message)}</p>`;
}

function wireAiConfigPanel() {
  document.getElementById("ai-provider").addEventListener("input", saveAiChoice);
  document.getElementById("ai-base-url").addEventListener("input", saveAiChoice);
  document.getElementById("ai-model").addEventListener("input", saveAiChoice);
  document.getElementById("ai-key").addEventListener("input", saveAiChoice);
  document.getElementById("ai-key-toggle").addEventListener("click", toggleApiKey);
  document.getElementById("ai-org").addEventListener("input", saveAiChoice);
  const clearInput = document.getElementById("ai-clear-key");
  if (clearInput) {
    clearInput.addEventListener("change", (event) => {
      state.ai.clearApiKey = event.target.checked;
    });
  }
  const saveButton = document.getElementById("ai-save");
  if (saveButton) saveButton.addEventListener("click", saveAiSettings);
}

function paintAiConfigPanel() {
  const host = document.getElementById("ai-config-panel");
  if (!host) return;
  if (setHTML(host, renderAiConfigPanel())) {
    wireAiConfigPanel();
  }
  updateAiStatus();
  toggleAiFields();
}

async function loadAiSettings() {
  try {
    const settings = await api("/api/management/ai");
    Object.assign(state.ai, settings, {
      loaded: true,
      saving: false,
      apiKey: "",
      clearApiKey: false,
      message: ""
    });
  } catch (error) {
    state.ai.loaded = true;
    state.ai.message = error.message;
  }
  paintAiConfigPanel();
}

async function saveAiSettings() {
  saveAiChoice();
  const ai = state.ai;
  ai.saving = true;
  ai.message = "";
  paintAiConfigPanel();
  try {
    const settings = await api("/api/management/ai", {
      method: "POST",
      body: JSON.stringify({
        providerName: ai.providerName,
        baseUrl: ai.baseUrl,
        model: ai.model,
        apiKey: ai.apiKey,
        orgId: ai.orgId,
        clearApiKey: ai.clearApiKey
      })
    });
    Object.assign(state.ai, settings, {
      saving: false,
      apiKey: "",
      clearApiKey: false,
      message: "Saved."
    });
  } catch (error) {
    ai.saving = false;
    ai.message = error.message;
  }
  paintAiConfigPanel();
}

function paintAssist() {
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
    host.innerHTML = `
      <div class="detail-head"><h2>Manifest</h2></div>
      <p class="muted">Select a resource to see its manifest and actions.</p>`;
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
    <div class="detail-head"><h2>${esc(resource.kind)}</h2><div class="detail-tools">${chip(resource.status)}${String(detail.yaml || "").trim() ? copyButton("Copy manifest", "yaml") : ""}</div></div>
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
  syncCopyButtons();
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
    syncCopyButtons();
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

// The Management tab's Resources, Namespaces, and Clusters sub-tabs each
// show only their own panel, so each paints independently and bails out
// immediately when its elements aren't the one currently on screen.
async function paintManagement() {
  await paintManagedResources();
  await paintManagedNamespaces();
  await paintManagedClusters();
}

async function paintManagedResources() {
  const resourceHost = document.getElementById("manage-resources");
  if (!resourceHost) return;
  const kindSelect = document.getElementById("manage-kind");
  if (kindSelect) kindSelect.disabled = !state.clusterId;
  let resources = [];
  if (state.clusterId && !namespaceScopeEmpty()) {
    resources = await api("/api/resources?kind=" + encodeURIComponent(state.management.kind || "deployments") + "&" + clusterQuery() + namespaceQuery());
  }
  if (setHTML(resourceHost, resources.length ? resources.map((resource) => `
    <div class="cluster-row">
      <div><strong>${esc(managedWhere(resource))}</strong><div class="muted">${chip(resource.status)} ${esc(resource.summary)}</div></div>
      <div class="inline">
        <button type="button" data-edit-kind="${esc(resource.kind)}" data-edit-namespace="${esc(resource.namespace)}" data-edit-name="${esc(resource.name)}">Edit</button>
        <button type="button" class="danger" data-remove-kind="${esc(resource.kind)}" data-remove-namespace="${esc(resource.namespace)}" data-remove-name="${esc(resource.name)}">Delete</button>
      </div>
    </div>`).join("") : `<p class="muted">${state.clusterId ? "No resources in the selected namespaces." : "Add a cluster before editing resources."}</p>`)) {
    resourceHost.querySelectorAll("[data-edit-kind]").forEach((button) => {
      button.addEventListener("click", () => editManagedResource(button.dataset.editKind, button.dataset.editNamespace, button.dataset.editName));
    });
    resourceHost.querySelectorAll("[data-remove-kind]").forEach((button) => {
      button.addEventListener("click", () => deleteManagedResource(button.dataset.removeKind, button.dataset.removeNamespace, button.dataset.removeName));
    });
  }
  const selected = state.clusters.find((cluster) => cluster.id === state.clusterId);
  const kindLabel = (MANAGE_KINDS.find(([value]) => value === state.management.kind) || MANAGE_KINDS[0])[1];
  document.getElementById("counts").textContent = selected
    ? selected.name + " · " + resources.length + " " + kindLabel.toLowerCase()
    : "No cluster selected";
}

async function paintManagedNamespaces() {
  const namespaceHost = document.getElementById("namespace-rows");
  if (!namespaceHost) return;
  const clusterHost = document.getElementById("manage-cluster");
  const selected = state.clusters.find((cluster) => cluster.id === state.clusterId);
  if (clusterHost) {
    clusterHost.textContent = selected
      ? selected.name + " · " + selected.server
      : "Add a cluster before creating namespaces.";
  }
  let details = [];
  if (state.clusterId) {
    details = await api("/api/namespace-details?" + clusterQuery());
    if (!namespaceScopeEmpty()) {
      details = details.filter((item) => state.selectedNamespaces.has(item.name));
    } else {
      details = [];
    }
  }
  if (setHTML(namespaceHost, details.length ? details.map((item) => `
    <div class="cluster-row">
      <div><strong>${esc(item.name)}</strong><div class="muted">${chip(item.status)}</div></div>
      ${item.deletable && item.status !== "Terminating"
        ? `<button type="button" class="danger" data-delete-namespace="${esc(item.name)}">Delete</button>`
        : `<span class="muted">${item.deletable ? "Removing" : "System"}</span>`}
    </div>`).join("") : `<p class="muted">${state.clusterId ? "No namespaces selected." : "No cluster selected."}</p>`)) {
    namespaceHost.querySelectorAll("[data-delete-namespace]").forEach((button) => {
      button.addEventListener("click", () => deleteNamespace(button.dataset.deleteNamespace));
    });
  }
  const form = document.getElementById("namespace-form");
  if (form) {
    form.querySelector("button").disabled = !state.clusterId;
  }
  document.getElementById("counts").textContent = selected
    ? selected.name + " · " + details.length + " namespace" + (details.length === 1 ? "" : "s")
    : "No cluster selected";
}

async function paintManagedClusters() {
  const clusterRows = document.getElementById("cluster-rows");
  if (!clusterRows) return;
  if (setHTML(clusterRows, state.clusters.length ? state.clusters.map((cluster) => `
    <div class="cluster-row">
      <div><strong>${esc(cluster.name)}</strong><div class="muted">${esc(cluster.server)} · ${esc(cluster.source)}${cluster.id === state.clusterId ? " · selected" : ""}</div></div>
      <div class="inline">
        <button type="button" data-activate="${esc(cluster.id)}">Use</button>
        ${cluster.demo ? "" : `<button type="button" class="danger" data-delete="${esc(cluster.id)}">Remove</button>`}
      </div>
    </div>`).join("") : `<p class="muted">No clusters.</p>`)) {
    clusterRows.querySelectorAll("[data-activate]").forEach((button) => {
      button.addEventListener("click", () => activateCluster(button.dataset.activate));
    });
    clusterRows.querySelectorAll("[data-delete]").forEach((button) => {
      button.addEventListener("click", () => removeCluster(button.dataset.delete));
    });
  }
  document.getElementById("counts").textContent = state.clusters.length + " cluster" + (state.clusters.length === 1 ? "" : "s");
}

function paintLdapPanel() {
  const host = document.getElementById("ldap-panel");
  if (!host) return;
  if (setHTML(host, renderLdapPanel(state.management.ldap))) {
    wireLdapPanel();
  }
}

function renderLdapPanel(ldap) {
  return `
    <h2>LDAP login</h2>
    <p class="muted">Defaults come from the server's application.yml / environment variables. Saving here writes an override - the bind password encrypted - under the server's data directory, and it applies on the next login attempt. Turning login on or off needs a restart.</p>
    ${ldap.restartRequired ? `<p class="notice">Restart the dashboard for the login on/off change to take effect.</p>` : ""}
    <label class="inline"><input id="ldap-enabled" type="checkbox" ${ldap.enabled ? "checked" : ""}> Require Active Directory login</label>
    <label>Host <input id="ldap-host" placeholder="acct01.us.lmco.com" autocomplete="off" value="${esc(ldap.host)}"></label>
    <label>Port <input id="ldap-port" type="number" min="1" max="65535" value="${esc(ldap.port)}"></label>
    <label>Default domain <input id="ldap-domain" placeholder="us" autocomplete="off" value="${esc(ldap.defaultDomain)}"></label>
    <label>Search base DN <input id="ldap-base-dn" placeholder="dc=us,dc=lmco,dc=com" autocomplete="off" value="${esc(ldap.searchBaseDn)}"></label>
    <label>Access group (AD group CN) <input id="ldap-group" placeholder="K8sDashboardUsers" autocomplete="off" value="${esc(ldap.group)}"></label>
    <label>Bind username <input id="ldap-bind-username" placeholder="svc-dashboard" autocomplete="off" value="${esc(ldap.bindUsername)}"></label>
    <label>Bind password <span class="muted">${ldap.hasBindPassword ? "(a password is set)" : "(none set)"}</span>
      <div class="secret">
        <input id="ldap-bind-password" type="password" autocomplete="new-password" placeholder="${ldap.hasBindPassword ? "Leave blank to keep the current password" : ""}" value="${esc(ldap.bindPassword)}">
        <button type="button" id="ldap-bind-password-toggle" aria-label="Show bind password" aria-pressed="false">
          <svg class="eye-on" viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12z"/><circle cx="12" cy="12" r="2.5"/></svg>
          <svg class="eye-off" viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M3 3l18 18"/><path d="M10.5 6.2A10.6 10.6 0 0 1 12 6c6.5 0 10 6 10 6a18 18 0 0 1-3.1 3.7"/><path d="M6.2 6.8C3.9 8.4 2 12 2 12s3.5 6 10 6c1.1 0 2.2-.2 3.2-.6"/><path d="M9.9 9.9a2.5 2.5 0 0 0 3.6 3.6"/></svg>
        </button>
      </div>
    </label>
    <label class="inline"><input id="ldap-clear-password" type="checkbox" ${ldap.clearBindPassword ? "checked" : ""} ${ldap.hasBindPassword ? "" : "disabled"}> Clear the stored bind password</label>
    <div class="actions">
      <button class="primary" id="ldap-save" type="button" ${ldap.saving ? "disabled" : ""}>${ldap.saving ? "Saving…" : "Save"}</button>
    </div>
    <p id="ldap-message" class="muted">${esc(ldap.message)}</p>`;
}

function wireLdapPanel() {
  const field = (id, key, parse) => {
    const input = document.getElementById(id);
    if (!input) return;
    input.addEventListener("input", (event) => {
      state.management.ldap[key] = parse ? parse(event.target.value) : event.target.value;
    });
  };
  const enabledInput = document.getElementById("ldap-enabled");
  if (enabledInput) {
    enabledInput.addEventListener("change", (event) => {
      state.management.ldap.enabled = event.target.checked;
    });
  }
  field("ldap-host", "host");
  field("ldap-port", "port", (value) => parseInt(value, 10) || 0);
  field("ldap-domain", "defaultDomain");
  field("ldap-base-dn", "searchBaseDn");
  field("ldap-group", "group");
  field("ldap-bind-username", "bindUsername");
  field("ldap-bind-password", "bindPassword");
  const clearInput = document.getElementById("ldap-clear-password");
  if (clearInput) {
    clearInput.addEventListener("change", (event) => {
      state.management.ldap.clearBindPassword = event.target.checked;
    });
  }
  const toggle = document.getElementById("ldap-bind-password-toggle");
  if (toggle) {
    toggle.addEventListener("click", () => {
      const input = document.getElementById("ldap-bind-password");
      if (!input) return;
      const show = input.type === "password";
      input.type = show ? "text" : "password";
      toggle.setAttribute("aria-pressed", show ? "true" : "false");
      toggle.setAttribute("aria-label", show ? "Hide bind password" : "Show bind password");
    });
  }
  const saveButton = document.getElementById("ldap-save");
  if (saveButton) saveButton.addEventListener("click", saveLdapSettings);
}

async function loadLdapSettings() {
  try {
    const settings = await api("/api/management/ldap");
    Object.assign(state.management.ldap, settings, {
      loaded: true,
      saving: false,
      bindPassword: "",
      clearBindPassword: false,
      message: ""
    });
  } catch (error) {
    state.management.ldap.message = error.message;
  }
  paintLdapPanel();
}

async function saveLdapSettings() {
  const ldap = state.management.ldap;
  ldap.saving = true;
  ldap.message = "";
  paintLdapPanel();
  try {
    const settings = await api("/api/management/ldap", {
      method: "POST",
      body: JSON.stringify({
        enabled: ldap.enabled,
        host: ldap.host,
        port: ldap.port,
        defaultDomain: ldap.defaultDomain,
        searchBaseDn: ldap.searchBaseDn,
        group: ldap.group,
        bindUsername: ldap.bindUsername,
        bindPassword: ldap.bindPassword,
        clearBindPassword: ldap.clearBindPassword
      })
    });
    Object.assign(state.management.ldap, settings, {
      saving: false,
      bindPassword: "",
      clearBindPassword: false,
      message: settings.restartRequired
        ? "Saved. Restart the dashboard for the login on/off change to take effect."
        : "Saved."
    });
  } catch (error) {
    ldap.saving = false;
    ldap.message = error.message;
  }
  paintLdapPanel();
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

function connectLive() {
  if (state.source) state.source.close();
  // EventSource always sends the browser's cookies for a same-origin URL,
  // so the session cookie that authenticates fetch() calls covers this too.
  const source = new EventSource("/api/live");
  // The server's own "tick" is ignored here: the client drives its own refresh
  // cadence (see startLiveTimer) so the selectable interval actually takes effect.
  // "changed" means something really did change, so it can jump the queue - but
  // on a busy cluster these can arrive far faster than the chosen interval, so
  // they're throttled to it too (otherwise the dropdown would have no visible
  // effect: a chatty cluster's "changed" events would just dominate instead).
  source.addEventListener("changed", () => {
    if (!state.live || document.hidden || state.refreshInterval === REFRESH_MANUAL) return;
    const now = Date.now();
    if (now - state.lastAutoRefresh < state.refreshInterval) return;
    state.lastAutoRefresh = now;
    window.clearTimeout(state.timer);
    state.timer = window.setTimeout(refresh, 200);
  });
  state.source = source;
}

function goToLogin() {
  window.location.href = "/login.html";
}

async function boot() {
  await loadClusters();
  // No real kubeconfig has been added yet - only the demo cluster (or nothing) is
  // available - so start the user on Management, focused on where to add one,
  // instead of a resource view with nothing but demo data to show.
  if (state.clusters.every((cluster) => cluster.demo)) {
    state.tab = "management";
    state.management.tab = "clusters";
    state.management.focusClusterName = true;
  }
  if (state.clusterId) await loadNamespaces(true);
  await refresh();
  connectLive();
  startLiveTimer();
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
  if (workspace) workspace.classList.toggle(spec.className, collapsed);
  if (button) {
    button.setAttribute("aria-expanded", collapsed ? "false" : "true");
    button.setAttribute("aria-label", collapsed ? spec.show : spec.hide);
  }
  if (persist) {
    try {
      sessionStorage.setItem(spec.key, collapsed ? "1" : "0");
    } catch (error) {
      /* Private browsing can reject sessionStorage. The panel still toggles. */
    }
  }
  layoutPanels();
}

const PANEL_LIMITS = {
  namespaces: { min: 160, max: 520, fallback: 0.18, key: "k8s-dashboard-namespaces-width", ratioKey: "k8s-dashboard-namespaces-ratio" },
  detail: { min: 240, max: 760, fallback: 0.28, key: "k8s-dashboard-detail-width", ratioKey: "k8s-dashboard-detail-ratio" }
};
const MAIN_MIN = 280;
const panelRatios = { namespaces: null, detail: null };

function installSplitters() {
  const workspace = document.getElementById("workspace");
  if (!workspace) return;
  setPanelCollapsed("namespaces", storageGet(sessionStorage, PANEL_COLLAPSE.namespaces.key) === "1", false);
  // The manifest panel defaults to collapsed until the user opts into keeping it open.
  const detailStored = storageGet(sessionStorage, PANEL_COLLAPSE.detail.key);
  setPanelCollapsed("detail", detailStored === null ? true : detailStored === "1", false);
  bindSplitter(document.getElementById("split-namespaces"), "namespaces");
  bindSplitter(document.getElementById("split-detail"), "detail");
  if (window.ResizeObserver) {
    const observer = new ResizeObserver(() => scheduleLayout());
    observer.observe(workspace);
    const stage = document.getElementById("stage");
    if (stage) observer.observe(stage);
  }
  window.addEventListener("resize", scheduleLayout);
  scheduleLayout();
}

let layoutFrame = 0;
function scheduleLayout() {
  if (layoutFrame) return;
  layoutFrame = window.requestAnimationFrame(() => {
    layoutFrame = 0;
    layoutPanels();
    layoutTerminal();
  });
}

function bindSplitter(splitter, side) {
  if (!splitter) return;
  splitter.addEventListener("pointerdown", (event) => {
    if (event.button !== 0 || stackedLayout() || panelCollapsed(side)) return;
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
      rememberPanelRatios();
    };
    splitter.addEventListener("pointermove", onMove);
    splitter.addEventListener("pointerup", onUp);
    splitter.addEventListener("pointercancel", onUp);
  });
  splitter.addEventListener("keydown", (event) => {
    if (stackedLayout() || panelCollapsed(side)) return;
    if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
    event.preventDefault();
    const step = event.shiftKey ? 48 : 16;
    const delta = event.key === "ArrowLeft" ? -step : step;
    nudgePanel(side, delta);
  });
  splitter.addEventListener("dblclick", () => {
    panelRatios[side] = PANEL_LIMITS[side].fallback;
    layoutPanels();
    rememberPanelRatios();
  });
}

function resizePanel(workspace, side, clientX) {
  const rect = workspace.getBoundingClientRect();
  const width = side === "namespaces" ? clientX - rect.left : rect.right - clientX;
  const total = rect.width || 1;
  panelRatios[side] = width / total;
  layoutPanels();
}

/* Preferred shares of the workspace stay fixed. Applied widths are whatever fits this window.
   The manifest (detail) panel floats over main as an overlay rather than sharing the grid with
   it, so only namespaces competes with main for space here - opening, closing, or resizing the
   manifest panel never changes main's width. */
function layoutPanels() {
  const workspace = document.getElementById("workspace");
  if (!workspace) return;
  syncPanelSplitter("namespaces");
  syncPanelSplitter("detail");
  if (stackedLayout()) return;
  const total = Math.round(workspace.getBoundingClientRect().width);
  if (total < 48) return;
  ensurePanelRatios(total);

  const nsOpen = !panelCollapsed("namespaces");
  const detailOpen = !panelCollapsed("detail");
  const mainFloor = Math.min(MAIN_MIN, Math.max(96, Math.round(total * 0.34)));

  let nsWidth = nsOpen ? desiredPanelWidth(total, "namespaces") : PANEL_RAIL;
  if (nsOpen) {
    const nsFloor = Math.min(PANEL_LIMITS.namespaces.min, Math.max(72, Math.round(total * 0.14)));
    const nsCeiling = Math.max(nsFloor, total - PANEL_RAIL - mainFloor);
    nsWidth = Math.min(nsWidth, nsCeiling);
  }

  const detailCeiling = Math.max(160, total - PANEL_RAIL);
  const detailWidth = detailOpen ? Math.min(desiredPanelWidth(total, "detail"), detailCeiling) : PANEL_RAIL;

  paintPanelWidth(workspace, "namespaces", nsWidth);
  paintPanelWidth(workspace, "detail", detailWidth);
}

function desiredPanelWidth(total, side) {
  const limit = PANEL_LIMITS[side];
  const raw = Math.round(total * panelRatios[side]);
  return Math.min(limit.max, Math.max(limit.min, raw));
}

function ensurePanelRatios(total) {
  ["namespaces", "detail"].forEach((side) => {
    if (panelRatios[side] != null) return;
    panelRatios[side] = storedPanelRatio(side, total);
  });
}

function storedPanelRatio(side, total) {
  const limit = PANEL_LIMITS[side];
  const savedRatio = Number(storageGet(sessionStorage, limit.ratioKey));
  if (savedRatio > 0.04 && savedRatio < 0.8) return savedRatio;
  const savedPx = Number(storageGet(sessionStorage, limit.key));
  if (Number.isFinite(savedPx) && savedPx > 0 && total > 0) return savedPx / total;
  return limit.fallback;
}

function paintPanelWidth(workspace, side, width) {
  const property = side === "namespaces" ? "--namespaces-width" : "--detail-width";
  const rounded = Math.round(width);
  workspace.style.setProperty(property, rounded + "px");
  const splitter = document.getElementById(side === "namespaces" ? "split-namespaces" : "split-detail");
  if (!splitter) return;
  const limit = PANEL_LIMITS[side];
  splitter.setAttribute("aria-valuemin", String(limit.min));
  splitter.setAttribute("aria-valuemax", String(limit.max));
  splitter.setAttribute("aria-valuenow", String(rounded));
}

function appliedPanelWidth(side) {
  const workspace = document.getElementById("workspace");
  const property = side === "namespaces" ? "--namespaces-width" : "--detail-width";
  const value = workspace ? parseFloat(workspace.style.getPropertyValue(property)) : NaN;
  return Number.isFinite(value) ? value : PANEL_LIMITS[side].min;
}

function rememberPanelRatios() {
  const workspace = document.getElementById("workspace");
  const total = workspace ? workspace.getBoundingClientRect().width : 0;
  if (total <= 0) return;
  ["namespaces", "detail"].forEach((side) => {
    if (panelCollapsed(side)) return;
    panelRatios[side] = appliedPanelWidth(side) / total;
    storageSet(sessionStorage, PANEL_LIMITS[side].ratioKey, String(Math.round(panelRatios[side] * 1000) / 1000));
  });
}

function nudgePanel(side, delta) {
  const workspace = document.getElementById("workspace");
  if (!workspace) return;
  const total = workspace.getBoundingClientRect().width || 1;
  const current = appliedPanelWidth(side);
  const next = side === "namespaces" ? current + delta : current - delta;
  panelRatios[side] = next / total;
  layoutPanels();
  rememberPanelRatios();
}

function syncPanelSplitter(side) {
  const splitter = document.getElementById(PANEL_COLLAPSE[side].splitter);
  if (!splitter) return;
  const collapsed = panelCollapsed(side) || stackedLayout();
  splitter.hidden = collapsed;
  splitter.tabIndex = collapsed ? -1 : 0;
}

function stackedLayout() {
  return window.matchMedia("(max-width: 980px)").matches;
}

const TERMINAL_COLLAPSED = "k8s-dashboard-terminal-collapsed";
const TERMINAL_HEIGHT = "k8s-dashboard-terminal-height";
const TERMINAL_RATIO_KEY = "k8s-dashboard-terminal-ratio";
const TERMINAL_HISTORY = "k8s-dashboard-terminal-history";
const TERMINAL_MIN = 180;
const TERMINAL_MAIN_MIN = 180;
let terminalHeight = 320;
let terminalRatio = null;

function commandNamespace() {
  return state.selectedNamespaces.size === 1 ? [...state.selectedNamespaces][0] : "";
}

function paintTerminalContext() {
  const context = document.getElementById("terminal-context");
  if (!context) return;
  const cluster = state.clusters.find((item) => item.id === state.clusterId);
  const name = cluster ? cluster.name : "No cluster";
  const namespace = commandNamespace();
  if (namespace) {
    context.textContent = name + " · -n " + namespace;
    return;
  }
  if (state.namespaces.length > 0 && state.selectedNamespaces.size === state.namespaces.length) {
    context.textContent = name + " · all namespaces";
    return;
  }
  context.textContent = name + " · pick one namespace to add -n";
}

function terminalCollapsed() {
  const stage = document.getElementById("stage");
  return Boolean(stage && stage.classList.contains("terminal-collapsed"));
}

function setTerminalCollapsed(collapsed, persist) {
  const stage = document.getElementById("stage");
  const button = document.getElementById("terminal-collapse");
  const splitter = document.getElementById("split-terminal");
  if (stage) stage.classList.toggle("terminal-collapsed", collapsed);
  if (button) {
    button.setAttribute("aria-expanded", collapsed ? "false" : "true");
    button.setAttribute("aria-label", collapsed ? "Show commands" : "Hide commands");
  }
  if (splitter) {
    splitter.hidden = collapsed || stackedLayout();
    splitter.tabIndex = collapsed ? -1 : 0;
  }
  if (persist) storageSet(sessionStorage, TERMINAL_COLLAPSED, collapsed ? "1" : "0");
  if (!collapsed && persist) {
    const input = document.getElementById("terminal-input");
    if (input) input.focus();
  }
  layoutTerminal();
}

function layoutTerminal() {
  const stage = document.getElementById("stage");
  const splitter = document.getElementById("split-terminal");
  const collapsed = terminalCollapsed() || stackedLayout();
  if (splitter) {
    splitter.hidden = collapsed;
    splitter.tabIndex = collapsed ? -1 : 0;
  }
  if (!stage || collapsed) return;
  const total = Math.round(stage.getBoundingClientRect().height);
  if (total < 48) return;
  ensureTerminalRatio(total);
  const bounds = terminalBounds(total);
  let height = Math.round(total * terminalRatio);
  height = Math.min(Math.max(height, bounds.min), bounds.max);
  if (height > total - 56) height = Math.max(56, total - 56);
  paintTerminalHeight(stage, height, bounds);
}

function terminalBounds(total) {
  const min = Math.min(TERMINAL_MIN, Math.max(88, Math.round(total * 0.2)));
  const main = Math.min(TERMINAL_MAIN_MIN, Math.max(96, Math.round(total * 0.28)));
  return { min, max: Math.max(min, total - main) };
}

function ensureTerminalRatio(total) {
  if (terminalRatio != null) return;
  const savedRatio = Number(storageGet(sessionStorage, TERMINAL_RATIO_KEY));
  if (savedRatio > 0.08 && savedRatio < 0.92) {
    terminalRatio = savedRatio;
    return;
  }
  const savedPx = Number(storageGet(sessionStorage, TERMINAL_HEIGHT));
  if (Number.isFinite(savedPx) && savedPx > 0 && total > 0) {
    terminalRatio = savedPx / total;
    return;
  }
  terminalRatio = 0.5;
}

function paintTerminalHeight(stage, height, bounds) {
  terminalHeight = Math.round(height);
  stage.style.setProperty("--terminal-height", terminalHeight + "px");
  const splitter = document.getElementById("split-terminal");
  if (!splitter) return;
  splitter.setAttribute("aria-valuemin", String(bounds.min));
  splitter.setAttribute("aria-valuemax", String(bounds.max));
  splitter.setAttribute("aria-valuenow", String(terminalHeight));
}

function rememberTerminalRatio() {
  const stage = document.getElementById("stage");
  const total = stage ? stage.getBoundingClientRect().height : 0;
  if (total <= 0) return;
  terminalRatio = terminalHeight / total;
  storageSet(sessionStorage, TERMINAL_RATIO_KEY, String(Math.round(terminalRatio * 1000) / 1000));
}

function installTerminal() {
  const stage = document.getElementById("stage");
  if (!stage) return;
  // The commands panel defaults to collapsed until the user opts into keeping it open.
  const terminalStored = storageGet(sessionStorage, TERMINAL_COLLAPSED);
  setTerminalCollapsed(terminalStored === null ? true : terminalStored === "1", false);
  try {
    const saved = JSON.parse(storageGet(sessionStorage, TERMINAL_HISTORY) || "[]");
    state.terminal.history = Array.isArray(saved) ? saved.filter((item) => typeof item === "string") : [];
  } catch (error) {
    state.terminal.history = [];
  }
  state.terminal.cursor = state.terminal.history.length;
  bindTerminalSplitter();
  bindTerminalInput();
  paintTerminalContext();
  layoutTerminal();
}

function bindTerminalSplitter() {
  const splitter = document.getElementById("split-terminal");
  if (!splitter) return;
  splitter.addEventListener("pointerdown", (event) => {
    if (event.button !== 0 || stackedLayout() || terminalCollapsed()) return;
    event.preventDefault();
    splitter.classList.add("dragging");
    document.body.classList.add("resizing-row");
    if (splitter.setPointerCapture) splitter.setPointerCapture(event.pointerId);
    const onMove = (move) => {
      const rect = document.getElementById("stage").getBoundingClientRect();
      const total = rect.height || 1;
      terminalRatio = (rect.bottom - move.clientY) / total;
      layoutTerminal();
    };
    const onUp = () => {
      splitter.classList.remove("dragging");
      document.body.classList.remove("resizing-row");
      splitter.removeEventListener("pointermove", onMove);
      splitter.removeEventListener("pointerup", onUp);
      splitter.removeEventListener("pointercancel", onUp);
      rememberTerminalRatio();
    };
    splitter.addEventListener("pointermove", onMove);
    splitter.addEventListener("pointerup", onUp);
    splitter.addEventListener("pointercancel", onUp);
  });
  splitter.addEventListener("keydown", (event) => {
    if (stackedLayout() || terminalCollapsed()) return;
    if (event.key !== "ArrowUp" && event.key !== "ArrowDown") return;
    event.preventDefault();
    const stage = document.getElementById("stage");
    const total = stage ? stage.getBoundingClientRect().height : 1;
    const step = event.shiftKey ? 48 : 16;
    const delta = event.key === "ArrowUp" ? step : -step;
    terminalRatio = (terminalHeight + delta) / (total || 1);
    layoutTerminal();
    rememberTerminalRatio();
  });
  splitter.addEventListener("dblclick", () => {
    terminalRatio = 0.5;
    layoutTerminal();
    rememberTerminalRatio();
  });
}

function bindTerminalInput() {
  const form = document.getElementById("terminal-form");
  const input = document.getElementById("terminal-input");
  const stop = document.getElementById("terminal-stop");
  if (!form || !input) return;
  form.addEventListener("submit", (event) => {
    event.preventDefault();
    runCommand(input.value);
  });
  input.addEventListener("input", () => {
    state.terminal.cursor = state.terminal.history.length;
    hideSuggestions();
    scheduleComplete();
  });
  input.addEventListener("focus", scheduleComplete);
  input.addEventListener("keydown", onTerminalKey);
  input.addEventListener("blur", () => {
    window.setTimeout(hideSuggestions, 150);
  });
  if (stop) stop.addEventListener("click", stopCommand);
  document.getElementById("terminal-collapse").addEventListener("click", () => {
    setTerminalCollapsed(!terminalCollapsed(), true);
  });
  document.getElementById("terminal-suggestions").addEventListener("mousedown", (event) => {
    const item = event.target.closest("li");
    if (!item || item.dataset.index == null) return;
    event.preventDefault();
    acceptSuggestion(Number(item.dataset.index));
  });
}

function onTerminalKey(event) {
  const suggestions = state.terminal.suggestions;
  if (event.key === "Escape") {
    if (suggestions.length) {
      event.preventDefault();
      hideSuggestions();
    } else if (state.terminal.busy) {
      event.preventDefault();
      stopCommand();
    }
    return;
  }
  if ((event.key === "c" || event.key === "C") && event.ctrlKey && state.terminal.busy) {
    event.preventDefault();
    stopCommand();
    return;
  }
  if (event.key === "ArrowDown" || event.key === "ArrowUp") {
    if (suggestions.length && event.target.value) {
      event.preventDefault();
      const delta = event.key === "ArrowDown" ? 1 : -1;
      state.terminal.active = (state.terminal.active + delta + suggestions.length) % suggestions.length;
      paintSuggestions();
      return;
    }
    event.preventDefault();
    hideSuggestions();
    recallHistory(event.key === "ArrowUp" ? -1 : 1);
    return;
  }
  if (event.key === "Tab" || (event.key === "ArrowRight" && atEnd(event.target) && ghostRest())) {
    if (!suggestions.length) return;
    event.preventDefault();
    acceptSuggestion(state.terminal.active);
  }
}

function atEnd(input) {
  return input.selectionStart === input.value.length && input.selectionEnd === input.value.length;
}

function ghostRest() {
  const ghost = document.getElementById("terminal-ghost");
  return Boolean(ghost && ghost.querySelector(".rest") && ghost.querySelector(".rest").textContent);
}

function scheduleComplete() {
  window.clearTimeout(state.terminal.timer);
  state.terminal.timer = window.setTimeout(completeCommand, 80);
}

async function completeCommand() {
  const input = document.getElementById("terminal-input");
  if (!input || terminalCollapsed()) return;
  const seq = ++state.terminal.runComplete;
  if (state.terminal.completeAbort) state.terminal.completeAbort.abort();
  const abort = new AbortController();
  state.terminal.completeAbort = abort;
  const line = input.value;
  const cursor = input.selectionStart || 0;
  try {
    const payload = await api("/api/commands/complete?" + clusterQuery(), {
      method: "POST",
      body: JSON.stringify({ line, cursor, namespace: commandNamespace() }),
      signal: abort.signal
    });
    if (seq !== state.terminal.runComplete || input.value !== line) return;
    state.terminal.line = line;
    state.terminal.suggestions = payload.suggestions || [];
    state.terminal.active = 0;
    state.terminal.replaceFrom = payload.replaceFrom || 0;
    state.terminal.replaceTo = payload.replaceTo || 0;
    state.terminal.hint = payload.hint || "";
    state.terminal.usage = payload.usage || "";
    paintSuggestions();
  } catch (error) {
    if (error.name === "AbortError") return;
    if (seq !== state.terminal.runComplete || input.value !== line) return;
    state.terminal.suggestions = [];
    state.terminal.hint = error.message;
    state.terminal.usage = "";
    paintSuggestions();
  }
}

function paintSuggestions() {
  const list = document.getElementById("terminal-suggestions");
  const input = document.getElementById("terminal-input");
  const hint = document.getElementById("terminal-hint");
  const usage = document.getElementById("terminal-usage");
  if (!list || !input) return;
  const items = state.terminal.suggestions;
  const current = items[state.terminal.active];
  if (hint) {
    const flagHint = current && (current.kind === "command" || current.kind === "flag" || current.kind === "value")
      ? current.detail
      : "";
    hint.textContent = flagHint || state.terminal.hint;
  }
  if (usage) usage.textContent = state.terminal.usage;
  list.innerHTML = "";
  if (!items.length) {
    list.hidden = true;
    input.setAttribute("aria-expanded", "false");
    paintGhost();
    return;
  }
  items.forEach((item, index) => {
    const row = document.createElement("li");
    row.dataset.index = String(index);
    row.setAttribute("role", "option");
    row.id = "terminal-option-" + index;
    if (index === state.terminal.active) row.className = "active";
    row.setAttribute("aria-selected", index === state.terminal.active ? "true" : "false");
    const value = document.createElement("span");
    value.textContent = item.value;
    const detail = document.createElement("span");
    detail.textContent = item.detail || "";
    row.append(value, detail);
    list.append(row);
  });
  list.hidden = false;
  input.setAttribute("aria-expanded", "true");
  input.setAttribute("aria-activedescendant", "terminal-option-" + state.terminal.active);
  const active = list.querySelector(".active");
  if (active && active.scrollIntoView) active.scrollIntoView({ block: "nearest" });
  paintGhost();
}

function paintGhost() {
  const ghost = document.getElementById("terminal-ghost");
  const input = document.getElementById("terminal-input");
  if (!ghost || !input) return;
  ghost.replaceChildren();
  const suggestion = state.terminal.suggestions[state.terminal.active];
  if (!suggestion || input.selectionStart !== input.value.length) return;
  const token = input.value.slice(state.terminal.replaceFrom);
  if (!suggestion.value.toLowerCase().startsWith(token.toLowerCase())) return;
  const rest = suggestion.value.slice(token.length);
  if (!rest) return;
  const typed = document.createElement("span");
  typed.className = "typed";
  typed.textContent = input.value;
  const more = document.createElement("span");
  more.className = "rest";
  more.textContent = rest;
  ghost.append(typed, more);
}

function hideSuggestions() {
  state.terminal.suggestions = [];
  const list = document.getElementById("terminal-suggestions");
  const input = document.getElementById("terminal-input");
  if (list) list.hidden = true;
  if (input) input.setAttribute("aria-expanded", "false");
  paintGhost();
}

function acceptSuggestion(index) {
  const input = document.getElementById("terminal-input");
  const suggestion = state.terminal.suggestions[index];
  if (!input || !suggestion || input.value !== state.terminal.line) return;
  const insert = suggestion.value + (suggestion.value.endsWith("=") ? "" : " ");
  const from = state.terminal.replaceFrom;
  const to = Math.max(from, state.terminal.replaceTo);
  input.value = input.value.slice(0, from) + insert + input.value.slice(to);
  const caret = from + insert.length;
  input.setSelectionRange(caret, caret);
  hideSuggestions();
  input.focus();
  scheduleComplete();
}

// Reusable Inline + List prediction for any plain text input that picks a
// value from a known set (namespaces, deployments, pods, containers). Wraps
// the input with a ghost-text overlay (Tab / ArrowRight-at-end accepts it,
// mirroring the terminal) and a dropdown list (Up/Down to move, Enter or a
// click to accept, Escape to dismiss).
function attachPrediction(input, fetchSuggestions) {
  if (!input || input.dataset.predictAttached) return;
  input.dataset.predictAttached = "true";

  const wrap = document.createElement("div");
  wrap.className = "predict-wrap";
  input.replaceWith(wrap);
  const ghost = document.createElement("div");
  ghost.className = "predict-ghost";
  ghost.setAttribute("aria-hidden", "true");
  const list = document.createElement("ul");
  list.className = "predict-list";
  list.setAttribute("role", "listbox");
  list.hidden = true;
  list.id = (input.id || "predict") + "-list";
  wrap.append(ghost, input, list);

  input.setAttribute("role", "combobox");
  input.setAttribute("aria-autocomplete", "list");
  input.setAttribute("aria-expanded", "false");
  input.setAttribute("aria-controls", list.id);

  const local = { items: [], active: 0, timer: null, abort: null, seq: 0 };

  function paintGhostLocal() {
    ghost.replaceChildren();
    const suggestion = local.items[local.active];
    const value = input.value;
    if (!suggestion || !value || input.selectionStart !== value.length) return;
    if (!suggestion.toLowerCase().startsWith(value.toLowerCase())) return;
    const rest = suggestion.slice(value.length);
    if (!rest) return;
    const typed = document.createElement("span");
    typed.className = "typed";
    typed.textContent = value;
    const more = document.createElement("span");
    more.className = "rest";
    more.textContent = rest;
    ghost.append(typed, more);
  }

  function hide() {
    local.items = [];
    list.hidden = true;
    input.setAttribute("aria-expanded", "false");
    paintGhostLocal();
  }

  function paint() {
    list.innerHTML = "";
    if (!local.items.length) {
      hide();
      return;
    }
    local.items.forEach((value, index) => {
      const row = document.createElement("li");
      row.dataset.index = String(index);
      row.setAttribute("role", "option");
      row.id = list.id + "-" + index;
      if (index === local.active) row.className = "active";
      row.setAttribute("aria-selected", index === local.active ? "true" : "false");
      row.textContent = value;
      list.append(row);
    });
    list.hidden = false;
    input.setAttribute("aria-expanded", "true");
    input.setAttribute("aria-activedescendant", list.id + "-" + local.active);
    paintGhostLocal();
  }

  function accept(index) {
    const value = local.items[index];
    if (value == null) return;
    input.value = value;
    hide();
    input.focus();
    input.dispatchEvent(new Event("input", { bubbles: true }));
    input.dispatchEvent(new Event("change", { bubbles: true }));
  }

  async function run() {
    const query = input.value;
    const seq = ++local.seq;
    if (local.abort) local.abort.abort();
    const abort = new AbortController();
    local.abort = abort;
    let items;
    try {
      items = await fetchSuggestions(query, abort.signal);
    } catch (error) {
      if (error.name === "AbortError") return;
      items = [];
    }
    if (seq !== local.seq || document.activeElement !== input) return;
    const lower = query.toLowerCase();
    local.items = [...new Set((items || []).filter(Boolean))].filter((value) => value.toLowerCase() !== lower);
    local.active = 0;
    paint();
  }

  function schedule() {
    window.clearTimeout(local.timer);
    local.timer = window.setTimeout(run, 120);
  }

  input.addEventListener("input", () => {
    hide();
    schedule();
  });
  input.addEventListener("focus", schedule);
  input.addEventListener("blur", () => window.setTimeout(hide, 150));
  input.addEventListener("keydown", (event) => {
    const items = local.items;
    if (event.key === "Escape") {
      if (items.length) {
        event.preventDefault();
        hide();
      }
      return;
    }
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      if (!items.length) return;
      event.preventDefault();
      const delta = event.key === "ArrowDown" ? 1 : -1;
      local.active = (local.active + delta + items.length) % items.length;
      paint();
      return;
    }
    if (event.key === "Tab" || (event.key === "ArrowRight" && atEnd(input) && ghost.querySelector(".rest"))) {
      if (!items.length) return;
      event.preventDefault();
      accept(local.active);
    }
  });
  list.addEventListener("mousedown", (event) => {
    const item = event.target.closest("li");
    if (!item || item.dataset.index == null) return;
    event.preventDefault();
    accept(Number(item.dataset.index));
  });
}

function recallHistory(delta) {
  const input = document.getElementById("terminal-input");
  const history = state.terminal.history;
  if (!input || !history.length) return;
  if (state.terminal.cursor === history.length) state.terminal.draft = input.value;
  const next = Math.min(history.length, Math.max(0, state.terminal.cursor + delta));
  state.terminal.cursor = next;
  input.value = next === history.length ? state.terminal.draft : history[next];
  input.setSelectionRange(input.value.length, input.value.length);
  scheduleComplete();
}

function rememberHistory(command) {
  const history = state.terminal.history.filter((item) => item !== command);
  history.push(command);
  while (history.length > 40) history.shift();
  state.terminal.history = history;
  state.terminal.cursor = history.length;
  state.terminal.draft = "";
  storageSet(sessionStorage, TERMINAL_HISTORY, JSON.stringify(history));
}

function setCommandBusy(busy) {
  state.terminal.busy = busy;
  const run = document.getElementById("terminal-run");
  const stop = document.getElementById("terminal-stop");
  if (run) run.disabled = busy;
  if (stop) stop.hidden = !busy;
}

function appendCommand(command, stdout, stderr, meta) {
  const output = document.getElementById("terminal-output");
  if (!output) return;
  const entry = document.createElement("div");
  entry.className = "term-entry";
  const typed = document.createElement("div");
  typed.className = "term-cmd";
  typed.textContent = "$ " + command;
  entry.append(typed);
  if (stdout) {
    const out = document.createElement("pre");
    out.className = "term-out";
    out.textContent = stdout;
    entry.append(out);
  }
  if (stderr) {
    const err = document.createElement("pre");
    err.className = "term-err";
    err.textContent = stderr;
    entry.append(err);
  }
  if (!stdout && !stderr) {
    const empty = document.createElement("pre");
    empty.className = "term-out";
    empty.textContent = "(no output)";
    entry.append(empty);
  }
  if (meta) {
    const note = document.createElement("div");
    note.className = "term-meta";
    note.textContent = meta;
    entry.append(note);
  }
  output.append(entry);
  output.scrollTop = output.scrollHeight;
  syncCopyButtons();
}

async function runCommand(line) {
  const command = line.trim();
  const input = document.getElementById("terminal-input");
  if (!command || state.terminal.busy) return;
  hideSuggestions();
  rememberHistory(command);
  if (input) input.value = "";
  paintGhost();
  const id = (window.crypto && crypto.randomUUID) ? crypto.randomUUID() : String(Date.now());
  const abort = new AbortController();
  const run = ++state.terminal.run;
  state.terminal.abort = abort;
  state.terminal.commandId = id;
  setCommandBusy(true);
  const hint = document.getElementById("terminal-hint");
  if (hint) hint.textContent = "Running " + command;
  try {
    const result = await api("/api/commands?" + clusterQuery(), {
      method: "POST",
      body: JSON.stringify({ command, namespace: commandNamespace(), id }),
      signal: abort.signal
    });
    if (run !== state.terminal.run) return;
    const meta = result.timedOut
      ? "Timed out"
      : (result.exitCode ? "exit " + result.exitCode : "");
    appendCommand(result.command || command, result.stdout || "", result.stderr || "", meta);
  } catch (error) {
    if (run !== state.terminal.run) return;
    if (error.name === "AbortError") appendCommand(command, "", "Stopped.", "");
    else appendCommand(command, "", error.message, "");
  } finally {
    if (run === state.terminal.run) {
      state.terminal.abort = null;
      state.terminal.commandId = "";
      setCommandBusy(false);
      scheduleComplete();
    }
  }
}

function stopCommand() {
  const id = state.terminal.commandId;
  if (state.terminal.abort) state.terminal.abort.abort();
  if (!id) return;
  api("/api/commands/" + encodeURIComponent(id), { method: "DELETE" }).catch(() => {});
}

document.querySelector(".terminal-bar").insertAdjacentHTML("beforeend", copyButton("Copy command output", "terminal", "terminal-copy", false));
paintDetail();

function syncCopyButtons() {
  const logs = document.querySelector('#view [data-copy="logs"]');
  if (logs) {
    const output = document.getElementById("log-output");
    logs.disabled = !output || output.dataset.empty !== "false";
  }
  const edit = document.querySelector('#manage-edit [data-copy="edit-yaml"]');
  if (edit) {
    const box = document.getElementById("manage-yaml");
    edit.disabled = !box || !box.value.trim();
  }
  const terminal = document.getElementById("terminal-copy");
  if (terminal) {
    const output = document.getElementById("terminal-output");
    terminal.disabled = !output || !output.querySelector(".term-entry");
  }
}

document.addEventListener("click", (event) => {
  const button = event.target.closest(".copy-icon");
  if (!button || button.disabled) return;
  event.preventDefault();
  event.stopPropagation();
  copyFromButton(button);
}, true);

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

["query", "label", "image", "node", "status", "name"].forEach((id) => {
  document.getElementById(id).addEventListener("input", () => {
    window.clearTimeout(state.timer);
    state.timer = window.setTimeout(refresh, 250);
  });
});

attachPrediction(document.getElementById("label"), suggestLabels);
attachPrediction(document.getElementById("image"), suggestImages);
attachPrediction(document.getElementById("node"), suggestNodes);
attachPrediction(document.getElementById("status"), suggestStatuses);
attachPrediction(document.getElementById("name"), suggestNames);

document.getElementById("live").addEventListener("change", (event) => {
  state.live = event.target.checked;
  startLiveTimer();
});

applyTheme(storedTheme());
document.getElementById("theme").addEventListener("change", (event) => {
  applyTheme(event.target.value);
});

applyRefreshInterval(storedRefreshInterval());
document.getElementById("refresh-interval").addEventListener("change", (event) => {
  const value = event.target.value;
  applyRefreshInterval(value === REFRESH_MANUAL ? REFRESH_MANUAL : parseInt(value, 10));
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
installSplitters();
installTerminal();

boot().catch((error) => {
  if (error.status === 401) goToLogin();
  else notice(error.message);
});
