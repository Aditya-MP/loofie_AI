package com.loofie.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.loofie.core.support.TestSupportConfig;

/**
 * The production proxy setting (ask 5), through real Tomcat (MockMvc skips the RemoteIpValve): the per-IP login-code
 * limit keys on the client the load balancer saw, and a client cannot forge its IP by sending its own
 * X-Forwarded-For entries in front of the ones the proxies add.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.forward-headers-strategy=native")
@ActiveProfiles({"local", "test"})
@Import(TestSupportConfig.class)
class ForwardedClientIpTest {

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void clientIpComesFromTheTrustedProxyHeader() throws Exception {
        String client = "203.0.113." + ThreadLocalRandom.current().nextInt(1, 255);
        long before = counted("otp-ip:" + client);
        assertThat(sendCode(client)).isEqualTo(202);
        assertThat(counted("otp-ip:" + client) - before).as("the limit keys on the real client").isEqualTo(1);
    }

    @Test
    void aForgedLeadingEntryIsIgnored() throws Exception {
        String real = "198.51.100." + ThreadLocalRandom.current().nextInt(1, 255);
        String forged = "192.0.2." + ThreadLocalRandom.current().nextInt(1, 255);
        long realBefore = counted("otp-ip:" + real);
        long forgedBefore = counted("otp-ip:" + forged);
        // The client sent "X-Forwarded-For: forged"; the load balancer appended the address it saw.
        assertThat(sendCode(forged + ", " + real)).isEqualTo(202);
        assertThat(counted("otp-ip:" + real) - realBefore).isEqualTo(1);
        assertThat(counted("otp-ip:" + forged) - forgedBefore).as("the forged entry is not trusted").isZero();
    }

    @Test
    void productionGuardRefusesTrustAllAndMissingStrategy() {
        ProxyTrustGuard.check("native", null);
        ProxyTrustGuard.check("NATIVE", "10\\.0\\.\\d{1,3}\\.\\d{1,3}");
        assertThatThrownBy(() -> ProxyTrustGuard.check("native", " ")).hasMessageContaining("trusts every");
        assertThatThrownBy(() -> ProxyTrustGuard.check(null, null)).hasMessageContaining("must be 'native'");
        assertThatThrownBy(() -> ProxyTrustGuard.check("framework", null)).hasMessageContaining("must be 'native'");
    }

    private int sendCode(String forwardedFor) throws Exception {
        String phone = "+9197" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/core/auth/otp/send"))
                .header("Content-Type", "application/json").header("X-Forwarded-For", forwardedFor)
                .POST(HttpRequest.BodyPublishers.ofString("{\"channel\":\"PHONE\",\"destination\":\"" + phone + "\"}"))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private long counted(String key) {
        return jdbc.sql("SELECT coalesce(sum(count), 0) FROM rate_counters WHERE key = :k").param("k", key)
                .query(Long.class).single();
    }
}
