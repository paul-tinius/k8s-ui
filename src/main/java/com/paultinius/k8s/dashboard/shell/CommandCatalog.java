package com.paultinius.k8s.dashboard.shell;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Static kubectl and helm command tree used for hints, completion, and namespace injection.
 */
final class CommandCatalog {

    record Flag(String name, String detail) {
    }

    record Spec(
            String name,
            String summary,
            String usage,
            boolean resources,
            boolean names,
            boolean namespaced,
            String listAs,
            List<String> aliases,
            List<Spec> children,
            List<Flag> flags
    ) {
        Spec child(String token) {
            for (Spec candidate : children) {
                if (candidate.matches(token)) {
                    return candidate;
                }
            }
            return null;
        }

        boolean matches(String token) {
            if (token == null) {
                return false;
            }
            if (name.equalsIgnoreCase(token)) {
                return true;
            }
            for (String alias : aliases) {
                if (alias.equalsIgnoreCase(token)) {
                    return true;
                }
            }
            return false;
        }
    }

    record Resource(String name, String kind, boolean namespaced, String listAs, List<String> aliases) {
        boolean matches(String token) {
            if (name.equalsIgnoreCase(token)) {
                return true;
            }
            for (String alias : aliases) {
                if (alias.equalsIgnoreCase(token)) {
                    return true;
                }
            }
            return false;
        }

        String detail() {
            if (aliases.isEmpty()) {
                return kind;
            }
            return kind + ". Short names " + String.join(", ", aliases);
        }
    }

    record Walk(Spec spec, boolean clusterScoped, String resource) {
    }

    private static final Set<String> VALUE_FLAGS = Set.of(
            "-n", "--namespace",
            "-o", "--output",
            "-l", "--selector",
            "-c", "--container",
            "--tail", "--since",
            "-f", "--filename", "--values",
            "--replicas",
            "--set", "--set-string",
            "--version",
            "--filter",
            "--timeout",
            "--grace-period",
            "--field-selector",
            "--dry-run",
            "--context",
            "--kubeconfig",
            "--kube-context",
            "-v", "--v",
            "--address",
            "--port",
            "--image",
            "--env",
            "--containers",
            "--sort-by",
            "--versions"
    );

    static final List<Flag> KUBECTL_FLAGS = List.of(
            new Flag("-n", "Namespace to use. One selected namespace is added when you omit this"),
            new Flag("--namespace", "Namespace to use"),
            new Flag("-A", "List resources in every namespace"),
            new Flag("--all-namespaces", "List resources in every namespace"),
            new Flag("-o", "Output format: wide, yaml, json, or name"),
            new Flag("--output", "Output format: wide, yaml, json, or name"),
            new Flag("-l", "Label selector, for example app=storefront"),
            new Flag("--selector", "Label selector, for example app=storefront"),
            new Flag("-f", "Filename, directory, or URL that contains the resource"),
            new Flag("--filename", "Filename, directory, or URL that contains the resource"),
            new Flag("--dry-run", "Must be none, client, or server"),
            new Flag("-w", "Watch for changes until stopped"),
            new Flag("--watch", "Watch for changes until stopped"),
            new Flag("--show-labels", "Print all labels as an extra column"),
            new Flag("--field-selector", "Selector of field names, for example status.phase=Running"),
            new Flag("--force", "Force the delete or replace"),
            new Flag("--grace-period", "Seconds to wait before deleting"),
            new Flag("--timeout", "How long to wait before giving up"),
            new Flag("-v", "Log level for kubectl")
    );

    static final List<Flag> HELM_FLAGS = List.of(
            new Flag("-n", "Namespace to use. One selected namespace is added when you omit this"),
            new Flag("--namespace", "Namespace to use"),
            new Flag("--create-namespace", "Create the release namespace if it does not exist"),
            new Flag("--dry-run", "Simulate an install or upgrade"),
            new Flag("--debug", "Print more diagnostic output"),
            new Flag("--wait", "Wait until the resources are ready"),
            new Flag("--atomic", "Roll back changes if the operation fails"),
            new Flag("--timeout", "How long to wait for Kubernetes operations"),
            new Flag("--version", "Chart version to use"),
            new Flag("-f", "Values file to use. Repeat for more files"),
            new Flag("--values", "Values file to use"),
            new Flag("--set", "Set a value on the command line, for example key=value"),
            new Flag("--set-string", "Set a string value on the command line"),
            new Flag("-o", "Output format, usually table or json"),
            new Flag("--output", "Output format, usually table or json"),
            new Flag("-A", "List releases in every namespace"),
            new Flag("--all-namespaces", "List releases in every namespace"),
            new Flag("--all", "Show all releases, including failed and uninstalled ones"),
            new Flag("--filter", "Regular expression to filter release names")
    );

