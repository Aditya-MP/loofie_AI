package com.loofie.core.identity.domain;

/** Whether the person may log in at all. Separate from creator marketplace eligibility (core, round C). */
public enum AccountStatus { ACTIVE, SUSPENDED, DELETED }
