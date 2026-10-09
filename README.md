# Kubernetes Dashboard

A local Spring Boot dashboard for more than one Kubernetes cluster. It runs as one JAR, keeps kubeconfigs on this machine, and calls a model only when you ask.

Open `http://127.0.0.1:8080` after starting it. With no kubeconfig, the **Demo** cluster is selected so the screens have data.

## Run

```bash
./gradlew bootRun
```

Single binary:

```bash
./gradlew bootJar
java -jar build/libs/k8s-dashboard.jar
```

The JAR is the only runtime dependency to serve the dashboard itself. Java 17 runs it. To run commands from the command panel against a live cluster (see below), `kubectl` and/or `helm` must also be installed and on `PATH`.

Your `~/.kube/config` is loaded when it exists. Each context is a cluster. Paste or upload another kubeconfig in **Management**. Files are written to `~/.k8s-dashboard/clusters` with user-only permissions.

| Setting | Environment variable | Default |
| --- | --- | --- |
| LDAP login | `DASHBOARD_LDAP_ENABLED` | `false`, no login |
| LDAP host | `DASHBOARD_LDAP_HOST` | empty |
| LDAP port | `DASHBOARD_LDAP_PORT` | `3269` |
| LDAP domain (for `user@domain`) | `DASHBOARD_LDAP_DEFAULT_DOMAIN` | empty |
| LDAP search base DN | `DASHBOARD_LDAP_SEARCH_BASE_DN` | empty |
| AD group required to log in | `DASHBOARD_LDAP_GROUP` | empty |
| LDAP bind username (service account) | `DASHBOARD_LDAP_BIND_USERNAME` | empty |
| LDAP bind password | `DASHBOARD_LDAP_BIND_PASSWORD` | empty |
| Load `~/.kube/config` | `DASHBOARD_CLUSTER_LOAD_DEFAULT_KUBECONFIG` | `true` |
| Demo cluster | `DASHBOARD_CLUSTER_DEMO_ENABLED` | `true` |
| In-cluster client | `DASHBOARD_CLUSTER_IN_CLUSTER` | `false` |
| Saved kubeconfigs | `DASHBOARD_CLUSTER_DATA_DIR` | `~/.k8s-dashboard` |
| Extra kubeconfig files | `DASHBOARD_CLUSTER_KUBECONFIGS_0` | none |
| Provider | `DASHBOARD_AI_PROVIDER` | `off` |
| Model | `DASHBOARD_AI_MODEL` | empty |
| Model base URL | `DASHBOARD_AI_BASE_URL` | empty |
| Model key | `XAI_API_KEY` or `DASHBOARD_AI_API_KEY` | empty |

`DASHBOARD_AI_API_KEY` maps to `dashboard.ai.api-key`. The default property also reads `XAI_API_KEY`.

## What you can do

