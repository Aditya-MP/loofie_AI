package com.loofie.core.creator.domain;

public final class CreatorExceptions {

    private CreatorExceptions() {
    }

    /** No such creator, or not one this caller may see: 404. */
    public static final class CreatorNotFoundException extends RuntimeException {
        public CreatorNotFoundException(String message) {
            super(message);
        }
    }

    /** No such evidence for this creator: 404. */
    public static final class EvidenceNotFoundException extends RuntimeException {
        public EvidenceNotFoundException(String message) {
            super(message);
        }
    }
}