    private static final List<Resource> RESOURCES = List.of(
            resource("pods", "Pod", true, "pod", "pod", "po"),
            resource("deployments", "Deployment", true, "deployment", "deployment", "deploy"),
            resource("services", "Service", true, "service", "service", "svc"),
            resource("configmaps", "ConfigMap", true, "configmap", "configmap", "cm"),
            resource("nodes", "Node", false, "node", "node", "no"),
            resource("events", "Event", true, "event", "event", "ev"),
            resource("namespaces", "Namespace", false, "namespace", "namespace", "ns"),
            resource("ingresses", "Ingress", true, "", "ingress", "ing"),
            resource("secrets", "Secret", true, "", "secret"),
            resource("statefulsets", "StatefulSet", true, "", "statefulset", "sts"),
            resource("daemonsets", "DaemonSet", true, "", "daemonset", "ds"),
            resource("replicasets", "ReplicaSet", true, "", "replicaset", "rs"),
            resource("jobs", "Job", true, "", "job"),
            resource("cronjobs", "CronJob", true, "", "cronjob", "cj"),
            resource("persistentvolumeclaims", "PersistentVolumeClaim", true, "", "persistentvolumeclaim", "pvc"),
            resource("persistentvolumes", "PersistentVolume", false, "", "persistentvolume", "pv"),
            resource("serviceaccounts", "ServiceAccount", true, "", "serviceaccount", "sa"),
            resource("endpoints", "Endpoints", true, "", "endpoint", "ep"),
            resource("networkpolicies", "NetworkPolicy", true, "", "networkpolicy", "netpol"),
            resource("horizontalpodautoscalers", "HorizontalPodAutoscaler", true, "", "horizontalpodautoscaler", "hpa"),
            resource("roles", "Role", true, "", "role"),
            resource("rolebindings", "RoleBinding", true, "", "rolebinding"),
            resource("clusterroles", "ClusterRole", false, "", "clusterrole"),
            resource("clusterrolebindings", "ClusterRoleBinding", false, "", "clusterrolebinding"),
            resource("storageclasses", "StorageClass", false, "", "storageclass", "sc"),
            resource("customresourcedefinitions", "CustomResourceDefinition", false, "", "customresourcedefinition", "crd", "crds"),
            resource("limitranges", "LimitRange", true, "", "limitrange", "limits"),
            resource("resourcequotas", "ResourceQuota", true, "", "resourcequota", "quota"),
            resource("all", "Pods, services, deployments, and more", true, "", "all")
    );

    private static final Spec KUBECTL = kubectl();
    private static final Spec HELM = helm();

    private CommandCatalog() {
    }

    static Spec tool(String name) {
        if ("kubectl".equalsIgnoreCase(name)) {
            return KUBECTL;
        }
        if ("helm".equalsIgnoreCase(name)) {
            return HELM;
        }
        return null;
    }

    static List<Spec> tools() {
        return List.of(KUBECTL, HELM);
    }

    static List<Resource> resources() {
        return RESOURCES;
    }

    static Resource resource(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String type = token;
        int slash = type.indexOf('/');
        if (slash >= 0) {
            type = type.substring(0, slash);
        }
        int comma = type.indexOf(',');
        if (comma >= 0) {
            type = type.substring(0, comma);
        }
        for (Resource resource : RESOURCES) {
            if (resource.matches(type)) {
                return resource;
            }
        }
        return null;
    }

    static boolean takesValue(String token) {
        if (token == null || token.length() < 2 || !token.startsWith("-") || "--".equals(token)) {
            return false;
        }
        String name = token;
        int equals = token.indexOf('=');
        if (equals >= 0) {
            name = token.substring(0, equals);
        }
        return VALUE_FLAGS.contains(name);
    }

