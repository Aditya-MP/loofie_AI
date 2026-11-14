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

import com.loofie.core.calendar.app.HolidayAdminService;
import com.loofie.core.calendar.domain.Holiday;
import com.loofie.core.job.domain.Actor;

/**
 * F10: ops edits the holiday table that brand-side working-day clocks skip.
 * {@code stateCode} empty or missing = national holiday; otherwise e.g. "WB" for a Bengal-only holiday.
 * HTTP mapping only: permissions and rules are in {@link HolidayAdminService}.
 */
@RestController
@RequestMapping("/api/core/ops/holidays")
class OpsHolidayController {

    record HolidayRequest(@NotNull LocalDate date,
                          @Pattern(regexp = "^[A-Za-z]{0,8}$") String stateCode,
                          @NotBlank @Size(max = 100) String name) {
    }

    private final HolidayAdminService holidays;

    OpsHolidayController(HolidayAdminService holidays) {
        this.holidays = holidays;
    }

    @GetMapping
    List<Holiday> list(Actor actor, @RequestParam int year) {
        return holidays.list(actor, year);
    }

    @PutMapping
    ResponseEntity<Void> upsert(Actor actor, @Valid @RequestBody HolidayRequest body) {
        boolean created = holidays.upsert(actor, body.date(), body.stateCode(), body.name());
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).build();
    }

    @DeleteMapping("/{date}")
    ResponseEntity<Void> delete(Actor actor, @PathVariable LocalDate date,
                                @RequestParam(required = false) String stateCode) {
        return holidays.delete(actor, date, stateCode) ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
