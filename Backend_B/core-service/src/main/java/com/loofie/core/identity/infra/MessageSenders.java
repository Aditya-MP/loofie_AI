package com.loofie.core.identity.infra;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.loofie.core.identity.domain.Channel;
import com.loofie.core.identity.domain.IdentityExceptions.DeliveryUnavailableException;
import com.loofie.core.identity.domain.MessageSender;

/** Chooses the sender from {@code loofie.identity.sender}: {@code none} (default) or {@code log}. */
@Configuration(proxyBeanMethods = false)
class MessageSenders {

    @Bean
    @ConditionalOnProperty(name = "loofie.identity.sender", havingValue = "log")
    LoggingMessageSender loggingMessageSender() {
        return new LoggingMessageSender();
    }

    /** No provider configured, so nothing pretends to send: the caller gets a 503 with a clear reason. */
    @Bean
    @ConditionalOnProperty(name = "loofie.identity.sender", havingValue = "none", matchIfMissing = true)
    MessageSender closedMessageSender() {
        return message -> {
            throw new DeliveryUnavailableException((message.channel() == Channel.PHONE ? "SMS" : "Email")
                    + " sending is not set up yet. Try again later.");
        };
    }
}
