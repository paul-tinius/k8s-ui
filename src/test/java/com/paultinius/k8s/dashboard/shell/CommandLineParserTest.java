package com.paultinius.k8s.dashboard.shell;

import com.paultinius.k8s.dashboard.error.DashboardException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandLineParserTest {

    @Test
    void parse_quotedArguments_keepsSpacesInsideTheToken() {
        assertThat(CommandLineParser.parse("kubectl get pods -l \"app=store front\""))
                .extracting(CommandLineParser.Token::text)
                .containsExactly("kubectl", "get", "pods", "-l", "app=store front");
    }

    @Test
    void parse_singleQuotedExec_keepsTheRemoteCommandTogether() {
        assertThat(CommandLineParser.parse("kubectl exec storefront-a -- sh -c 'echo hi'"))
                .extracting(CommandLineParser.Token::text)
                .containsExactly("kubectl", "exec", "storefront-a", "--", "sh", "-c", "echo hi");
    }

    @Test
    void parse_outputEquals_staysOneArgument() {
        assertThat(CommandLineParser.parse("kubectl get pods --output=wide"))
                .extracting(CommandLineParser.Token::text)
                .containsExactly("kubectl", "get", "pods", "--output=wide");
    }

    @Test
    void parse_pipe_rejectsShellSyntax() {
        assertThatThrownBy(() -> CommandLineParser.parse("kubectl get pods | grep store"))
                .isInstanceOf(DashboardException.class)
                .hasMessageContaining("shell");
    }

    @Test
    void parse_unmatchedQuote_rejectsTheLine() {
        assertThatThrownBy(() -> CommandLineParser.parse("kubectl get \"pods"))
                .isInstanceOf(DashboardException.class)
                .hasMessageContaining("quote");
    }
}
