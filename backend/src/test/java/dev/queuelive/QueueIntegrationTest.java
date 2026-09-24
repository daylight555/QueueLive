package dev.queuelive;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.servlet.http.Cookie;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import static dev.queuelive.Model.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest(properties={"queuelive.public-base-url=http://localhost:8080", "queuelive.demo.enabled=false", "server.servlet.session.cookie.secure=false"})
@AutoConfigureMockMvc
class QueueIntegrationTest {
    // Default runs use Testcontainers. An explicit external URL supports isolated, real PostgreSQL
    // environments without Docker. NEVER point it at a database with valuable data: setup truncates tables.
    static PostgreSQLContainer<?> postgres;
    @DynamicPropertySource static void database(DynamicPropertyRegistry props) {
        String external = System.getenv("TEST_DATABASE_URL");
        if (external == null) {
            postgres = new PostgreSQLContainer<>("postgres:17.11-alpine3.24"); postgres.start();
            props.add("spring.datasource.url", postgres::getJdbcUrl);
            props.add("spring.datasource.username", postgres::getUsername);
            props.add("spring.datasource.password", postgres::getPassword);
        } else {
            props.add("spring.datasource.url", () -> external);
            props.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DATABASE_USER", "postgres"));
            props.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
        }
    }
    @Autowired QueueService queue;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean LiveUpdates updates;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder passwords;

    @BeforeEach void reset() {
        db.execute("TRUNCATE audit_event, operation_result, ticket, staff CASCADE");
        db.update("UPDATE help_queue SET is_open=true, next_number=1");
        for (String staff : List.of("alex", "sam"))
            db.update("INSERT INTO staff(username,password_hash) VALUES (?,?)", staff, passwords.encode("test-password-123"));
    }
    Ticket join(UUID owner) { return queue.join(owner, UUID.randomUUID(), "Student").ticket(); }

    @Test void fullFlowWithUtcTimestampsAndAudit() {
        UUID owner = UUID.randomUUID(); Ticket waiting = join(owner);
        assertThat(waiting.number()).isEqualTo("Q-001");
        Ticket called = queue.callNext("alex",UUID.randomUUID()).ticket();
        assertThat(called.id()).isEqualTo(waiting.id());
        assertThat(called.calledAt()).isNotNull();
        queue.transition("alex",called.id(),State.IN_SERVICE);
        Ticket complete = queue.transition("alex",called.id(),State.COMPLETED).ticket();
        assertThat(complete.finishedAt()).isAfterOrEqualTo(complete.startedAt());
        assertThat(queue.studentSnapshot(owner).ticket().state()).isEqualTo(State.COMPLETED);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_event",Long.class)).isEqualTo(4);
    }
    @Test void leaveAndNoShowAndRejoin() {
        UUID owner = UUID.randomUUID(); join(owner);
        assertThat(queue.leave(owner).ticket().state()).isEqualTo(State.CANCELLED);
        Ticket second = join(owner);
        queue.callNext("sam",UUID.randomUUID());
        assertThat(queue.transition("sam",second.id(),State.NO_SHOW).ticket().state()).isEqualTo(State.NO_SHOW);
        assertThat(queue.publicSnapshot().counts().noShow()).isEqualTo(1);
    }
    @Test void closedQueueDoesNotInterruptExistingTickets() {
        Ticket t = join(UUID.randomUUID()); queue.setOpen("alex",false);
        assertThatThrownBy(() -> join(UUID.randomUUID())).isInstanceOf(ApiException.class).hasMessageContaining("closed");
        assertThat(queue.callNext("alex",UUID.randomUUID()).ticket().id()).isEqualTo(t.id());
    }
    @Test void duplicateJoinAndRetryReturnOriginalOutcomeEvenAfterCompletion() {
        UUID owner = UUID.randomUUID(), key = UUID.randomUUID();
        Mutation original = queue.join(owner,key,"A");
        assertThat(queue.join(owner,UUID.randomUUID(),"B").ticket().id()).isEqualTo(original.ticket().id());
        queue.callNext("alex",UUID.randomUUID());
        queue.transition("alex",original.ticket().id(),State.IN_SERVICE);
        queue.transition("alex",original.ticket().id(),State.COMPLETED);
        assertThat(queue.join(owner,key,"A")).isEqualTo(original);
        assertThatThrownBy(() -> queue.join(owner,key,"Different")).isInstanceOf(ApiException.class).hasMessageContaining("different input");
        assertThat(db.queryForObject("SELECT count(*) FROM ticket",Long.class)).isEqualTo(1);
    }
    @Test void callRetryIsOriginalEvenWhenQueueChanges() {
        UUID key = UUID.randomUUID();
        assertThat(queue.callNext("alex",key).ticket()).isNull();
        join(UUID.randomUUID());
        assertThat(queue.callNext("alex",key).ticket()).isNull();
        UUID nextKey = UUID.randomUUID(); Mutation called = queue.callNext("alex",nextKey);
        queue.transition("alex",called.ticket().id(),State.NO_SHOW);
        assertThat(queue.callNext("alex",nextKey)).isEqualTo(called);
    }
    @Test void invalidTransitionsAndWrongStaffAreRejected() {
        UUID owner = UUID.randomUUID(); Ticket t = join(owner);
        queue.callNext("alex",UUID.randomUUID());
        assertThatThrownBy(() -> queue.transition("alex",t.id(),State.COMPLETED)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> queue.transition("sam",t.id(),State.IN_SERVICE)).isInstanceOf(ApiException.class).hasMessageContaining("not found");
        assertThatThrownBy(() -> queue.leave(owner)).isInstanceOf(ApiException.class);
        queue.transition("alex",t.id(),State.IN_SERVICE);
        assertThatThrownBy(() -> queue.transition("alex",t.id(),State.NO_SHOW)).isInstanceOf(ApiException.class);
    }

