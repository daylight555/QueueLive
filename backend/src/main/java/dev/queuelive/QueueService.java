package dev.queuelive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static dev.queuelive.Model.*;

@Service
public class QueueService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final String joinUrl;
    private static final String ACTIVE = "('WAITING','CALLED','IN_SERVICE')";
    private static final RowMapper<Ticket> TICKET = (rs, row) -> new Ticket(
        rs.getObject("id", UUID.class), number(rs.getLong("number")), rs.getString("display_name"),
        State.valueOf(rs.getString("state")), rs.getString("assigned_to"), time(rs,"created_at"),
        time(rs,"called_at"), time(rs,"started_at"), time(rs,"finished_at"));

    QueueService(JdbcTemplate db, ObjectMapper json, ApplicationEventPublisher events,
        @Value("${queuelive.public-base-url}") String baseUrl) {
        var uri = java.net.URI.create(baseUrl);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
            || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("PUBLIC_BASE_URL must be an HTTP(S) origin, without path, credentials, query or fragment");
        }
        this.db = db; this.json = json; this.events = events;
        this.joinUrl = baseUrl.replaceAll("/+$", "") + "/";
    }
    static String number(long n) { return "Q-" + String.format(java.util.Locale.ROOT, "%03d", n); }
    private static Instant time(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }
    // All writes acquire this SAME row first; PostgreSQL serialises callers across threads/processes.
    private boolean lockQueue() { return db.queryForObject("SELECT is_open FROM help_queue WHERE id=1 FOR UPDATE", Boolean.class); }
    private boolean isOpen() { return db.queryForObject("SELECT is_open FROM help_queue WHERE id=1", Boolean.class); }
    private Ticket ticket(UUID id) { return db.queryForObject("SELECT * FROM ticket WHERE id=?", TICKET, id); }
    private Ticket first(String sql, Object... args) { return db.query(sql, TICKET, args).stream().findFirst().orElse(null); }
    private void changed() { events.publishEvent(new Changed()); }
    private void audit(UUID id, String actor, State from, State to) {
        db.update("INSERT INTO audit_event(ticket_id,actor,from_state,to_state) VALUES (?,?,?,?)",
            id, actor, from == null ? null : from.name(), to.name());
    }
    // Called only while holding the queue lock, inside the enclosing transaction.
    // Null assignment results are stored too: an empty-queue retry must remain empty.
    private Mutation once(String caller, String operation, UUID key, String payload, Supplier<Mutation> action) {
        var rows = db.queryForList("SELECT payload,response FROM operation_result WHERE caller=? AND operation=? AND key=?", caller, operation, key);
        if (!rows.isEmpty()) {
            var row = rows.getFirst();
            if (!payload.equals(row.get("payload"))) throw new ApiException(409, "Idempotency key already used with different input");
            try { return json.readValue((String) row.get("response"), Mutation.class); }
            catch (JsonProcessingException e) { throw new IllegalStateException("Cannot read stored operation", e); }
        }
        Mutation result = action.get();
        try {
            db.update("INSERT INTO operation_result(caller,operation,key,payload,response) VALUES (?,?,?,?,?)",
                caller, operation, key, payload, json.writeValueAsString(result));
        } catch (JsonProcessingException e) { throw new IllegalStateException("Cannot store operation", e); }
        return result;
    }

    @Transactional
    public Mutation join(UUID owner, UUID key, String displayName) {
        String name = displayName == null ? "" : displayName.strip();
        if (name.length() > 40 || name.codePoints().anyMatch(Character::isISOControl)) throw new ApiException(400, "Use a name of at most 40 characters without control characters");
        boolean open = lockQueue();
        return once("student:" + owner, "join", key, name, () -> {
            Ticket existing = first("SELECT * FROM ticket WHERE owner=? AND state IN " + ACTIVE, owner);
            if (existing != null) return new Mutation(existing, "Your current ticket");
            if (!open) throw new ApiException(409, "The queue is closed to new joins");
            long n = db.queryForObject("UPDATE help_queue SET next_number=next_number+1 WHERE id=1 RETURNING next_number-1", Long.class);
            UUID id = UUID.randomUUID();
            db.update("INSERT INTO ticket(id,queue_id,number,owner,display_name,state) VALUES (?,1,?,?,?,'WAITING')", id, n, owner, name.isEmpty() ? null : name);
            audit(id, "student:" + owner, null, State.WAITING); changed();
            return new Mutation(ticket(id), "You joined the queue");
        });
    }

    @Transactional
    public Mutation callNext(String staff, UUID key) {
        lockQueue();
        return once("staff:" + staff, "call-next", key, "", () -> {
            if (first("SELECT * FROM ticket WHERE assigned_to=? AND state IN ('CALLED','IN_SERVICE')", staff) != null)
                throw new ApiException(409, "Finish your current assignment first");
            Ticket next = first("SELECT * FROM ticket WHERE state='WAITING' ORDER BY number,id LIMIT 1");
            if (next == null) return new Mutation(null, "No students are waiting");
            db.update("UPDATE ticket SET state='CALLED',assigned_to=?,called_at=now() WHERE id=?", staff, next.id());
            audit(next.id(), "staff:" + staff, State.WAITING, State.CALLED); changed();
            return new Mutation(ticket(next.id()), "Student called");
        });
    }

    @Transactional
    public Mutation leave(UUID owner) {
        lockQueue();
        Ticket own = first("SELECT * FROM ticket WHERE owner=? AND state IN " + ACTIVE, owner);
        if (own == null || own.state() != State.WAITING) throw new ApiException(409, "Only a waiting ticket can be cancelled");
        db.update("UPDATE ticket SET state='CANCELLED',finished_at=now() WHERE id=?", own.id());
        audit(own.id(), "student:" + owner, own.state(), State.CANCELLED); changed();
        return new Mutation(ticket(own.id()), "You left the queue");
    }

    @Transactional
    public Mutation transition(String staff, UUID id, State target) {
        lockQueue();
        Ticket current = first("SELECT * FROM ticket WHERE id=? AND assigned_to=?", id, staff);
        if (current == null) throw new ApiException(404, "Assignment not found");
        boolean valid = (current.state() == State.CALLED && (target == State.IN_SERVICE || target == State.NO_SHOW))
            || (current.state() == State.IN_SERVICE && target == State.COMPLETED);
        if (!valid) throw new ApiException(409, "This transition is not allowed");
        String timestamp = target == State.IN_SERVICE ? "started_at" : "finished_at";
        db.update("UPDATE ticket SET state=?," + timestamp + "=now() WHERE id=?", target.name(), id);
        audit(id, "staff:" + staff, current.state(), target); changed();
        return new Mutation(ticket(id), "Ticket updated");
    }

    @Transactional
    public void setOpen(String staff, boolean open) {
        boolean previous = lockQueue();
        if (previous == open) return;
        db.update("UPDATE help_queue SET is_open=? WHERE id=1", open);
        db.update("INSERT INTO audit_event(actor,from_state,to_state) VALUES (?,?,?)", "staff:" + staff, previous ? "OPEN" : "CLOSED", open ? "OPEN" : "CLOSED");
        changed();
    }

    private Counts counts() {
        return db.queryForObject("SELECT count(*) FILTER(WHERE state='WAITING') waiting, count(*) FILTER(WHERE state IN ('CALLED','IN_SERVICE')) active, count(*) FILTER(WHERE state='COMPLETED') completed, count(*) FILTER(WHERE state='NO_SHOW') no_show FROM ticket", (rs, row) ->
            new Counts(rs.getLong("waiting"), rs.getLong("active"), rs.getLong("completed"), rs.getLong("no_show")));
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public PublicSnapshot publicSnapshot() {
        // Explicit projection: private ticket records are NEVER serialised here.
        List<PublicTicket> called = db.query("SELECT number,state,assigned_to FROM ticket WHERE state IN ('CALLED','IN_SERVICE') ORDER BY number,id", (rs,row) ->
            new PublicTicket(number(rs.getLong("number")), State.valueOf(rs.getString("state")), "Desk " + rs.getString("assigned_to")));
        return new PublicSnapshot(isOpen(), counts(), called, joinUrl);
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public StudentSnapshot studentSnapshot(UUID owner) {
        Ticket own = first("SELECT * FROM ticket WHERE owner=? ORDER BY number DESC,id DESC LIMIT 1", owner);
        long ahead = 0;
        Integer minutes = null;
        String note = "An estimate will appear when you join";
        if (own != null && own.state() == State.WAITING) {
            long n = Long.parseLong(own.number().substring(2));
            ahead = db.queryForObject("SELECT count(*) FROM ticket WHERE state='WAITING' AND number < ?", Long.class, n);
            long capacity = db.queryForObject("SELECT count(*) FROM staff WHERE enabled AND last_seen > now()-interval '90 seconds'", Long.class);
            var durations = db.queryForList("SELECT extract(epoch FROM finished_at-started_at)/60.0 AS minutes FROM ticket WHERE state='COMPLETED' AND finished_at > now()-interval '7 days' ORDER BY finished_at DESC LIMIT 20", Double.class);
            if (capacity == 0) note = "Waiting for staff to come online";
            else if (durations.size() < 3) note = "Not enough service history for an estimate yet";
            else {
                long busy = db.queryForObject("SELECT count(*) FROM ticket WHERE state IN ('CALLED','IN_SERVICE')", Long.class);
                double average = durations.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
                minutes = (int) Math.min(1440, Math.max(1, Math.ceil(average * (ahead + busy) / capacity)));
                note = "Approximate; based on recent service times and online staff";
            }
        } else if (own != null) note = "See your ticket status for the next step";
        return new StudentSnapshot(isOpen(), own, ahead, minutes, note);
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public StaffSnapshot staffSnapshot(String staff) {
        db.update("UPDATE staff SET last_seen=now() WHERE username=?", staff);
        return new StaffSnapshot(isOpen(), counts(), db.query("SELECT * FROM ticket WHERE state='WAITING' ORDER BY number,id", TICKET),
            db.query("SELECT * FROM ticket WHERE state IN ('CALLED','IN_SERVICE') ORDER BY number,id", TICKET), staff);
    }
}
