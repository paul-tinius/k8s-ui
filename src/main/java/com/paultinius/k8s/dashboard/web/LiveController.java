package com.paultinius.k8s.dashboard.web;

import com.paultinius.k8s.dashboard.live.LiveHub;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api")
public class LiveController {

    private final LiveHub hub;

    public LiveController(LiveHub hub) {
        this.hub = hub;
    }

    @GetMapping(path = "/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter live() {
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean open = new AtomicBoolean(true);
        Runnable remove = hub.subscribe(event -> {
            if (!open.get()) {
                return;
            }
            try {
                String type = event.type();
                String clusterId = event.clusterId();
                if (type == null || clusterId == null) {
                    return;
                }
                emitter.send(SseEmitter.event().name(type).data(clusterId));
            } catch (Exception exception) {
                open.set(false);
                emitter.complete();
            }
        });
        emitter.onCompletion(() -> {
            open.set(false);
            remove.run();
        });
        emitter.onTimeout(() -> {
            open.set(false);
            remove.run();
        });
        emitter.onError(error -> {
            open.set(false);
            remove.run();
        });
        return emitter;
    }
}
