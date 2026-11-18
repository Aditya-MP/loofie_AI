package com.loofie.core.creator.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.loofie.core.creator.app.CreatorEvidenceService;
import com.loofie.core.creator.app.CreatorProfileService;
import com.loofie.core.creator.app.InstagramConnectService;
import com.loofie.core.creator.domain.CreatorForm;
import com.loofie.core.creator.domain.Evidence;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.storage.Uploads;

/** A creator's own passport, Instagram connection and screenshots (round C, flows 1 and 2). Creators only. */
@RestController
@RequestMapping("/api/core/creator")
class CreatorController {

    record ListingRequest(Boolean listed) {
    }

    record UploadRequest(String contentType) {
    }

    record AttachRequest(String uploadKey, Evidence.Kind kind) {
    }

    private final CreatorProfileService profiles;
    private final InstagramConnectService instagram;
    private final CreatorEvidenceService evidence;

    CreatorController(CreatorProfileService profiles, InstagramConnectService instagram,
                      CreatorEvidenceService evidence) {
        this.profiles = profiles;
        this.instagram = instagram;
        this.evidence = evidence;
    }

    @GetMapping("/profile")
    CreatorProfileService.ProfileView profile(Actor actor) {
        return profiles.mine(actor);
    }

    /** Saves progress; completes the passport when nothing is missing (see {@code missing} in the answer). */
    @PutMapping("/profile")
    CreatorProfileService.ProfileView save(Actor actor, @RequestBody CreatorForm form) {
        return profiles.save(actor, form);
    }

    /** Pause (false) or resume (true) her listing. */
    @PutMapping("/listing")
    CreatorProfileService.ProfileView listing(Actor actor, @RequestBody ListingRequest body) {
        return profiles.setListed(actor, body == null || body.listed() == null || body.listed());
    }

    /** Backend A's link to Meta's screen; the app sends her browser there. */
    @PostMapping("/instagram/connect")
    InstagramConnectService.Link connect(Actor actor) {
        return instagram.start(actor);
    }

    /** Where her connection stands, asked of Backend A (never taken from the return URL). Poll every 2 s. */
    @GetMapping("/instagram")
    InstagramConnectService.Status instagramStatus(Actor actor) {
        return instagram.status(actor);
    }

    @DeleteMapping("/instagram")
    InstagramConnectService.Status disconnect(Actor actor) {
        return instagram.disconnect(actor);
    }

    /** Step 1 of a screenshot: a 5-minute URL to PUT the image to, with the headers to send. */
    @PostMapping("/uploads")
    ResponseEntity<Uploads.UploadTicket> upload(Actor actor, @RequestBody UploadRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(evidence.uploadTicket(actor,
                body == null ? null : body.contentType()));
    }

    /** Step 2: attach the uploaded file as Insights proof or a collaboration proof. */
    @PostMapping("/evidence")
    ResponseEntity<CreatorProfileService.ProfileView> attach(Actor actor, @RequestBody AttachRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(evidence.attach(actor,
                body == null ? null : body.uploadKey(), body == null ? null : body.kind()));
    }

    @DeleteMapping("/evidence/{evidenceId}")
    CreatorProfileService.ProfileView remove(Actor actor, @PathVariable UUID evidenceId) {
        return evidence.remove(actor, evidenceId);
    }
}
