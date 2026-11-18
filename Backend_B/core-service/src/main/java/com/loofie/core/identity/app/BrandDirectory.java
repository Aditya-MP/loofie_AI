package com.loofie.core.identity.app;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.loofie.core.identity.infra.OrganizationRepository;
import com.loofie.core.job.domain.BrandProfiles;

/**
 * What other features may ask about a brand's company, without reading identity tables.
 * <ul>
 *   <li>{@link #pickupState}: the job engine copies it onto each new job (E2).</li>
 *   <li>{@link #readyForBriefs}: the company is set up. GSTIN is optional (changed 2026-11-18; was "GSTIN before the
 *       first brief", E3): an unregistered brand gets a B2C invoice with 18% GST and no input tax credit. F2 (briefs)
 *       and F4 (checkout) must call it when their APIs are built; there is no brief or order API yet.</li>
 * </ul>
 */
@Component
public class BrandDirectory implements BrandProfiles {

    private final OrganizationRepository organizations;

    public BrandDirectory(OrganizationRepository organizations) {
        this.organizations = organizations;
    }

    @Override
    public String pickupState(UUID brandOrgId) {
        return organizations.pickupState(brandOrgId);
    }

    /** The company exists. A GSTIN is not needed: without one, invoices are B2C. */
    public boolean readyForBriefs(UUID brandOrgId) {
        return organizations.exists(brandOrgId);
    }
}
