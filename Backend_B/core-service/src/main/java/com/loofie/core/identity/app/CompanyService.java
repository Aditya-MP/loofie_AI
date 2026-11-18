package com.loofie.core.identity.app;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.loofie.core.calendar.domain.Holiday;
import com.loofie.core.calendar.domain.StateCodes.InvalidStateCodeException;
import com.loofie.core.common.Ids;
import com.loofie.core.identity.domain.Account;
import com.loofie.core.identity.domain.AccountType;
import com.loofie.core.identity.domain.Gstin;
import com.loofie.core.identity.domain.IdentityExceptions.CompanyNotFoundException;
import com.loofie.core.identity.domain.IdentityExceptions.GstinTakenException;
import com.loofie.core.identity.domain.IdentityExceptions.InvalidRequestException;
import com.loofie.core.identity.domain.Organization;
import com.loofie.core.identity.domain.Organization.BillingAddress;
import com.loofie.core.identity.domain.SessionView;
import com.loofie.core.identity.infra.InviteRepository;
import com.loofie.core.identity.infra.OrganizationRepository;
import com.loofie.core.identity.infra.UserRepository;
import com.loofie.core.job.domain.Actor;
import com.loofie.core.job.domain.ActorRole;
import com.loofie.core.job.domain.JobExceptions.ActorNotAllowedException;

/**
 * A brand's company (flow 4): created by the first person right after signup, who becomes BRAND_ADMIN. Admins edit
 * it; every member reads it. GSTIN is optional at first and needed before the first brief (E3); it is set once, and
 * after that only ops changes it (with a recorded reason).
 */
@Service
public class CompanyService {

    /** @param profile what the company form sends */
    public record CompanyForm(String legalName, String gstin, BillingAddress billing, String pickupState) {
    }

    /** What brand members (and ops) see. */
    public record CompanyView(Organization company, boolean readyForBriefs, List<Organization.Member> members,
                              List<Organization.PendingInvite> pendingInvites) {
    }

    private final OrganizationRepository organizations;
    private final InviteRepository invites;
    private final UserRepository users;
    private final AuditLog audit;
    private final Clock clock;

