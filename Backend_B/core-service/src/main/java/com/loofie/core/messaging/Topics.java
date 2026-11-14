package com.loofie.core.messaging;

/** Kafka topic names. Keep in sync with scripts/local/kafka.sh and the architecture doc. */
public final class Topics {

    public static final String CORE_ORDER = "core.order.v1";
    public static final String CORE_JOB = "core.job.v1";
    public static final String CORE_CREATOR = "core.creator.v1";
    public static final String CORE_EVENTS = "core.events.v1";

    private Topics() {
    }
}
