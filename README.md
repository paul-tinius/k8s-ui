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

The JAR is the only runtime dependency. Java 17 runs it.

Your `~/.kube/config` is loaded when it exists. Each context is a cluster. Paste or upload another kubeconfig in **Management**. Files are written to `~/.k8s-dashboard/clusters` with user-only permissions.

| Setting | Environment variable | Default |
| --- | --- | --- |
| Access token | `DASHBOARD_TOKEN` | empty, local use |
| Load `~/.kube/config` | `DASHBOARD_CLUSTER_LOAD_DEFAULT_KUBECONFIG` | `true` |
| Demo cluster | `DASHBOARD_CLUSTER_DEMO_ENABLED` | `true` |
| In-cluster client | `DASHBOARD_CLUSTER_IN_CLUSTER` | `false` |
| Saved kubeconfigs | `DASHBOARD_CLUSTER_DATA_DIR` | `~/.k8s-dashboard` |
| Extra kubeconfig files | `DASHBOARD_CLUSTER_KUBECONFIGS_0` | none |
| Provider | `DASHBOARD_AI_PROVIDER` | `off` |
| Model | `DASHBOARD_AI_MODEL` | `grok-4.7` |
| Model base URL | `DASHBOARD_AI_BASE_URL` | `https://api.x.ai/v1` |
| Model key | `XAI_API_KEY` or `DASHBOARD_AI_API_KEY` | empty |

`DASHBOARD_AI_API_KEY` maps to `dashboard.ai.api-key`. The default property also reads `XAI_API_KEY`.

## What you can do

- Switch clusters and tick more than one namespace. Pods, deployments, services, config maps, and events group by namespace.
- Search by name, status, label (`app=storefront`), image, or node.
- Open a manifest. Scale a deployment, roll it out again, or delete a pod so the replica set replaces it.
- Apply a YAML manifest.
- Tail logs across the pods in the selected namespaces, or one deployment, pod, and container. Search and tail length are server-side.
- Port-forward a pod, or a service (the first ready pod that matches its selector). On the demo cluster this is recorded and no socket is opened. On a live cluster the port listens on the machine running the dashboard.
- Live refresh uses a server-sent event stream. The switch in the header pauses it.
- Theme follows the system appearance, or stays dark or light. The choice is kept in this browser.
- Assist sends the selected manifest and recent logs to the provider entered in the Assist tab. With no base URL or API key it runs a local check and does not call out.
- Management creates and deletes namespaces, edits and deletes pods, deployments, services, and config maps, and adds, selects, or removes kubeconfigs. Saving an edit applies that manifest. `default`, `kube-system`, `kube-public`, and `kube-node-lease` cannot be deleted. Deleting a namespace removes everything in it. A deleted pod owned by a deployment is replaced.

Enter a provider name, base URL, and API key in **Assist**. A model name is optional; a blank model uses `dashboard.ai.model` (`grok-4.7` unless `DASHBOARD_AI_MODEL` is set). Those fields stay in this browser's `localStorage` and are sent only with Ask. Leave them blank for a local check. Ask does not use the server API key.

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

Set `DASHBOARD_TOKEN` before binding beyond localhost. The browser shell loads without the token. API calls send `Authorization: Bearer`. The token is stored in `sessionStorage` for the tab. Event streams may pass it as `access_token` because `EventSource` cannot set headers. Ordinary API routes ignore that query parameter.

Create the token with OpenSSL, then start the process in the same shell:

```bash
export DASHBOARD_TOKEN="$(openssl rand -base64 32)"
printf '%s\n' "$DASHBOARD_TOKEN"
./gradlew bootRun
```

`openssl rand -base64 32` is the whole creation step. Any other long random string works the same way. Paste the printed value into the access-token form. Use that same value for the Kubernetes Secret key `token` and for Helm `auth.token`. Docker takes it with `-e DASHBOARD_TOKEN`.

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
  --from-literal=token="$DASHBOARD_TOKEN" \
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

Set the token and model key in the release, or leave them empty and create the Secret above. The Secret reference stays optional until one of those values is set.

```bash
helm upgrade --install k8s-dashboard deploy/helm/k8s-dashboard \
  --namespace k8s-dashboard \
  --create-namespace \
  --set auth.token="$DASHBOARD_TOKEN" \
  --set ai.apiKey="$XAI_API_KEY"
```

Uploaded kubeconfigs are stored on an emptyDir at `/data`. Set `persistence.enabled=true` to keep them on a PersistentVolumeClaim.

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
