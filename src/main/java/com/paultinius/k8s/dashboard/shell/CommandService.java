package com.paultinius.k8s.dashboard.shell;

import com.paultinius.k8s.dashboard.cluster.ClusterClient;
import com.paultinius.k8s.dashboard.cluster.DemoCluster;
import com.paultinius.k8s.dashboard.cluster.ResourceKind;
import com.paultinius.k8s.dashboard.error.DashboardException;
import com.paultinius.k8s.dashboard.model.ResourceView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class CommandService {

    private static final Logger log = LoggerFactory.getLogger(CommandService.class);

    private final ExternalCommand external = new ExternalCommand();

    public CommandResult execute(ClusterClient cluster, String command, String namespace, String id) {
        List<String> args = CommandPolicy.prepare(command, namespace);
        String verb = args.size() > 1 && !args.get(1).startsWith("-") ? args.get(1) : "";
        log.info("Running {} {} on cluster {}", args.get(0), verb, cluster.info().id());
        if (cluster.info().demo()) {
            if (cluster instanceof DemoCluster demo) {
                return DemoCommands.execute(demo, args);
            }
            throw DashboardException.badRequest("The demo cluster cannot run this command");
        }
        return external.run(id, args, cluster.kubeconfigDocument().orElse(""), cluster.inCluster());
    }

    public Completion complete(ClusterClient cluster, String line, int cursor, String namespace) {
        return CommandCompleter.complete(line, cursor, namespace, new ClusterNames(cluster));
    }

    public void cancel(String id) {
        external.cancel(id);
    }

    private static final class ClusterNames implements CommandNames {
        private final ClusterClient cluster;

        private ClusterNames(ClusterClient cluster) {
            this.cluster = cluster;
        }

        @Override
        public List<String> namespaces() {
            try {
                return cluster.namespaces();
            } catch (RuntimeException exception) {
                return List.of();
            }
        }

        @Override
        public List<String> names(String listAs, String namespace) {
            if ("namespace".equals(listAs)) {
                return namespaces();
            }
            ResourceKind kind = switch (listAs == null ? "" : listAs) {
                case "pod" -> ResourceKind.POD;
                case "deployment" -> ResourceKind.DEPLOYMENT;
                case "service" -> ResourceKind.SERVICE;
                case "configmap" -> ResourceKind.CONFIG_MAP;
                case "node" -> ResourceKind.NODE;
                case "event" -> ResourceKind.EVENT;
                default -> null;
            };
            if (kind == null) {
                return List.of();
            }
            Set<String> scope = namespace == null || namespace.isBlank() || !kind.namespaced()
                    ? Set.of()
                    : Set.of(namespace);
            try {
                return cluster.list(kind, scope).stream()
                        .map(ResourceView::name)
                        .distinct()
                        .sorted()
                        .toList();
            } catch (RuntimeException exception) {
                return List.of();
            }
        }
    }
}
