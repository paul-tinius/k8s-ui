package com.paultinius.k8s.dashboard.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paultinius.k8s.dashboard.cluster.ClusterRegistry;
import com.paultinius.k8s.dashboard.cluster.LogRequest;
import com.paultinius.k8s.dashboard.model.LogPage;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class LogStreams {

    private final ClusterRegistry clusters;
    private final ObjectMapper mapper;
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    public LogStreams(ClusterRegistry clusters, ObjectMapper mapper) {
        this.clusters = clusters;
        this.mapper = mapper;
    }

    public SseEmitter open(String clusterId, LogRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        Subscription subscription = new Subscription(clusterId, request, emitter);
        subscriptions.add(subscription);
        emitter.onCompletion(() -> subscriptions.remove(subscription));
        emitter.onTimeout(() -> subscriptions.remove(subscription));
        emitter.onError(error -> subscriptions.remove(subscription));
        return emitter;
    }

    @Scheduled(fixedDelayString = "${dashboard.live.log-interval:2s}")
    public void tick() {
        for (Subscription subscription : List.copyOf(subscriptions)) {
            push(subscription);
        }
    }

    private void push(Subscription subscription) {
        try {
            LogPage page = clusters.require(subscription.clusterId).logs(subscription.request);
            String payload = mapper.writeValueAsString(page);
            if (payload == null) {
                throw new IllegalStateException("Could not encode the log page");
            }
            subscription.emitter.send(SseEmitter.event().name("logs").data(payload));
        } catch (Exception exception) {
            subscription.emitter.complete();
            subscriptions.remove(subscription);
        }
    }

    private record Subscription(String clusterId, LogRequest request, SseEmitter emitter) {
    }
}
