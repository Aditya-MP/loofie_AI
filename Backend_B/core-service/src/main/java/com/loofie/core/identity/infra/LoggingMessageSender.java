package com.loofie.core.identity.infra;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.loofie.core.identity.domain.Contacts;
import com.loofie.core.identity.domain.MessageSender;

/**
 * LOCAL AND TESTS ONLY ({@code loofie.identity.sender=log}): writes the message, code included, to the log instead of
 * sending it, and keeps the last few hundred so tests can read the code a person would have received.
 */
public class LoggingMessageSender implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingMessageSender.class);
    private static final int KEEP = 500;
    private final Deque<Message> sent = new ArrayDeque<>();
    private volatile boolean failing;

    LoggingMessageSender() {
        log.warn("FAKE SENDER ACTIVE: SMS and email are logged, not sent. Never use loofie.identity.sender=log in "
                + "production.");
    }

    @Override
    public void send(Message message) {
        if (failing) {
            throw new com.loofie.core.identity.domain.IdentityExceptions.DeliveryUnavailableException(
                    "The message could not be sent. Try again in a minute.");
        }
        log.info("[fake {}] to {} ({}): {}", message.channel(), Contacts.mask(message.to()), message.kind(),
                message.text());
        synchronized (sent) {
            sent.addFirst(message);
            if (sent.size() > KEEP) {
                sent.removeLast();
            }
        }
    }

    /** The newest message sent to this phone / email. */
    public Optional<Message> last(String to) {
        synchronized (sent) {
            return sent.stream().filter(m -> m.to().equals(to)).findFirst();
        }
    }

    /** Tests: make every send fail, as a provider outage would. */
    public void failing(boolean on) {
        this.failing = on;
    }
}