- Switch clusters and tick more than one namespace. Pods, deployments, services, config maps, and events group by namespace. Nodes are cluster-scoped and listed on their own tab.
- Search by name, status, label (`app=storefront`), image, or node.
- Open a manifest. Scale a deployment, roll it out again, or delete a pod so the replica set replaces it.
- Tail logs across the pods in the selected namespaces, or one deployment, pod, and container. Search and tail length are server-side.
- Port-forward a pod, or a service (the first ready pod that matches its selector). On the demo cluster this is recorded and no socket is opened. On a live cluster the port listens on the machine running the dashboard.
- Run `kubectl` or `helm` against the selected cluster from the command panel docked under the main view. Tab completes tools, resource types, flags, and live object names; Enter runs; Stop cancels a running command. The dashboard injects the selected namespace, rejects `--kubeconfig`/`--context`, and runs the binary directly (no shell). On the demo cluster only a safe read-only subset (`get`, `describe`, `api-resources`, `api-versions`, `version`, `cluster-info`, `config current-context`, and a few `helm` read commands) is answered in-memory; everything else needs a live cluster. On a live cluster, `kubectl` and/or `helm` must already be installed and on the `PATH` of the machine or container running the dashboard — the published Docker image and Helm chart do not bundle either binary, so the panel reports "not found on PATH" there until you build an image that adds them.
- Live refresh uses a server-sent event stream. The switch in the header pauses it.
- Theme follows the system appearance, or stays dark or light. The choice is kept in this browser.
- Assist sends the selected manifest and recent logs to the provider configured in Management's AI assist panel. With no base URL or API key it runs a local check and does not call out.
- Management creates and deletes namespaces, edits and deletes pods, deployments, services, and config maps, and adds, selects, or removes kubeconfigs. Saving an edit applies that manifest. `default`, `kube-system`, `kube-public`, and `kube-node-lease` cannot be deleted. Deleting a namespace removes everything in it. A deleted pod owned by a deployment is replaced.
- Management's AI assist panel edits the `dashboard.ai.*` settings below from the browser. It starts from whatever `application.yml`/environment variables set; saving writes an override to `~/.k8s-dashboard/ai-settings.yml` (user-only permissions) that takes precedence after that, including across restarts and in every browser you open the dashboard in. The API key is encrypted before it's written, with a key kept alongside it at `~/.k8s-dashboard/.secret.key` (also user-only) - this protects the file at rest, not against anyone who can read that key or run the process itself. Leaving the key field blank on save keeps whatever is already stored; a checkbox clears it.
- Management's LDAP panel edits the `DASHBOARD_LDAP_*` settings above from the browser. It starts from whatever `application.yml`/environment variables set; saving writes an override to `~/.k8s-dashboard/ldap-settings.yml` (user-only permissions) that takes precedence after that, including across restarts. The bind password is encrypted before it's written, with the same key as the AI assist panel's at `~/.k8s-dashboard/.secret.key` (also user-only) - this protects the file at rest, not against anyone who can read that key or run the process itself. Leaving the password field blank on save keeps whatever is already stored; a checkbox clears it. Every field except the login on/off switch applies on the next login attempt; flipping login on or off still needs a restart, which the panel says so.

Enter a provider name, base URL, and API key in Management's **AI assist** panel, then Save. A model name is optional; a blank model uses `dashboard.ai.model` (empty unless `DASHBOARD_AI_MODEL` is set). Saving persists the configuration under `~/.k8s-dashboard` (the API key encrypted) so it survives a restart and is picked up by every browser you open the dashboard in; the key itself is never sent back to a browser once saved. The Assist tab's Ask button uses whatever is saved.

Name the provider Devin, or set the base URL to `https://api.devin.ai/v3`, to open a short Devin session. Devin also needs the organization id from Settings, then Devin API.

```bash
./gradlew bootRun
```

Ollama on this machine: provider name `Ollama`, base URL `http://127.0.0.1:11434/v1`, model `llama3.1`, and an empty API key.

## HTTPS

Leave TLS off for localhost. For a remote browser, terminate TLS in front of the process or set Spring's server SSL properties:

```yaml
server:
  port: 8443
  ssl:
    enabled: true
    key-store: file:/path/keystore.p12
    key-store-password: ${DASHBOARD_SSL_PASSWORD}
    key-store-type: PKCS12
```

Set `DASHBOARD_LDAP_ENABLED=true` (and the other `DASHBOARD_LDAP_*` settings above) before binding beyond localhost. With LDAP on, every route except the login page and static assets requires a session from a successful Active Directory login, and that login only succeeds for members of `DASHBOARD_LDAP_GROUP`. The static shell and `/login.html` stay public so the login form itself can load; API calls return `401` without a session. Login uses a server-side session cookie, not a bearer token, so there is nothing to paste into the browser - sign in at `/login.html` with your AD username and password.

LDAPS needs the Active Directory CA trusted by the JRE - see `certs/README.md` for dropping a CA certificate into the Docker build context. Without it, logins fail with an SSL handshake error, not a credentials error.

## Docker

```bash
./gradlew dockerBuild
docker run --rm -p 8080:8080 \
  -v "$HOME/.kube/config:/kube/config:ro" \
  -e DASHBOARD_CLUSTER_KUBECONFIGS_0=/kube/config \
  -e DASHBOARD_CLUSTER_DEMO_ENABLED=false \
  -e XAI_API_KEY \
  k8s-dashboard:1.0.0-SNAPSHOT
```