    public CompanyService(OrganizationRepository organizations, InviteRepository invites, UserRepository users,
                          AuditLog audit, Clock clock) {
        this.organizations = organizations;
        this.invites = invites;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    /** The company form after brand signup. The person becomes its first BRAND_ADMIN. */
    @Transactional
    public CompanyView create(SessionView s, CompanyForm form) {
        Account a = users.findForUpdate(s.userId()).orElseThrow(); // one company per person, even on a double tap
        if (a.type() != AccountType.BRAND) {
            throw new ActorNotAllowedException("Only brand accounts create a company");
        }
        if (organizations.membership(a.id()).isPresent()) {
            throw new InvalidRequestException("You already belong to a company on Loofie");
        }
        Valid v = validate(form);
        String gstin = form.gstin() == null || form.gstin().isBlank() ? null : Gstin.normalise(form.gstin());
        Instant now = clock.instant();
        UUID id = Ids.newId();
        try {
            organizations.insert(new Organization(id, v.legalName, gstin, null, v.billing, v.pickupState, a.id(), now,
                    now));
        } catch (DuplicateKeyException e) {
            throw new GstinTakenException();
        }
        organizations.addMember(id, a.id(), ActorRole.BRAND_ADMIN.name(), a.id(), now);
        users.markOnboarded(a.id(), now);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("legalName", v.legalName);
        details.put("pickupState", v.pickupState);
        details.put("gstinGiven", gstin != null);
        audit.record(a.id(), ActorRole.BRAND_ADMIN.name(), AuditLog.ORG_CREATED, a.id(), id, null, details);
        return view(id, true);
    }

    public CompanyView get(Actor actor) {
        requireBrand(actor);
        return view(actor.orgId(), actor.role() == ActorRole.BRAND_ADMIN);
    }

    /** Name, billing address, pickup state. A new pickup state applies to new orders only (E2). */
    @Transactional
    public CompanyView update(Actor actor, CompanyForm form) {
        requireAdmin(actor);
        Organization before = organizations.findForUpdate(actor.orgId()).orElseThrow(CompanyNotFoundException::new);
        Valid v = validate(form);
        organizations.updateProfile(actor.orgId(), v.legalName, v.billing, v.pickupState, clock.instant());
        Map<String, Object> details = new LinkedHashMap<>();
        if (!before.pickupState().equals(v.pickupState)) {
            details.put("pickupState", before.pickupState() + " -> " + v.pickupState);
        }
        if (!before.legalName().equals(v.legalName)) {
            details.put("legalName", before.legalName() + " -> " + v.legalName);
        }
        audit.record(actor, AuditLog.ORG_UPDATED, null, actor.orgId(), null, details);
        return view(actor.orgId(), true);
    }

    /** The admin adds the GSTIN once. A wrong one is corrected by ops. */
    @Transactional
    public CompanyView setGstin(Actor actor, String raw) {
        requireAdmin(actor);
        Organization o = organizations.findForUpdate(actor.orgId()).orElseThrow(CompanyNotFoundException::new);
        if (o.gstin() != null) {
            throw new InvalidRequestException("The GSTIN is already set. Contact support to change it.");
        }
        String gstin = Gstin.normalise(raw);
        try {
            organizations.setGstin(o.id(), gstin, null, actor.userId(), clock.instant());
        } catch (DuplicateKeyException e) {
            throw new GstinTakenException();
        }
        audit.record(actor, AuditLog.GSTIN_SET, null, o.id(), null, Map.of("gstin", gstin));
        return view(o.id(), true);
    }

    /** Ops: open a company (recorded). */
    @Transactional
    public CompanyView opsView(Actor actor, UUID orgId) {
        StaffRules.requireOps(actor);
        CompanyView v = view(orgId, true);
        audit.record(actor, AuditLog.ORG_VIEWED, null, orgId, null, Map.of());
        return v;
    }

    /**
     * Ops sets or corrects a GSTIN. {@code shared} lets a second company use a GSTIN another already has (one company,
     * two brands, Q9); the reason is stored on the company and in the audit log.
     */
    @Transactional
    public CompanyView opsSetGstin(Actor actor, UUID orgId, String raw, boolean shared, String reason) {
        StaffRules.requireOps(actor);
        String why = StaffRules.requireReason(reason);
        Organization o = organizations.findForUpdate(orgId).orElseThrow(CompanyNotFoundException::new);
        String gstin = Gstin.normalise(raw);
        try {
            organizations.setGstin(o.id(), gstin, shared ? why : null, actor.userId(), clock.instant());
        } catch (DuplicateKeyException e) {
            throw new GstinTakenException();
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", String.valueOf(o.gstin()));
        details.put("to", gstin);
        details.put("shared", shared);
        audit.record(actor, AuditLog.GSTIN_SET_BY_OPS, null, o.id(), why, details);
        return view(o.id(), true);
    }

    private CompanyView view(UUID orgId, boolean withInvites) {
        Organization o = organizations.find(orgId).orElseThrow(CompanyNotFoundException::new);
        return new CompanyView(o, o.gstin() != null, organizations.members(orgId),
                withInvites ? invites.open(orgId, clock.instant()) : List.of());
    }

    static void requireBrand(Actor actor) {
        if (!actor.role().isBrand()) {
            throw new ActorNotAllowedException("Only brand accounts have a company");
        }
    }

    static void requireAdmin(Actor actor) {
        if (actor.role() != ActorRole.BRAND_ADMIN) {
            throw new ActorNotAllowedException("Only a company admin can do this");
        }
    }

    private record Valid(String legalName, BillingAddress billing, String pickupState) {
    }

    private static Valid validate(CompanyForm f) {
        if (f == null) {
            throw new InvalidRequestException("Company details are required");
        }
        String name = text(f.legalName(), 2, 200, "Legal name");
        BillingAddress b = f.billing();
        if (b == null) {
            throw new InvalidRequestException("Billing address is required");
        }
        String line2 = b.line2() == null || b.line2().isBlank() ? null : text(b.line2(), 1, 200, "Address line 2");
        String pin = b.pincode() == null ? "" : b.pincode().replaceAll("\\s", "");
        if (!pin.matches("[1-9]\\d{5}")) {
            throw new InvalidRequestException("PIN code must be 6 digits");
        }
        BillingAddress billing = new BillingAddress(text(b.line1(), 3, 200, "Address line 1"), line2,
                text(b.city(), 2, 100, "City"), state(b.state(), "Billing state"), pin);
        return new Valid(name, billing, state(f.pickupState(), "Pickup state"));
    }

    private static String text(String s, int min, int max, String field) {
        String t = s == null ? "" : s.trim();
        if (t.length() < min || t.length() > max) {
            throw new InvalidRequestException(field + " must be " + min + "-" + max + " characters");
        }
        return t;
    }

    private static String state(String raw, String field) {
        try {
            String code = Holiday.normaliseState(raw);
            if (code.isEmpty()) {
                throw new InvalidRequestException(field + " is required (a state code like MH or KA)");
            }
            return code;
        } catch (InvalidStateCodeException e) {
            throw new InvalidRequestException(field + ": " + e.getMessage());
        }
    }
}
