package com.loofie.core.identity.domain;

/** Where an OTP goes. Creators log in by phone, brands and staff by email. */
public enum Channel {
    PHONE, EMAIL;

    /** The account type a new signup on this channel gets. */
    public AccountType signupType() {
        return this == PHONE ? AccountType.CREATOR : AccountType.BRAND;
    }

    /** The channel a person of this type logs in with. */
    public static Channel loginChannel(AccountType type) {
        return type == AccountType.CREATOR ? PHONE : EMAIL;
    }
}
