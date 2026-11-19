package com.loofie.core.security;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Production runs behind a load balancer, so the client IP (per-IP login-code limits, consent and session records)
 * comes from {@code X-Forwarded-For}, read by Tomcat only from proxies in
 * {@code server.tomcat.remoteip.internal-proxies}. Spring Boot's default list is the private ranges, which covers a
 * load balancer inside a VPC; the hosting owner may narrow it to the VPC range
 * ({@code SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES}). An empty value would trust every sender, so anyone could forge
 * their IP and get round the per-IP limits: the app refuses to start with it.
 */
@Component
@Profile("prod")
class ProxyTrustGuard {

    ProxyTrustGuard(Environment env) {
        check(env.getProperty("server.forward-headers-strategy"),
                env.getProperty("server.tomcat.remoteip.internal-proxies"));
    }

    static void check(String strategy, String internalProxies) {
        if (!"native".equalsIgnoreCase(strategy == null ? "" : strategy.trim())) {
            throw new IllegalStateException("server.forward-headers-strategy must be 'native' in production "
                    + "(behind the load balancer every request would otherwise share one IP)");
        }
        if (internalProxies != null && internalProxies.isBlank()) {
            throw new IllegalStateException("server.tomcat.remoteip.internal-proxies is empty: that trusts every "
                    + "sender's X-Forwarded-For. Leave it unset (private ranges) or set the VPC range");
        }
    }
}
