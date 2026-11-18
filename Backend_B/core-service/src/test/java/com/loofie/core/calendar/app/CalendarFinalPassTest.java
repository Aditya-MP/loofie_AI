package com.loofie.core.calendar.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.loofie.core.calendar.app.HolidayYearImport.Change;
import com.loofie.core.calendar.app.HolidayYearImport.Item;
import com.loofie.core.calendar.app.HolidayYearImport.RejectedRows;
import com.loofie.core.calendar.app.HolidayYearImport.Result;
import com.loofie.core.calendar.app.HolidayYearImport.Row;
import com.loofie.core.calendar.infra.HolidayRepository;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;
import com.loofie.core.notification.app.NotificationService;
import com.loofie.core.support.IntegrationTest;
import com.loofie.core.support.MutableClock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Calendar final pass (docs/features/working-day-calendar.md "Final pass"): year import, alarm threshold, ops notice. */
@IntegrationTest
@AutoConfigureMockMvc
class CalendarFinalPassTest {

    /** A year nothing else touches. */
    static final int YEAR = 2031;

    @Autowired HolidayYearImport yearImport;
    @Autowired HolidayAdminService admin;
    @Autowired HolidayRepository holidays;
    @Autowired NotificationService notifications;
    @Autowired JdbcClient jdbc;
    @Autowired MutableClock clock;
    @Autowired MockMvc mvc;

    final Actor ops = Actor.ops(UUID.randomUUID());
    Instant before;

    @BeforeEach
    void clean() {
        before = clock.instant();
        jdbc.sql("DELETE FROM holidays WHERE extract(year FROM holiday_date) = ?").param(YEAR).update();
        jdbc.sql("DELETE FROM notifications WHERE kind = ?").param(HolidayCoverage.NOTICE).update();
    }

    @AfterEach
    void restore() {
        clock.set(before);
        jdbc.sql("DELETE FROM holidays WHERE extract(year FROM holiday_date) = ?").param(YEAR).update();
        jdbc.sql("DELETE FROM notifications WHERE kind = ?").param(HolidayCoverage.NOTICE).update();
    }

    // ---- K1. Year import ----

    @Test
    void aPreviewSaysWhatWouldChangeAndSavesNothing() {
        Result preview = yearImport.importYear(ops, YEAR, list(), true);

        assertThat(preview.applied()).isFalse();
        assertThat(preview.added()).isEqualTo(4);
        assertThat(preview.changes()).extracting(Item::change).containsOnly(Change.ADDED);
        assertThat(preview.note()).startsWith("Preview only");
        assertThat(admin.list(ops, YEAR)).isEmpty();
        assertThat(history()).isEmpty();
    }

    @Test
    void applyingReplacesTheYearAndRecordsEveryChange() {
        Result first = yearImport.importYear(ops, YEAR, list(), false);
        assertThat(first.applied()).isTrue();
        assertThat(admin.list(ops, YEAR)).hasSize(4);

        // The corrected list: Holi renamed, the Bengal day dropped, Diwali unchanged, Christmas added.
        List<Row> corrected = List.of(
                new Row(LocalDate.of(YEAR, 1, 26), "", "Republic Day"),
                new Row(LocalDate.of(YEAR, 3, 9), null, "Holi (Dhulandi)"),
                new Row(LocalDate.of(YEAR, 11, 14), "", "Diwali"),
                new Row(LocalDate.of(YEAR, 12, 25), "", "Christmas"));
        Result second = yearImport.importYear(ops, YEAR, corrected, false);

        assertThat(second.added()).isEqualTo(1);
        assertThat(second.renamed()).isEqualTo(1);
        assertThat(second.removed()).isEqualTo(1);
        assertThat(second.unchanged()).isEqualTo(2);
        assertThat(second.changes()).filteredOn(i -> i.change() == Change.RENAMED).singleElement()
                .satisfies(i -> {
                    assertThat(i.oldName()).isEqualTo("Holi");
                    assertThat(i.newName()).isEqualTo("Holi (Dhulandi)");
                });
        assertThat(admin.list(ops, YEAR)).extracting(h -> h.name())
                .containsExactly("Republic Day", "Holi (Dhulandi)", "Diwali", "Christmas");
        // Additions and renames are applied first, then removals.
        assertThat(history()).extracting(c -> c.action().name())
                .containsExactly("INSERT", "INSERT", "INSERT", "INSERT", "RENAME", "INSERT", "DELETE");
        assertThat(history()).allSatisfy(c -> assertThat(c.changedBy()).isEqualTo(ops.userId()));
    }

