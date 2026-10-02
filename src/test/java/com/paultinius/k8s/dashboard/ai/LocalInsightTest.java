package com.paultinius.k8s.dashboard.ai;

import com.paultinius.k8s.dashboard.model.LogLine;
import com.paultinius.k8s.dashboard.model.LogPage;
import com.paultinius.k8s.dashboard.model.ResourceDetail;
import com.paultinius.k8s.dashboard.model.ResourceView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalInsightTest {

    @Test
    void assess_crashLoopWithConnectionRefused_namesTheAddress() {
        var detail = new ResourceDetail(new ResourceView(
                "Pod",
                "payments",
                "ledger-a",
                "CrashLoopBackOff",
                "node-a",
                List.of("ghcr.io/example/ledger:2.3.0"),
                Map.of("app", "ledger"),
                "",
                0,
                1,
                "0/1",
                Map.of()
        ), "yaml", List.of("ledger"));
        var logs = new LogPage(List.of(new LogLine(
                "payments",
                "ledger-a",
                "ledger",
                "java.net.ConnectException: Connection refused: db.payments.svc:5432"
        )), false);

        String answer = new LocalInsight().assess("xAI", "grok-4.7", "why", detail, logs);

        assertThat(answer).contains("db.payments.svc:5432");
        assertThat(answer).contains("CrashLoopBackOff");
        assertThat(answer).contains("local check");
        assertThat(answer).contains("grok-4.7");
    }
}
