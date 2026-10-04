package com.paultinius.k8s.dashboard.shell;

import com.paultinius.k8s.dashboard.error.DashboardException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandPolicyTest {

    @Test
    void prepare_oneNamespace_addsItWhenTheCommandCanTakeOne() {
        assertThat(CommandPolicy.prepare("kubectl get pods", "shop"))
                .containsExactly("kubectl", "get", "pods", "-n", "shop");
    }

    @Test
    void prepare_nodes_doesNotAddANamespace() {
        assertThat(CommandPolicy.prepare("kubectl get nodes", "shop"))
                .containsExactly("kubectl", "get", "nodes");
    }

    @Test
    void prepare_existingNamespaceFlag_isLeftAlone() {
        assertThat(CommandPolicy.prepare("kubectl get pods -n payments", "shop"))
                .containsExactly("kubectl", "get", "pods", "-n", "payments");
    }

    @Test
    void prepare_helmVersion_doesNotAddANamespace() {
        assertThat(CommandPolicy.prepare("helm version", "shop"))
                .containsExactly("helm", "version");
    }

    @Test
    void prepare_helmList_addsTheNamespace() {
        assertThat(CommandPolicy.prepare("helm list", "shop"))
                .containsExactly("helm", "list", "-n", "shop");
    }

    @Test
    void prepare_kubeconfigFlag_isRejected() {
        assertThatThrownBy(() -> CommandPolicy.prepare("kubectl get pods --kubeconfig /tmp/other", "shop"))
                .isInstanceOf(DashboardException.class)
                .hasMessageContaining("selected cluster");
    }

    @Test
    void prepare_contextFlag_isRejected() {
        assertThatThrownBy(() -> CommandPolicy.prepare("helm --kube-context other list", ""))
                .isInstanceOf(DashboardException.class)
                .hasMessageContaining("selected cluster");
    }

    @Test
    void prepare_pathToTheBinary_isRejected() {
        assertThatThrownBy(() -> CommandPolicy.prepare("/usr/bin/kubectl get pods", ""))
                .isInstanceOf(DashboardException.class)
                .hasMessageContaining("by name");
    }
}