    @Test
    void oneBadRowAndNothingIsAppliedWithEveryProblemListed() {
        List<Row> rows = new ArrayList<>(list());
        rows.add(new Row(LocalDate.of(YEAR + 1, 1, 1), "", "New Year next year")); // 5: wrong year
        rows.add(new Row(LocalDate.of(YEAR, 4, 14), "BENGAL", "Poila Boishakh"));    // 6: bad state
        rows.add(new Row(LocalDate.of(YEAR, 1, 26), " ", "Republic Day again"));     // 7: duplicate (national)
        rows.add(new Row(LocalDate.of(YEAR, 5, 1), "MH", "  "));                     // 8: no name
        rows.add(new Row(null, "", "No date"));                                      // 9: no date

        assertThatThrownBy(() -> yearImport.importYear(ops, YEAR, rows, false))
                .isInstanceOfSatisfying(RejectedRows.class, e -> assertThat(e.rows())
                        .extracting(HolidayYearImport.RowError::row).containsExactly(5, 6, 7, 8, 9));
        assertThat(admin.list(ops, YEAR)).as("the good rows were not applied either").isEmpty();
        assertThat(history()).isEmpty();
    }

    @Test
    void stateCodesAreStoredAsTheCurrentCodeAndOnlyOpsMayImport() {
        Result r = yearImport.importYear(ops, YEAR, List.of(
                new Row(LocalDate.of(YEAR, 4, 14), "wb", "Poila Boishakh"),
                new Row(LocalDate.of(YEAR, 4, 15), "OR", "Odia New Year")), false);
        assertThat(r.changes()).extracting(Item::stateCode).containsExactly("WB", "OD");
        assertThat(r.stateHolidaysInUse()).isTrue();
        assertThat(r.note()).contains("count for brands whose pickup state matches");

        for (Actor notOps : List.of(Actor.brandAdmin(UUID.randomUUID(), UUID.randomUUID()),
                Actor.creator(UUID.randomUUID()), Actor.system())) {
            assertThatThrownBy(() -> yearImport.importYear(notOps, YEAR, list(), true))
                    .isInstanceOf(ActorNotAllowedException.class);
        }
        assertThat(yearImport.importYear(Actor.opsLead(UUID.randomUUID()), YEAR, List.of(), true).removed())
                .as("an empty list would remove both; previewed only").isEqualTo(2);
        assertThat(admin.list(ops, YEAR)).hasSize(2);
    }