    static List<String> outputValues() {
        return List.of("wide", "yaml", "json", "name");
    }

    static List<String> dryRunValues() {
        return List.of("none", "client", "server");
    }

    static Walk inspect(List<String> args) {
        if (args == null || args.isEmpty()) {
            return new Walk(null, false, "");
        }
        Spec spec = tool(args.get(0));
        if (spec == null) {
            return new Walk(null, false, "");
        }
        boolean clusterScoped = false;
        String resource = "";
        boolean resourceSeen = false;
        for (int index = 1; index < args.size(); index++) {
            String token = args.get(index);
            if ("--".equals(token)) {
                for (int rest = index + 1; rest < args.size(); rest++) {
                    if (clusterScoped(args.get(rest))) {
                        clusterScoped = true;
                    }
                }
                break;
            }
            if (token.startsWith("-")) {
                if (takesValue(token) && !token.contains("=")
                        && index + 1 < args.size()
                        && !args.get(index + 1).startsWith("-")) {
                    index++;
                }
                continue;
            }
            Spec child = !resourceSeen ? spec.child(token) : null;
            if (child != null) {
                spec = child;
                continue;
            }
            if (!resourceSeen && resource(token) != null) {
                resourceSeen = true;
                resource = token;
                if (clusterScoped(token)) {
                    clusterScoped = true;
                }
                continue;
            }
            if (clusterScoped(token)) {
                clusterScoped = true;
            }
        }
        return new Walk(spec, clusterScoped, resource);
    }

    static boolean clusterScoped(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        for (String part : token.split(",")) {
            String type = part.trim();
            int slash = type.indexOf('/');
            if (slash >= 0) {
                type = type.substring(0, slash);
            }
            Resource match = resource(type);
            if (match != null && !match.namespaced()) {
                return true;
            }
        }
        return false;
    }

