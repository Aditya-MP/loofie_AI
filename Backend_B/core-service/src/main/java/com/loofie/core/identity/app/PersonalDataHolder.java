package com.loofie.core.identity.app;

import java.util.UUID;

/**
 * A feature that keeps personal data about a user outside identity (round C: the creator passport, uploaded files).
 * When an account is deleted, identity calls every holder inside the deletion transaction, so personal data goes in
 * the same commit as the account; anything outside the database (storage, Backend A) is queued, never skipped.
 */
public interface PersonalDataHolder {

    /** Removes or anonymises this user's personal data. Runs inside the deletion transaction. */
    void erase(UUID userId);
}
