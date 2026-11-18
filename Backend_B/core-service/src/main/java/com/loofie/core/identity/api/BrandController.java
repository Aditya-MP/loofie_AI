package com.loofie.core.identity.api;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

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

import com.loofie.core.identity.app.CompanyService;
import com.loofie.core.identity.app.SessionAuthenticator;
import com.loofie.core.identity.app.TeamService;
import com.loofie.core.identity.domain.Organization;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;

/** A brand's company and team (flows 4 and 5). Members read; admins change. */
@RestController
@RequestMapping("/api/core/brand")
class BrandController {

    record GstinRequest(String gstin) {
    }

    record InviteRequest(String email, ActorRole role) {
    }

    record RoleRequest(ActorRole role) {
    }

    record AcceptRequest(String inviteToken) {
    }

    private final CompanyService companies;
    private final TeamService team;
    private final SessionAuthenticator authenticator;

    BrandController(CompanyService companies, TeamService team, SessionAuthenticator authenticator) {
        this.companies = companies;
        this.team = team;
        this.authenticator = authenticator;
    }

    /** Right after brand signup: the person has no company yet, so this takes the session, not an Actor. */
    @PostMapping("/company")
    ResponseEntity<CompanyService.CompanyView> create(@RequestBody CompanyService.CompanyForm form,
                                                      HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(companies.create(authenticator.requireFull(request),
                form));
    }

    @GetMapping("/company")
    CompanyService.CompanyView get(Actor actor) {
        return companies.get(actor);
    }

    @PutMapping("/company")
    CompanyService.CompanyView update(Actor actor, @RequestBody CompanyService.CompanyForm form) {
        return companies.update(actor, form);
    }

    @PutMapping("/company/gstin")
    CompanyService.CompanyView gstin(Actor actor, @RequestBody GstinRequest body) {
        return companies.setGstin(actor, body == null ? null : body.gstin());
    }

    @PostMapping("/team/invites")
    ResponseEntity<TeamService.Invite> invite(Actor actor, @RequestBody InviteRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(team.invite(actor, body == null ? null : body.email(),
                body == null ? null : body.role()));
    }

    @DeleteMapping("/team/invites/{inviteId}")
    ResponseEntity<Void> revoke(Actor actor, @PathVariable UUID inviteId) {
        team.revokeInvite(actor, inviteId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/team/members/{userId}/role")
    ResponseEntity<Void> role(Actor actor, @PathVariable UUID userId, @RequestBody RoleRequest body) {
        team.changeRole(actor, userId, body == null ? null : body.role());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/team/members/{userId}")
    ResponseEntity<Void> remove(Actor actor, @PathVariable UUID userId) {
        team.remove(actor, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/team/leave")
    ResponseEntity<Void> leave(Actor actor) {
        team.leave(actor);
        return ResponseEntity.noContent().build();
    }

    /** A logged-in brand user without a company opens a teammate invite. */
    @PostMapping("/invites/accept")
    Organization accept(@RequestBody AcceptRequest body, HttpServletRequest request) {
        return team.acceptAsExistingUser(authenticator.requireFull(request), body == null ? null : body.inviteToken());
    }
}