    private static Spec kubectl() {
        return tool("kubectl", "Control the selected Kubernetes cluster", "kubectl <command> [flags]",
                resourceCommand("get", "Display one or many resources",
                        "kubectl get TYPE [NAME] [-n NAMESPACE] [-o wide|yaml|json|name] [-l SELECTOR]", true,
                        new Flag("-w", "Watch for changes"),
                        new Flag("--show-labels", "Print labels as a column")),
                resourceCommand("describe", "Show details of a resource",
                        "kubectl describe TYPE [NAME] [-n NAMESPACE]", true),
                resourceCommand("apply", "Apply a configuration to a resource",
                        "kubectl apply -f FILE [-n NAMESPACE] [--dry-run=client|server]", true,
                        new Flag("-f", "File or directory to apply"),
                        new Flag("--server-side", "Use server-side apply"),
                        new Flag("-k", "Apply a kustomization directory")),
                resourceCommand("delete", "Delete resources by name, file, or label",
                        "kubectl delete TYPE [NAME] [-n NAMESPACE] [--force] [--grace-period=SECONDS]", true,
                        new Flag("--now", "Send a short grace period"),
                        new Flag("--all", "Delete every resource of that type in the namespace")),
                resourceCommand("create", "Create a resource from a file or from flags",
                        "kubectl create -f FILE | kubectl create TYPE NAME", true),
                leaf("logs", "Print logs for a container in a pod",
                        "kubectl logs POD [-c CONTAINER] [-n NAMESPACE] [--tail=LINES]", true, true, "pod",
                        new Flag("-c", "Container name"),
                        new Flag("--container", "Container name"),
                        new Flag("--tail", "Lines from the end of the logs"),
                        new Flag("-f", "Follow the log stream"),
                        new Flag("--follow", "Follow the log stream"),
                        new Flag("--since", "Only logs newer than this duration, for example 5m"),
                        new Flag("--timestamps", "Print a timestamp on each line"),
                        new Flag("--all-containers", "Print logs from every container in the pod")),
                leaf("exec", "Run a command in a container. This panel has no interactive terminal",
                        "kubectl exec POD [-c CONTAINER] [-n NAMESPACE] -- COMMAND", true, true, "pod",
                        new Flag("-c", "Container name"),
                        new Flag("--container", "Container name"),
                        new Flag("-it", "Allocate a terminal. The panel cannot attach one"),
                        new Flag("-i", "Pass stdin to the container"),
                        new Flag("-t", "Allocate a terminal")),
                resourceCommand("port-forward", "Forward local ports to a pod or service",
                        "kubectl port-forward TYPE/NAME LOCAL:REMOTE [-n NAMESPACE]", true,
                        new Flag("--address", "Addresses to listen on")),
                resourceCommand("scale", "Set a new size for a deployment or similar resource",
                        "kubectl scale TYPE NAME --replicas=COUNT [-n NAMESPACE]", true,
                        new Flag("--replicas", "The new replica count")),
                group("rollout", "Manage a rollout", "kubectl rollout COMMAND TYPE NAME [-n NAMESPACE]", true,
                        resourceCommand("status", "Show the status of a rollout",
                                "kubectl rollout status TYPE NAME [-n NAMESPACE]", true),
                        resourceCommand("history", "Show previous rollouts",
                                "kubectl rollout history TYPE NAME [-n NAMESPACE]", true),
                        resourceCommand("undo", "Roll back to a previous revision",
                                "kubectl rollout undo TYPE NAME [-n NAMESPACE]", true),
                        resourceCommand("restart", "Restart a rollout",
                                "kubectl rollout restart TYPE NAME [-n NAMESPACE]", true),
                        resourceCommand("pause", "Pause a rollout",
                                "kubectl rollout pause TYPE NAME [-n NAMESPACE]", true),
                        resourceCommand("resume", "Resume a paused rollout",
                                "kubectl rollout resume TYPE NAME [-n NAMESPACE]", true)),
                resourceCommand("explain", "Show documentation for a resource field",
                        "kubectl explain TYPE[.FIELD]", false),
                leaf("api-resources", "List the resource types the API server supports",
                        "kubectl api-resources [-o wide|name]", false, false, ""),
                leaf("api-versions", "List the API versions the server supports",
                        "kubectl api-versions", false, false, ""),
                leaf("cluster-info", "Show the address of the control plane",
                        "kubectl cluster-info", false, false, ""),
                group("config", "Read kubeconfig context information", "kubectl config COMMAND", false,
                        leaf("current-context", "Show the current context name",
                                "kubectl config current-context", false, false, ""),
                        leaf("get-contexts", "List contexts in the kubeconfig",
                                "kubectl config get-contexts", false, false, ""),
                        leaf("view", "Show the kubeconfig this command is using",
                                "kubectl config view", false, false, ""),
                        leaf("use-context", "The dashboard keeps the selected cluster. This flag is ignored",
                                "kubectl config use-context NAME", false, false, ""),
                        leaf("set-context", "Change a context entry",
                                "kubectl config set-context NAME", false, false, "")),
                group("top", "Show CPU and memory use", "kubectl top pod|node [NAME]", false,
                        leaf("pod", "Show pod metrics", "kubectl top pod [NAME] [-n NAMESPACE]", true, true, "pod", "pods", "po"),
                        leaf("node", "Show node metrics", "kubectl top node [NAME]", false, true, "node", "nodes", "no")),
                resourceCommand("label", "Update labels on a resource",
                        "kubectl label TYPE NAME KEY=VALUE [-n NAMESPACE]", true),
                resourceCommand("annotate", "Update annotations on a resource",
                        "kubectl annotate TYPE NAME KEY=VALUE [-n NAMESPACE]", true),
                leaf("cordon", "Mark a node as unschedulable", "kubectl cordon NODE", false, true, "node"),
                leaf("uncordon", "Mark a node as schedulable", "kubectl uncordon NODE", false, true, "node"),
                leaf("drain", "Evict pods from a node so it can be taken down",
                        "kubectl drain NODE [--ignore-daemonsets] [--force]", false, true, "node",
                        new Flag("--ignore-daemonsets", "Ignore DaemonSet pods"),
                        new Flag("--delete-emptydir-data", "Delete pods that use emptyDir")),
                leaf("diff", "Diff the live configuration against what would be applied",
                        "kubectl diff -f FILE [-n NAMESPACE]", true, false, "",
                        new Flag("-f", "File to compare")),
                group("auth", "Inspect authorization", "kubectl auth COMMAND", false,
                        leaf("can-i", "Check whether an action is allowed",
                                "kubectl auth can-i VERB TYPE [-n NAMESPACE]", true, false, ""),
                        leaf("whoami", "Show the user this kubeconfig authenticates as",
                                "kubectl auth whoami", false, false, "")),
                leaf("version", "Print the client and server version",
                        "kubectl version [--client]", false, false, ""),
                resourceCommand("wait", "Wait until a condition is true",
                        "kubectl wait TYPE NAME --for=CONDITION [-n NAMESPACE]", true,
                        new Flag("--for", "Condition to wait for, for example condition=ready")),
                resourceCommand("patch", "Update fields of a resource",
                        "kubectl patch TYPE NAME --patch PATCH [-n NAMESPACE]", true,
                        new Flag("--patch", "Patch document"),
                        new Flag("--type", "Patch type: strategic, merge, or json")),
                resourceCommand("edit", "Edit a resource in an editor. This panel has no editor",
                        "kubectl edit TYPE NAME [-n NAMESPACE]", true),
                resourceCommand("replace", "Replace a resource from a file",
                        "kubectl replace -f FILE [-n NAMESPACE]", true),
                leaf("expose", "Create a service for a workload",
                        "kubectl expose TYPE NAME [--port=PORT] [-n NAMESPACE]", true, false, "",
                        new Flag("--port", "Port the service should serve"),
                        new Flag("--type", "Service type, for example ClusterIP")),
                leaf("run", "Create a pod from an image",
                        "kubectl run NAME --image=IMAGE [-n NAMESPACE]", true, false, "",
                        new Flag("--image", "Container image to run"),
                        new Flag("--command", "Use the remaining arguments as the container command")),
                leaf("debug", "Attach an ephemeral debug container or copy a pod",
                        "kubectl debug POD [-n NAMESPACE] [--image=IMAGE]", true, true, "pod",
                        new Flag("--image", "Image for the ephemeral container")),
                leaf("events", "List events", "kubectl events [-n NAMESPACE]", true, false, ""),
                leaf("proxy", "Run a proxy to the Kubernetes API server",
                        "kubectl proxy [--port=PORT]", false, false, "",
                        new Flag("--port", "Local port. 0 chooses one")),
                leaf("attach", "Attach to a process already running in a pod",
                        "kubectl attach POD [-c CONTAINER] [-n NAMESPACE]", true, true, "pod"),
                leaf("cp", "Copy files to or from a container",
                        "kubectl cp SOURCE DEST [-c CONTAINER] [-n NAMESPACE]", true, false, "",
                        new Flag("-c", "Container name")),
                leaf("kustomize", "Build a kustomization directory",
                        "kubectl kustomize DIRECTORY", false, false, ""),
                group("certificate", "Approve or deny a certificate signing request",
                        "kubectl certificate COMMAND NAME", false,
                        leaf("approve", "Approve a certificate signing request",
                                "kubectl certificate approve NAME", false, false, ""),
                        leaf("deny", "Deny a certificate signing request",
                                "kubectl certificate deny NAME", false, false, "")),
                group("set", "Change fields on a workload", "kubectl set COMMAND TYPE NAME", true,
                        resourceCommand("image", "Change a container image",
                                "kubectl set image TYPE NAME CONTAINER=IMAGE [-n NAMESPACE]", true),
                        resourceCommand("env", "Change environment variables",
                                "kubectl set env TYPE NAME KEY=VALUE [-n NAMESPACE]", true),
                        resourceCommand("resources", "Change CPU and memory requests",
                                "kubectl set resources TYPE NAME [-n NAMESPACE]", true)),
                group("plugin", "List client plugins", "kubectl plugin COMMAND", false,
                        leaf("list", "List kubectl plugins on PATH", "kubectl plugin list", false, false, "")),
                leaf("completion", "Generate a shell completion script",
                        "kubectl completion bash|zsh|fish|powershell", false, false, ""),
                leaf("options", "List global kubectl flags", "kubectl options", false, false, ""),
                leaf("help", "Show help for a command", "kubectl help [COMMAND]", false, false, ""));
    }