`./gradlew build` builds this image too, because `assemble` depends on `dockerBuild`. The tag is `k8s-dashboard:1.0.0-SNAPSHOT`. The image runs as uid 1001. Uploaded kubeconfigs go to `/data`.

## Kubernetes

```bash
./gradlew dockerBuild
kubectl apply -f deploy/kubernetes/dashboard.yaml
kubectl -n k8s-dashboard port-forward svc/k8s-dashboard 8080:8080
```

The manifest uses the pod service account (`DASHBOARD_CLUSTER_IN_CLUSTER=true`) and turns the demo cluster off. The ClusterRole can list workloads and change or delete deployments, pods, services, config maps, and namespaces. Treat the dashboard as an admin tool.

The Service stays inside the cluster until you add one access method. Apply the Ingress or the Istio Gateway and VirtualService, not both:

```bash
kubectl apply -f deploy/kubernetes/dashboard.yaml -f deploy/kubernetes/ingress.yaml
```

```bash
kubectl apply -f deploy/kubernetes/dashboard.yaml -f deploy/kubernetes/istio.yaml
```

Both listen for `dashboard.local` and send it to port 8080. The Istio Gateway selects the ingress gateway labeled `istio: ingressgateway`.

Port-forwards opened from a pod listen on that pod, not on your laptop. Use port-forward when you run the binary locally.

Optional secrets, both keys optional in the manifest:

```bash
kubectl -n k8s-dashboard create secret generic k8s-dashboard \
  --from-literal=ldap-bind-password="$LDAP_BIND_PASSWORD" \
  --from-literal=xai-api-key="$XAI_API_KEY"
```

## Helm

`deploy/helm/k8s-dashboard` installs the same ServiceAccount, ClusterRole, Deployment, and Service. The container requests 100m CPU and 256Mi memory, with limits of 500m CPU and 512Mi memory.

```bash
./gradlew dockerBuild
helm upgrade --install k8s-dashboard deploy/helm/k8s-dashboard \
  --namespace k8s-dashboard \
  --create-namespace
kubectl -n k8s-dashboard port-forward svc/k8s-dashboard 8080:8080
```

Set the LDAP bind password and model key in the release, or leave them empty and create the Secret above. The Secret reference stays optional until one of those values is set.

```bash
helm upgrade --install k8s-dashboard deploy/helm/k8s-dashboard \
  --namespace k8s-dashboard \
  --create-namespace \
  --set ldap.enabled=true \
  --set ldap.group="K8sDashboardUsers" \
  --set ldap.bindUsername="svc-dashboard" \
  --set ldap.bindPassword="$LDAP_BIND_PASSWORD" \
  --set ai.apiKey="$XAI_API_KEY"
```

Uploaded kubeconfigs are stored on an emptyDir at `/data`. Set `persistence.enabled=true` to keep them on a PersistentVolumeClaim. The LDAP panel's saved override and its encryption key live under the same data directory, so they're subject to the same choice - on an emptyDir, saving an LDAP override from the panel does not survive a pod restart, and a new encryption key is generated each time, same as the uploaded kubeconfigs it sits next to.

Expose the Service with Ingress or Istio. The chart rejects a release that enables both.

```bash
helm upgrade --install k8s-dashboard deploy/helm/k8s-dashboard \
  --namespace k8s-dashboard \
  --create-namespace \
  --set ingress.enabled=true
```

```bash
helm upgrade --install k8s-dashboard deploy/helm/k8s-dashboard \
  --namespace k8s-dashboard \
  --create-namespace \
  --set istio.enabled=true
```

`istio.hosts` defaults to `dashboard.local`. An empty `istio.gateway` creates a Gateway for pods labeled `istio: ingressgateway`. Set `istio.gateway` to an existing `namespace/name` to attach the VirtualService to a gateway you already run.

## Privacy

Nothing phones home. Cluster data stays in this process. A model request happens only from Assist, and only when an API key is configured. The request contains the selected manifest and recent log lines, not your kubeconfig.

## Tests

```bash
./gradlew test
```

Tests use the demo cluster and do not contact a live API server.
