package com.loofie.core.messaging;

import java.time.Duration;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Consumer error policy from the architecture doc: blocking retries with exponential backoff,
 * 5 attempts in total, then the record goes to {@code <topic>.DLT} on the same partition.
 * A message that can never succeed (bad envelope) skips the retries.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
class KafkaConfig {

    static final String DLT_SUFFIX = ".DLT";

    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template,
                                         @Value("${loofie.kafka.retry.initial-interval:1s}") Duration initial,
                                         @Value("${loofie.kafka.retry.multiplier:2.0}") double multiplier,
                                         @Value("${loofie.kafka.retry.max-attempts:5}") int maxAttempts) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + DLT_SUFFIX, record.partition()));

        ExponentialBackOff backOff = new ExponentialBackOff(initial.toMillis(), multiplier);
        backOff.setMaxAttempts(maxAttempts - 1); // retries after the first delivery

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }
}