    private static Spec helm() {
        return tool("helm", "Install and inspect Helm charts on the selected cluster", "helm <command> [flags]",
                leaf("install", "Install a chart",
                        "helm install NAME CHART [-n NAMESPACE] [-f VALUES] [--set key=value]", true, false, "",
                        new Flag("--create-namespace", "Create the namespace if needed"),
                        new Flag("--wait", "Wait until the release is ready"),
                        new Flag("--atomic", "Delete the install if it fails")),
                leaf("upgrade", "Upgrade a release",
                        "helm upgrade NAME CHART [-n NAMESPACE] [-f VALUES] [--install]", true, false, "",
                        new Flag("--install", "Install the release if it does not exist"),
                        new Flag("--reuse-values", "Keep the previous release values"),
                        new Flag("--atomic", "Roll back if the upgrade fails"),
                        new Flag("--wait", "Wait until the release is ready")),
                leaf("uninstall", "Uninstall a release",
                        "helm uninstall NAME [-n NAMESPACE]", true, false, "",
                        new Flag("--keep-history", "Keep the release history after uninstall")),
                leaf("delete", "Uninstall a release",
                        "helm delete NAME [-n NAMESPACE]", true, false, ""),
                leaf("list", "List releases",
                        "helm list [-n NAMESPACE] [--all] [--all-namespaces]", true, false, "",
                        new Flag("-q", "Print release names only"),
                        new Flag("--short", "Print release names only")),
                leaf("ls", "List releases",
                        "helm ls [-n NAMESPACE] [--all-namespaces]", true, false, ""),
                leaf("status", "Show the status of a release",
                        "helm status NAME [-n NAMESPACE]", true, false, ""),
                group("get", "Download information about a release", "helm get COMMAND NAME [-n NAMESPACE]", true,
                        leaf("all", "Download every part of a release", "helm get all NAME [-n NAMESPACE]", true, false, ""),
                        leaf("hooks", "Download the hooks of a release", "helm get hooks NAME [-n NAMESPACE]", true, false, ""),
                        leaf("manifest", "Download the rendered manifest", "helm get manifest NAME [-n NAMESPACE]", true, false, ""),
                        leaf("notes", "Download the release notes", "helm get notes NAME [-n NAMESPACE]", true, false, ""),
                        leaf("values", "Download the release values", "helm get values NAME [-n NAMESPACE]", true, false, "",
                                new Flag("-a", "Show all values, including defaults"))),
                leaf("history", "Fetch release history",
                        "helm history NAME [-n NAMESPACE]", true, false, ""),
                leaf("rollback", "Roll a release back to an older revision",
                        "helm rollback NAME [REVISION] [-n NAMESPACE]", true, false, "",
                        new Flag("--wait", "Wait until the rollback is ready")),
                group("repo", "Work with chart repositories", "helm repo COMMAND", false,
                        leaf("add", "Add a chart repository", "helm repo add NAME URL", false, false, ""),
                        leaf("list", "List chart repositories", "helm repo list", false, false, ""),
                        leaf("remove", "Remove a chart repository", "helm repo remove NAME", false, false, ""),
                        leaf("update", "Update chart repositories", "helm repo update [NAME]", false, false, ""),
                        leaf("index", "Generate an index file for a chart directory",
                                "helm repo index DIRECTORY", false, false, "")),
                group("search", "Search for charts", "helm search COMMAND KEYWORD", false,
                        leaf("repo", "Search repositories that have been added",
                                "helm search repo KEYWORD", false, false, ""),
                        leaf("hub", "Search Artifact Hub", "helm search hub KEYWORD", false, false, "")),
                group("show", "Show information about a chart", "helm show COMMAND CHART", false,
                        leaf("all", "Show every part of a chart", "helm show all CHART", false, false, ""),
                        leaf("chart", "Show the chart metadata", "helm show chart CHART", false, false, ""),
                        leaf("crds", "Show the chart CRDs", "helm show crds CHART", false, false, ""),
                        leaf("readme", "Show the chart README", "helm show readme CHART", false, false, ""),
                        leaf("values", "Show the chart's default values", "helm show values CHART", false, false, "")),
                leaf("pull", "Download a chart archive", "helm pull CHART [--version VERSION]", false, false, ""),
                leaf("template", "Render chart templates locally",
                        "helm template NAME CHART [-f VALUES]", false, false, ""),
                leaf("lint", "Examine a chart for issues", "helm lint PATH", false, false, ""),
                leaf("package", "Package a chart directory", "helm package PATH", false, false, ""),
                leaf("create", "Create a new chart directory", "helm create NAME", false, false, ""),
                group("dependency", "Manage a chart's dependencies", "helm dependency COMMAND", false,
                        leaf("build", "Build the charts/ directory from Chart.lock",
                                "helm dependency build CHART", false, false, ""),
                        leaf("list", "List the chart's dependencies", "helm dependency list CHART", false, false, ""),
                        leaf("update", "Update Chart.lock and download dependencies",
                                "helm dependency update CHART", false, false, "")),
                group("plugin", "Manage Helm plugins", "helm plugin COMMAND", false,
                        leaf("install", "Install a plugin", "helm plugin install PATH", false, false, ""),
                        leaf("list", "List installed plugins", "helm plugin list", false, false, ""),
                        leaf("uninstall", "Uninstall a plugin", "helm plugin uninstall NAME", false, false, ""),
                        leaf("update", "Update a plugin", "helm plugin update NAME", false, false, "")),
                leaf("version", "Print the Helm client version", "helm version", false, false, ""),
                leaf("env", "Print Helm client environment information", "helm env", false, false, ""),
                leaf("test", "Run tests for a release", "helm test NAME [-n NAMESPACE]", true, false, ""),
                group("registry", "Log in to or out of an OCI registry", "helm registry COMMAND", false,
                        leaf("login", "Log in to a registry", "helm registry login HOST", false, false, ""),
                        leaf("logout", "Log out of a registry", "helm registry logout HOST", false, false, "")),
                leaf("completion", "Generate a shell completion script",
                        "helm completion bash|zsh|fish|powershell", false, false, ""),
                leaf("help", "Show help for a command", "helm help [COMMAND]", false, false, ""));
    }

