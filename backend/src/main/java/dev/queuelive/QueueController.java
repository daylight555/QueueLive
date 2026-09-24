package dev.queuelive;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import static dev.queuelive.Model.*;

@RestController
@RequestMapping("/api")
class QueueController {
    private final QueueService queue;
    QueueController(QueueService queue) { this.queue = queue; }
    private UUID owner(HttpSession session) {
        UUID id = (UUID) session.getAttribute("studentOwner");
        if (id == null) {
            id = UUID.nameUUIDFromBytes(session.getId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            session.setAttribute("studentOwner", id);
        }
        return id;
    }
    @GetMapping("/session") Map<String, Object> session(HttpSession session, CsrfToken csrf, Principal principal) {
        owner(session);
        return Map.of("csrfToken", csrf.getToken(), "staff", principal == null ? "" : principal.getName());
    }
    @GetMapping("/public") PublicSnapshot publicSnapshot() { return queue.publicSnapshot(); }
    @GetMapping("/student") StudentSnapshot student(HttpSession session) { return queue.studentSnapshot(owner(session)); }
    record Join(@Size(max=40) String displayName) {}
    @PostMapping("/student/join") Mutation join(HttpSession session, @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody Join input) {
        return queue.join(owner(session), key, input.displayName());
    }
    @PostMapping("/student/leave") Mutation leave(HttpSession session) { return queue.leave(owner(session)); }
    @GetMapping("/staff") StaffSnapshot staff(Principal principal) { return queue.staffSnapshot(principal.getName()); }
    @PostMapping("/staff/call-next") Mutation next(Principal principal, @RequestHeader("Idempotency-Key") UUID key) { return queue.callNext(principal.getName(), key); }
    record Transition(@NotNull State state) {}
    @PostMapping("/staff/tickets/{id}/transition") Mutation transition(Principal principal, @PathVariable UUID id, @Valid @RequestBody Transition input) {
        return queue.transition(principal.getName(), id, input.state());
    }
    record Open(@NotNull Boolean open) {}
    @PostMapping("/staff/queue") void open(Principal principal, @Valid @RequestBody Open input) { queue.setOpen(principal.getName(), input.open()); }
}
