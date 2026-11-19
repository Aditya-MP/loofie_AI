package com.loofie.core.job.api;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.loofie.core.job.app.JobActions;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.storage.Uploads;

/**
 * F6 photos on the upload register (F7 D8): the creator's receipt and damage photos, the brand's handover photo. The
 * key returned here is what {@code photoRef}, {@code evidenceRef} or {@code handoverPhotoRef} then carries.
 */
@RestController
class JobUploadController {

    /** @param sizeBytes the file's exact size: the signed URL accepts only that size */
    record UploadRequest(@NotNull Uploads.Purpose purpose, @NotNull @Size(max = 40) String contentType,
                         Long sizeBytes) {
    }

    private final JobActions actions;

    JobUploadController(JobActions actions) {
        this.actions = actions;
    }

    @PostMapping("/api/core/jobs/{jobId}/uploads")
    Uploads.UploadTicket upload(Actor actor, @PathVariable UUID jobId, @Valid @RequestBody UploadRequest body) {
        return actions.ticket(actor, jobId, body.purpose(), body.contentType(), body.sizeBytes());
    }
}
