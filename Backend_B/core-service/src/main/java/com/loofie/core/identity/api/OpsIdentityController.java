package com.loofie.core.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.loofie.core.identity.app.AccountAdminService;
import com.loofie.core.identity.app.StaffService;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;

/** Staff tools: invites, authenticator resets, suspensions, manual contact changes, audit log. */
@RestController
@RequestMapping("/api/core/ops")
class OpsIdentityController {

    record InviteRequest(String email, ActorRole role) {
    }

    record ReasonRequest(String reason) {
    }

    record ContactRequest(String newContact, String reason) {
    }

    private final StaffService staff;
    private final AccountAdminService admin;

    OpsIdentityController(StaffService staff, AccountAdminService admin) {
        this.staff = staff;
        this.admin = admin;
    }

    @PostMapping("/staff/invites")
    ResponseEntity<StaffService.Invite> invite(Actor actor, @RequestBody InviteRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(staff.invite(actor, body == null ? null : body.email(),
                body == null ? null : body.role()));
    }

    @PostMapping("/staff/{userId}/authenticator-reset")
    ResponseEntity<Void> resetAuthenticator(Actor actor, @PathVariable UUID userId, @RequestBody ReasonRequest body) {
        staff.resetAuthenticator(actor, userId, body == null ? null : body.reason());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/accounts/{userId}")
    AccountAdminService.AccountSummary view(Actor actor, @PathVariable UUID userId) {
        return admin.view(actor, userId);
    }

    @PostMapping("/accounts/{userId}/suspend")
    AccountAdminService.AccountSummary suspend(Actor actor, @PathVariable UUID userId,
                                               @RequestBody ReasonRequest body) {
        return admin.suspend(actor, userId, body == null ? null : body.reason());
    }

    @PostMapping("/accounts/{userId}/unsuspend")
    AccountAdminService.AccountSummary unsuspend(Actor actor, @PathVariable UUID userId,
                                                 @RequestBody ReasonRequest body) {
        return admin.unsuspend(actor, userId, body == null ? null : body.reason());
    }

    @PostMapping("/accounts/{userId}/contact")
    AccountAdminService.AccountSummary changeContact(Actor actor, @PathVariable UUID userId,
                                                     @RequestBody ContactRequest body) {
        return admin.changeContact(actor, userId, body == null ? null : body.newContact(),
                body == null ? null : body.reason());
    }

    @GetMapping("/audit-log")
    List<com.loofie.core.identity.domain.AuditEntry> auditLog(Actor actor, @RequestParam(required = false) UUID targetUserId,
                                         @RequestParam(required = false) Instant before,
                                         @RequestParam(defaultValue = "50") int limit) {
        return admin.auditLog(actor, targetUserId, before, limit);
    }
}
