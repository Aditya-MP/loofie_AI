package com.loofie.core.calendar.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One recorded change to the holiday list (append-only history). {@code changedBy} null = migration seed. */
public record HolidayChange(Instant changedAt, UUID changedBy, Action action, LocalDate date, String stateCode,
                            String oldName, String newName) {

    public enum Action { INSERT, RENAME, DELETE }

    /** What an add-or-rename did. */
    public enum Outcome { CREATED, RENAMED, UNCHANGED }
}
