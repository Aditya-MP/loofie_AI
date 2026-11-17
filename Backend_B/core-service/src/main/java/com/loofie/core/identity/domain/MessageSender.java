package com.loofie.core.identity.domain;

/**
 * Sends one SMS or email. Implementations: a logging fake (local, tests) and a closed one that refuses (the default
 * until a provider is chosen: MSG91 for SMS once DLT is registered, Postmark or SES for email).
 */
public interface MessageSender {

    /** @param kind what this is (OTP, STAFF_INVITE, ACCOUNT_SUSPENDED...), for logs and templates */
    record Message(Channel channel, String to, String kind, String subject, String text) {
    }

    /** @throws IdentityExceptions.DeliveryUnavailableException when it could not be sent */
    void send(Message message);
}
