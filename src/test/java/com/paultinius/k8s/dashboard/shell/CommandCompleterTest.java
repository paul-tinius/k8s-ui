package com.paultinius.k8s.dashboard.shell;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommandCompleterTest {

    private final CommandNames names = new CommandNames() {
        @Override
        public List<String> namespaces() {
            return List.of("payments", "shop");
        }

        @Override
        public List<String> names(String listAs, String namespace) {
            if ("pod".equals(listAs) && (namespace == null || namespace.isBlank() || "shop".equals(namespace))) {
                return List.of("cart-a", "storefront-a");
            }
            if ("namespace".equals(listAs)) {
                return namespaces();
            }
            return List.of();
        }
    };

    @Test
    void complete_emptyLine_suggestsKubectlAndHelm() {
        Completion completion = CommandCompleter.complete("", 0, "", names);

        assertThat(completion.suggestions()).extracting(Completion.Suggestion::value)
                .containsExactly("kubectl", "helm");
        assertThat(completion.hint()).contains("kubectl or helm");
    }

    @Test
    void complete_partialGet_describesGet() {
        Completion completion = CommandCompleter.complete("kubectl g", 9, "", names);

        assertThat(completion.suggestions()).extracting(Completion.Suggestion::value).contains("get");
        assertThat(completion.hint()).contains("Display one or many resources");
        assertThat(completion.usage()).contains("kubectl get");
    }

    @Test
    void complete_podPrefix_suggestsPods() {
        Completion completion = CommandCompleter.complete("kubectl get po", 14, "shop", names);

        assertThat(completion.suggestions()).extracting(Completion.Suggestion::value).contains("pods");
        assertThat(completion.suggestions()).filteredOn(item -> "pods".equals(item.value()))
                .extracting(Completion.Suggestion::detail)
                .allMatch(detail -> detail.contains("Pod"));
    }

    @Test
    void complete_afterPods_suggestsPodNames() {
        String line = "kubectl get pods ";
        Completion completion = CommandCompleter.complete(line, line.length(), "shop", names);

        assertThat(completion.suggestions()).extracting(Completion.Suggestion::value)
                .contains("cart-a", "storefront-a");
    }

    @Test
    void complete_namespaceFlag_suggestsNamespaces() {
        String line = "kubectl get pods -n ";
        Completion completion = CommandCompleter.complete(line, line.length(), "", names);

        assertThat(completion.suggestions()).extracting(Completion.Suggestion::value)
                .contains("payments", "shop");
        assertThat(completion.hint()).contains("Namespace");
    }

    @Test
    void complete_helmRepo_suggestsRepositoryCommands() {
        String line = "helm repo ";
        Completion completion = CommandCompleter.complete(line, line.length(), "", names);

        assertThat(completion.suggestions()).extracting(Completion.Suggestion::value)
                .contains("add", "list", "remove", "update");
        assertThat(completion.hint()).contains("chart repositories");
    }
}
