package com.loofie.core.identity.domain;

/** LOGIN covers signup too. CHANGE_OLD / CHANGE_NEW prove both sides of a phone or email change. */
public enum OtpPurpose { LOGIN, CHANGE_OLD, CHANGE_NEW, DELETE }
