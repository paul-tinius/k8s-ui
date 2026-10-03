package com.paultinius.k8s.dashboard.ai;

import com.paultinius.k8s.dashboard.cluster.ClusterClient;
import com.paultinius.k8s.dashboard.cluster.ClusterRegistry;
import com.paultinius.k8s.dashboard.cluster.LogRequest;
import com.paultinius.k8s.dashboard.cluster.ResourceKind;
import com.paultinius.k8s.dashboard.config.DashboardProperties;
import com.paultinius.k8s.dashboard.error.DashboardException;
import com.paultinius.k8s.dashboard.model.AskResponse;
import com.paultinius.k8s.dashboard.model.LogLine;
import com.paultinius.k8s.dashboard.model.LogPage;
import com.paultinius.k8s.dashboard.model.ResourceDetail;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Set;

@Service
public class TroubleshootService {

    private static final String SYSTEM = """
            You are an SRE working inside a local Kubernetes dashboard. \
            Use only the evidence in the user message. If the evidence is insufficient, say what to inspect next. \
            Do not invent object names, metrics, or commands that the evidence does not support. \
            Keep the answer under 220 words. Structure it as: what is happening, likely cause, next check.
            """;

    private final ClusterRegistry clusters;
    private final AiClient client;
    private final DashboardProperties properties;
    private final LocalInsight local = new LocalInsight();

    public TroubleshootService(ClusterRegistry clusters, AiClient client, DashboardProperties properties) {
        this.clusters = clusters;
        this.client = client;
        this.properties = properties;
    }

    public AskResponse ask(
            String clusterId,
            String namespace,
            String kind,
            String name,
            String question,
            boolean includeLogs,
            String provider,
            String baseUrl,
            String modelName,
            String apiKey,
            String orgId
    ) {
        ModelRequest model = resolve(provider, baseUrl, modelName, apiKey, orgId);
        ClusterClient cluster = clusters.require(clusterId);
        ResourceDetail detail = null;
        if (kind != null && !kind.isBlank() && name != null && !name.isBlank()) {
            detail = cluster.detail(ResourceKind.from(kind), namespace == null ? "" : namespace, name);
        }
        LogPage logs = LogPage.empty();
        if (includeLogs && detail != null) {
            logs = logsFor(cluster, detail);
        }
        if (!usable(model)) {
            return new AskResponse(false, "local", model.model(), local.assess(model.provider(), model.model(), question, detail, logs));
        }
        String answer = client.complete(model, SYSTEM, prompt(cluster, detail, logs, question));
        return new AskResponse(true, model.provider(), model.model(), answer);
    }

    private ModelRequest resolve(String provider, String baseUrl, String modelName, String apiKey, String orgId) {
        DashboardProperties.Ai server = properties.getAi();
        String name = provider == null ? "" : provider.trim();
        String base = baseUrl == null ? "" : baseUrl.trim();
        String chosenModel = modelName == null ? "" : modelName.trim();
        String key = apiKey == null ? "" : apiKey.trim();
        String org = orgId == null ? "" : orgId.trim();
        if (name.isBlank() && base.isBlank() && key.isBlank()) {
            return new ModelRequest("off", "", "", "", "", server.getTimeout());
        }
        if (base.isBlank()) {
            throw DashboardException.badRequest("Base URL is required");
        }
        requireHttp(base);
        if (chosenModel.isBlank()) {
            chosenModel = server.getModel() == null ? "" : server.getModel().trim();
        }
        if (name.isBlank()) {
            name = "custom";
        }
        if (devin(name, base) && org.isBlank()) {
            throw DashboardException.badRequest("Devin organization id is required");
        }
        if (!devin(name, base)) {
            org = "";
        }
        return new ModelRequest(name, base, chosenModel, key, org, server.getTimeout());
    }

    private static void requireHttp(String base) {
        URI uri;
        try {
            uri = URI.create(base);
        } catch (RuntimeException exception) {
            throw DashboardException.badRequest("Base URL is invalid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw DashboardException.badRequest("Base URL must be http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw DashboardException.badRequest("Base URL is invalid");
        }
    }

    private static boolean devin(String provider, String baseUrl) {
        if ("devin".equalsIgnoreCase(provider)) {
            return true;
        }
        try {
            String host = URI.create(baseUrl).getHost();
            return host != null && "api.devin.ai".equalsIgnoreCase(host);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean usable(ModelRequest model) {
        if (model.apiKey() != null && !model.apiKey().isBlank()) {
            return true;
        }
        String base = model.baseUrl() == null ? "" : model.baseUrl();
        return base.startsWith("http://127.0.0.1") || base.startsWith("http://localhost");
    }

    private static LogPage logsFor(ClusterClient cluster, ResourceDetail detail) {
        String namespace = detail.resource().namespace();
        Set<String> namespaces = namespace.isBlank() ? Set.of() : Set.of(namespace);
        if ("Pod".equals(detail.resource().kind())) {
            return cluster.logs(new LogRequest(namespaces, "", detail.resource().name(), "", 80, ""));
        }
        if ("Deployment".equals(detail.resource().kind())) {
            return cluster.logs(new LogRequest(namespaces, detail.resource().name(), "", "", 80, ""));
        }
        return LogPage.empty();
    }

    private static String prompt(ClusterClient cluster, ResourceDetail detail, LogPage logs, String question) {
        StringBuilder text = new StringBuilder();
        text.append("Cluster: ").append(cluster.info().name()).append(" (").append(cluster.info().server()).append(")\n");
        if (detail != null) {
            text.append("Resource: ").append(detail.resource().kind()).append(' ')
                    .append(detail.resource().namespace()).append('/').append(detail.resource().name()).append('\n');
            text.append("Status: ").append(detail.resource().status()).append('\n');
            text.append("Summary: ").append(detail.resource().summary()).append("\n\n");
            String yaml = detail.yaml();
            if (yaml.length() > 12_000) {
                yaml = yaml.substring(0, 12_000) + "\n# truncated";
            }
            text.append("Manifest:\n").append(yaml).append('\n');
        }
        if (!logs.lines().isEmpty()) {
            text.append("\nRecent logs:\n");
            int count = 0;
            for (LogLine line : logs.lines()) {
                text.append(line.namespace()).append('/').append(line.pod()).append('/').append(line.container())
                        .append(' ').append(line.text()).append('\n');
                if (++count >= 80) {
                    break;
                }
            }
        }
        text.append("\nQuestion: ").append(question);
        return text.toString();
    }
}
