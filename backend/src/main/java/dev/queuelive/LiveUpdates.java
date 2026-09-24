package dev.queuelive;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
class LiveUpdates {
    private final java.util.Set<SseEmitter> clients = ConcurrentHashMap.newKeySet();
    synchronized SseEmitter connect() {
        if (clients.size() >= 300) throw new ApiException(503, "Live connection capacity reached; polling remains available");
        SseEmitter emitter = new SseEmitter(120_000L);
        clients.add(emitter);
        emitter.onCompletion(() -> clients.remove(emitter));
        emitter.onTimeout(() -> { clients.remove(emitter); emitter.complete(); });
        emitter.onError(ex -> clients.remove(emitter));
        send(emitter, "ready");
        return emitter;
    }
    private void send(SseEmitter emitter, String event) {
        try { emitter.send(SseEmitter.event().name(event).data("refresh")); }
        catch (IOException | IllegalStateException ex) { clients.remove(emitter); emitter.complete(); }
    }
    // Default phase AFTER_COMMIT: rolled-back writes never announce success.
    @TransactionalEventListener
    public void changed(Model.Changed event) { clients.forEach(e -> send(e, "changed")); }
    @Scheduled(fixedRate=20_000)
    public void heartbeat() { clients.forEach(e -> send(e, "heartbeat")); }
}

@RestController
class LiveController {
    private final LiveUpdates updates;
    LiveController(LiveUpdates updates) { this.updates = updates; }
    // Public invalidation hints only. No names, IDs, credentials or ticket data.
    @GetMapping(value="/api/events", produces="text/event-stream")
    SseEmitter events() { return updates.connect(); }
}
