package com.loofie.core.ops.api;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.loofie.core.calendar.HolidayCalendar;
import com.loofie.core.calendar.HolidayCalendar.Holiday;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;

/**
 * F10: ops edits the holiday table that brand-side working-day clocks skip.
 * {@code stateCode} empty or missing = national holiday; otherwise e.g. "WB" for a Bengal-only holiday.
 * Changes apply to clocks started afterwards; running deadlines are not recalculated.
 */
@RestController
@RequestMapping("/api/core/ops/holidays")
class OpsHolidayController {

    record HolidayRequest(@NotNull LocalDate date,
                          @Pattern(regexp = "^[A-Za-z]{0,8}$") String stateCode,
                          @NotBlank @Size(max = 100) String name) {
    }

    private final HolidayCalendar calendar;

    OpsHolidayController(HolidayCalendar calendar) {
        this.calendar = calendar;
    }

    @GetMapping
    List<Holiday> list(Actor actor, @RequestParam int year) {
        requireOps(actor);
        return calendar.list(year);
    }

    @PutMapping
    ResponseEntity<Void> upsert(Actor actor, @Valid @RequestBody HolidayRequest body) {
        requireOps(actor);
        boolean created = calendar.upsert(body.date(), body.stateCode(), body.name(), actor.userId());
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).build();
    }

    @DeleteMapping("/{date}")
    ResponseEntity<Void> delete(Actor actor, @PathVariable LocalDate date,
                                @RequestParam(required = false) String stateCode) {
        requireOps(actor);
        return calendar.delete(date, stateCode) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private static void requireOps(Actor actor) {
        if (actor.role() != ActorRole.OPS) {
            throw new ActorNotAllowedException("Holidays are edited by OPS only");
        }
    }
}
