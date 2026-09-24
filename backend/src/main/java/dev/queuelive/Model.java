package dev.queuelive;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class Model {
    private Model() {}
    enum State { WAITING, CALLED, IN_SERVICE, COMPLETED, CANCELLED, NO_SHOW }
    record Ticket(UUID id, String number, String displayName, State state, String assignedTo,
                  Instant createdAt, Instant calledAt, Instant startedAt, Instant finishedAt) {}
    record Mutation(Ticket ticket, String message) {}
    record Counts(long waiting, long active, long completed, long noShow) {}
    record PublicTicket(String number, State state, String counter) {}
    record PublicSnapshot(boolean open, Counts counts, List<PublicTicket> called, String joinUrl) {}
    record StudentSnapshot(boolean open, Ticket ticket, long peopleAhead, Integer approximateMinutes, String estimateNote) {}
    record StaffSnapshot(boolean open, Counts counts, List<Ticket> waiting, List<Ticket> active, String username) {}
    record Changed() {}
}