    // Each worker invokes a Spring proxy on a separate thread: independent real transactions.
    // A barrier releases both callers together, without timing guesses or sleeps.
    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<T> a = pool.submit(() -> {barrier.await(10,TimeUnit.SECONDS);return first.call();});
            Future<T> b = pool.submit(() -> {barrier.await(10,TimeUnit.SECONDS);return second.call();});
            return List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
    }
    @Test void simultaneousStaffNeverReceiveSameTicket() throws Exception {
        Ticket first = join(UUID.randomUUID()), second = join(UUID.randomUUID());
        var results = race(() -> queue.callNext("alex",UUID.randomUUID()), () -> queue.callNext("sam",UUID.randomUUID()));
        assertThat(results).extracting(r -> r.ticket().id()).containsExactlyInAnyOrder(first.id(),second.id());
        assertThat(queue.publicSnapshot().counts().active()).isEqualTo(2);
    }
    @Test void concurrentSameKeyJoinProducesOneResult() throws Exception {
        UUID owner=UUID.randomUUID(), key=UUID.randomUUID();
        var results=race(() -> queue.join(owner,key,"A"), () -> queue.join(owner,key,"A"));
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(db.queryForObject("SELECT count(*) FROM ticket",Long.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM operation_result",Long.class)).isEqualTo(1);
    }
    @Test void concurrentSameKeyCallProducesOneResult() throws Exception {
        join(UUID.randomUUID()); join(UUID.randomUUID()); UUID key=UUID.randomUUID();
        var results=race(() -> queue.callNext("alex",key), () -> queue.callNext("alex",key));
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(queue.publicSnapshot().counts().active()).isEqualTo(1);
    }
    @Test void oneAssignmentPerStaffAndDatabaseConstraints() {
        UUID owner=UUID.randomUUID(); Ticket t=join(owner); join(UUID.randomUUID());
        queue.callNext("alex",UUID.randomUUID());
        assertThatThrownBy(() -> queue.callNext("alex",UUID.randomUUID())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> db.update("UPDATE ticket SET state='CALLED',assigned_to='alex',called_at=now() WHERE state='WAITING'"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("INSERT INTO ticket(id,queue_id,number,owner,state) VALUES (?,1,99,?,'WAITING')",UUID.randomUUID(),owner))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(t.state()).isEqualTo(State.WAITING);
    }
    @Test void rollbackDoesNotPersistTicketOrRetryRecord() {
        org.mockito.Mockito.clearInvocations(updates);
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {join(UUID.randomUUID());status.setRollbackOnly();});
        assertThat(db.queryForObject("SELECT count(*) FROM ticket",Long.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM operation_result",Long.class)).isZero();
        org.mockito.Mockito.verify(updates,org.mockito.Mockito.never()).changed(org.mockito.ArgumentMatchers.any());
        join(UUID.randomUUID());
        org.mockito.Mockito.verify(updates).changed(org.mockito.ArgumentMatchers.any());
    }
    @Test void sessionOwnershipCsrfAndStaffAuthorization() throws Exception {
        var sessionResponse = mvc.perform(get("/api/session")).andExpect(status().isOk()).andExpect(jsonPath("csrfToken").exists()).andReturn().getResponse();
        Cookie student = sessionResponse.getCookie("SESSION");
        var response=mvc.perform(post("/api/student/join").cookie(student).with(csrf())
            .header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"displayName\":\"Private Name\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id=json.readTree(response).path("ticket").path("id").asText();
        mvc.perform(get("/api/student")).andExpect(status().isOk()).andExpect(jsonPath("ticket").isEmpty());
        mvc.perform(post("/api/student/leave").with(csrf())).andExpect(status().isConflict());
        mvc.perform(get("/api/student/"+id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/student").cookie(student)).andExpect(jsonPath("ticket.id").value(id));
        mvc.perform(post("/api/student/leave").cookie(student)).andExpect(status().isForbidden());
        mvc.perform(get("/api/staff")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/staff/call-next").with(csrf()).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isUnauthorized());
    }
    @Test void realStaffLoginAndLogoutRevokesAccess() throws Exception {
        var login=mvc.perform(post("/api/login").with(csrf()).param("username","alex").param("password","test-password-123"))
            .andExpect(status().isNoContent()).andReturn();
        Cookie staffSession = login.getResponse().getCookie("SESSION");
        mvc.perform(get("/api/staff").cookie(staffSession)).andExpect(status().isOk());
        mvc.perform(post("/api/logout").cookie(staffSession).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/staff")).andExpect(status().isUnauthorized());
    }
    @Test void publicProjectionContainsNoPrivateFieldsAndSnapshotRecoversChanges() throws Exception {
        UUID owner=UUID.randomUUID(); Ticket t=queue.join(owner,UUID.randomUUID(),"Very Private").ticket();
        queue.callNext("alex",UUID.randomUUID());
        String content=mvc.perform(get("/api/public")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(content).doesNotContain("Very Private",owner.toString(),t.id().toString(),"displayName","createdAt","password","owner");
        assertThat(queue.studentSnapshot(owner).ticket().state()).isEqualTo(State.CALLED);
        queue.transition("alex",t.id(),State.IN_SERVICE);
        assertThat(queue.studentSnapshot(owner).ticket().state()).isEqualTo(State.IN_SERVICE);
    }
    @Test void estimatesRequireHistoryAndOnlineStaff() {
        UUID owner=UUID.randomUUID();join(owner);
        assertThat(queue.studentSnapshot(owner).approximateMinutes()).isNull();
        assertThat(queue.studentSnapshot(owner).estimateNote()).contains("staff");
        queue.staffSnapshot("alex");
        assertThat(queue.studentSnapshot(owner).estimateNote()).contains("history");
    }
    @Test void simultaneousDifferentKeysStillGiveOneTicketPerStudent() throws Exception {
        UUID owner=UUID.randomUUID();
        var results=race(() -> queue.join(owner,UUID.randomUUID(),"A"), () -> queue.join(owner,UUID.randomUUID(),"A"));
        assertThat(results.get(0).ticket().id()).isEqualTo(results.get(1).ticket().id());
        assertThat(db.queryForObject("SELECT count(*) FROM ticket",Long.class)).isEqualTo(1);
    }
    @Test void simultaneousStaffActionsWithDifferentKeysPermitOneAssignment() throws Exception {
        join(UUID.randomUUID());join(UUID.randomUUID());
        Callable<Boolean> call=() -> {
            try {queue.callNext("alex",UUID.randomUUID());return true;}
            catch(ApiException ex) {assertThat(ex.status).isEqualTo(409);return false;}
        };
        assertThat(race(call,call)).containsExactlyInAnyOrder(true,false);
        assertThat(queue.publicSnapshot().counts().active()).isEqualTo(1);
    }
    @Test void supportedEstimateUsesServiceDurationsAndCapacity() {
        for(int i=0;i<3;i++) {
            Ticket t=join(UUID.randomUUID());queue.callNext("alex",UUID.randomUUID());
            queue.transition("alex",t.id(),State.IN_SERVICE);queue.transition("alex",t.id(),State.COMPLETED);
            db.update("UPDATE ticket SET started_at=now()-interval '5 minutes',finished_at=now() WHERE id=?",t.id());
        }
        join(UUID.randomUUID()); UUID owner=UUID.randomUUID();join(owner);queue.staffSnapshot("alex");
        assertThat(queue.studentSnapshot(owner).peopleAhead()).isEqualTo(1);
        assertThat(queue.studentSnapshot(owner).approximateMinutes()).isEqualTo(5);
    }

}
