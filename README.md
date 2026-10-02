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

Your `~/.kube/config` is loaded when it exists. Each context is a cluster. Paste another kubeconfig in **Clusters**. Files are written to `~/.k8s-dashboard/clusters` with user-only permissions.

| Setting | Environment variable | Default |
| --- | --- | --- |
| Access token | `DASHBOARD_TOKEN` | empty, local use |
| Load `~/.kube/config` | `DASHBOARD_CLUSTER_LOAD_DEFAULT_KUBECONFIG` | `true` |
| Demo cluster | `DASHBOARD_CLUSTER_DEMO_ENABLED` | `true` |
| In-cluster client | `DASHBOARD_CLUSTER_IN_CLUSTER` | `false` |
| Saved kubeconfigs | `DASHBOARD_CLUSTER_DATA_DIR` | `~/.k8s-dashboard` |
| Extra kubeconfig files | `DASHBOARD_CLUSTER_KUBECONFIGS_0` | none |
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
- Assist sends the selected manifest and recent logs to the configured model. With no API key it runs a local check and does not call out.

Grok on `https://api.x.ai/v1` is the default (`grok-4.7`). The same client speaks any OpenAI-compatible chat completions API, so OpenAI, OpenRouter (Claude or Gemini), DeepSeek, Qwen, Ollama, and LM Studio are presets in the Assist tab. The key stays in the server process.

```bash
export XAI_API_KEY=...
./gradlew bootRun
```

Ollama on this machine:

```bash
export DASHBOARD_AI_BASE_URL=http://127.0.0.1:11434/v1
export DASHBOARD_AI_MODEL=llama3.1
export DASHBOARD_AI_API_KEY=
```

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

## Docker

```bash
docker build -t k8s-dashboard:1.0.0-SNAPSHOT .
docker run --rm -p 8080:8080 \
  -v "$HOME/.kube/config:/kube/config:ro" \
  -e DASHBOARD_CLUSTER_KUBECONFIGS_0=/kube/config \
  -e DASHBOARD_CLUSTER_DEMO_ENABLED=false \
  -e XAI_API_KEY \
  k8s-dashboard:1.0.0-SNAPSHOT
```

The image runs as uid 1001. Uploaded kubeconfigs go to `/data`.

## Kubernetes

```bash
docker build -t k8s-dashboard:1.0.0-SNAPSHOT .
kubectl apply -f deploy/kubernetes/dashboard.yaml
kubectl -n k8s-dashboard port-forward svc/k8s-dashboard 8080:8080
```

The manifest uses the pod service account (`DASHBOARD_CLUSTER_IN_CLUSTER=true`) and turns the demo cluster off. The ClusterRole can list workloads and change deployments, pods, services, and config maps. Treat the dashboard as an admin tool.

Port-forwards opened from a pod listen on that pod, not on your laptop. Use port-forward when you run the binary locally.

Optional secrets, both keys optional in the manifest:

```bash
kubectl -n k8s-dashboard create secret generic k8s-dashboard \
  --from-literal=token=choose-a-token \
  --from-literal=xai-api-key="$XAI_API_KEY"
```

## Privacy

Nothing phones home. Cluster data stays in this process. A model request happens only from Assist, and only when an API key is configured. The request contains the selected manifest and recent log lines, not your kubeconfig.

## Tests

```bash
./gradlew test
```

Tests use the demo cluster and do not contact a live API server.
