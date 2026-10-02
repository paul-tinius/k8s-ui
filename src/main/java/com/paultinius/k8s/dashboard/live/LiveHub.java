package com.paultinius.k8s.dashboard.live;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class LiveHub {

    private final List<Consumer<LiveEvent>> listeners = new CopyOnWriteArrayList<>();

    public Runnable subscribe(Consumer<LiveEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public void publish(String clusterId, String type) {
        LiveEvent event = new LiveEvent(type, clusterId);
        for (Consumer<LiveEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException exception) {
                listeners.remove(listener);
            }
        }
    }

    @Scheduled(fixedDelayString = "${dashboard.live.resource-interval:3s}")
    public void tick() {
        publish("*", "tick");
    }

    public record LiveEvent(String type, String clusterId) {
    }
}