    @Test
    void twoImportsOfTheSameYearAtOnceDoNotDoubleTheHistory() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Result>> runs = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    go.await();
                    return yearImport.importYear(ops, YEAR, list(), false);
                }));
            }
            go.countDown();
            int added = 0;
            for (Future<Result> run : runs) {
                added += run.get(30, TimeUnit.SECONDS).added();
            }
            assertThat(added).as("one adds, the other finds them in place").isEqualTo(4);
        }
        assertThat(history()).hasSize(4);
    }

    @Test
    void theApiPreviewsByDefaultAndAnswers422WithTheBadRows() throws Exception {
        String body = """
                [{"date":"%d-01-26","stateCode":"","name":"Republic Day"},
                 {"date":"%d-02-30","stateCode":"","name":"Not a day"}]
                """.formatted(YEAR, YEAR);
        mvc.perform(put("/api/core/ops/holidays/years/{y}", YEAR).header("X-Dev-Role", "OPS")
                        .header("X-Dev-User-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()); // 30 February is not a date at all

        String bad = """
                [{"date":"%d-01-26","stateCode":"","name":"Republic Day"},
                 {"date":"%d-01-26","stateCode":"XX","name":"Typo state"}]
                """.formatted(YEAR, YEAR);
        mvc.perform(put("/api/core/ops/holidays/years/{y}", YEAR).param("dryRun", "false").header("X-Dev-Role", "OPS")
                        .header("X-Dev-User-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("invalid-holiday-rows")))
                .andExpect(jsonPath("$.rows[0].row").value(2));

        String good = """
                [{"date":"%d-01-26","stateCode":"","name":"Republic Day"}]
                """.formatted(YEAR);
        mvc.perform(put("/api/core/ops/holidays/years/{y}", YEAR).header("X-Dev-Role", "OPS")
                        .header("X-Dev-User-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(good))
                .andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.added").value(1));
        assertThat(admin.list(ops, YEAR)).isEmpty();

        mvc.perform(put("/api/core/ops/holidays/years/{y}", YEAR).header("X-Dev-Role", "BRAND_ADMIN")
                        .header("X-Dev-User-Id", UUID.randomUUID().toString())
                        .header("X-Dev-Org-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(good))
                .andExpect(status().isForbidden());
    }

    // ---- K2 + K3. Alarm threshold and the ops notice ----

    @Test
    void aHalfEnteredYearIsMissingOnceTheExpectedCountIsRaised() {
        clock.set(Instant.parse("2026-12-05T06:00:00Z")); // 2027 has only the 3 seeded fixed days
        HolidayCoverage atThree = coverage(3);
        atThree.refresh();
        assertThat(atThree.nextYearMissing()).as("3 expected: looks complete").isFalse();
        assertThat(opsNotices()).isZero();

        HolidayCoverage atFifteen = coverage(15);
        atFifteen.refresh();
        assertThat(atFifteen.nextYearMissing()).as("15 expected: 2027 is half-entered").isTrue();
        assertThat(opsNotices()).isEqualTo(1);
        var payload = notifications.inbox(ops, false, null, null, 100).stream()
                .filter(i -> i.kind().equals(HolidayCoverage.NOTICE)).findFirst().orElseThrow().payload();
        assertThat(payload).containsEntry("why", "NEXT_YEAR_MISSING").containsEntry("year", 2027)
                .containsEntry("nationalFound", 3).containsEntry("expected", 15);

        assertThatThrownBy(() -> coverage(2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void opsIsToldAtMostOnceADayEvenWithSeveralInstancesChecking() throws Exception {
        clock.set(Instant.parse("2026-12-05T06:00:00Z"));
        List<HolidayCoverage> instances = List.of(coverage(15), coverage(15), coverage(15), coverage(15));
        CountDownLatch go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(instances.size())) {
            List<Future<?>> runs = new ArrayList<>();
            for (HolidayCoverage c : instances) {
                runs.add(pool.submit(() -> {
                    go.await();
                    c.refresh();
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> run : runs) {
                run.get(30, TimeUnit.SECONDS);
            }
        }
        instances.getFirst().refresh(); // six hours later, same day
        assertThat(opsNotices()).isEqualTo(1);

        clock.set(Instant.parse("2026-12-06T06:00:00Z")); // next day: told again
        instances.getFirst().refresh();
        assertThat(opsNotices()).isEqualTo(2);
    }

    @Test
    void nothingMissingMeansNoNotice() {
        clock.set(Instant.parse("2026-11-20T06:00:00Z")); // November: next year not due yet; next 365 days have 3+
        coverage(3).refresh();
        assertThat(opsNotices()).isZero();
    }

    // ---- helpers ----

    /** This test's own history rows (the history is append-only, so earlier tests' rows stay). */
    private List<com.loofie.core.calendar.domain.HolidayChange> history() {
        return admin.changes(ops, YEAR).stream().filter(c -> ops.userId().equals(c.changedBy())).toList();
    }

    private HolidayCoverage coverage(int expected) {
        return new HolidayCoverage(holidays, notifications, clock, new SimpleMeterRegistry(), expected);
    }

    private long opsNotices() {
        return jdbc.sql("SELECT count(*) FROM notifications WHERE kind = ? AND recipient_type = 'OPS'")
                .param(HolidayCoverage.NOTICE).query(Long.class).single();
    }

    private static List<Row> list() {
        return List.of(
                new Row(LocalDate.of(YEAR, 1, 26), "", "Republic Day"),
                new Row(LocalDate.of(YEAR, 3, 9), "", "Holi"),
                new Row(LocalDate.of(YEAR, 10, 20), "WB", "Durga Puja"),
                new Row(LocalDate.of(YEAR, 11, 14), "", "Diwali"));
    }
}