    private static Spec tool(String name, String summary, String usage, Spec... children) {
        return spec(name, summary, usage, false, false, false, "", List.of(), List.of(children), List.of());
    }

    private static Spec group(String name, String summary, String usage, boolean namespaced, Spec... children) {
        return spec(name, summary, usage, false, false, namespaced, "", List.of(), List.of(children), List.of());
    }

    private static Spec resourceCommand(String name, String summary, String usage, boolean namespaced, Flag... flags) {
        return spec(name, summary, usage, true, true, namespaced, "", List.of(), List.of(), List.of(flags));
    }

    private static Spec leaf(
            String name,
            String summary,
            String usage,
            boolean namespaced,
            boolean names,
            String listAs,
            Flag... flags
    ) {
        return spec(name, summary, usage, false, names, namespaced, listAs, List.of(), List.of(), List.of(flags));
    }

    private static Spec leaf(
            String name,
            String summary,
            String usage,
            boolean namespaced,
            boolean names,
            String listAs,
            String alias,
            String... moreAliases
    ) {
        List<String> aliases = new ArrayList<>();
        aliases.add(alias);
        aliases.addAll(List.of(moreAliases));
        return spec(name, summary, usage, false, names, namespaced, listAs, aliases, List.of(), List.of());
    }

    private static Spec spec(
            String name,
            String summary,
            String usage,
            boolean resources,
            boolean names,
            boolean namespaced,
            String listAs,
            List<String> aliases,
            List<Spec> children,
            List<Flag> flags
    ) {
        return new Spec(name, summary, usage, resources, names, namespaced, listAs, aliases, children, flags);
    }

    private static Resource resource(String name, String kind, boolean namespaced, String listAs, String... aliases) {
        return new Resource(name, kind, namespaced, listAs, List.of(aliases));
    }

    static String canonicalTool(String name) {
        if (name == null) {
            return "";
        }
        String lowered = name.toLowerCase(Locale.ROOT);
        if ("kubectl".equals(lowered) || "helm".equals(lowered)) {
            return lowered;
        }
        return "";
    }
}
