package com.loofie.core.identity.domain;

/**
 * FULL: logged in. MFA_PENDING: staff passed email OTP, still needs the authenticator code. MFA_ENROL: staff passed
 * email OTP and must set up the authenticator app first. Only FULL sessions act on anything.
 */
public enum SessionKind { FULL, MFA_PENDING, MFA_ENROL }
