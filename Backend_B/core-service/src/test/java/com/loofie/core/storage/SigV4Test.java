package com.loofie.core.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/** The signer reproduces AWS's own published presigned-URL example, byte for byte. */
class SigV4Test {

    /**
     * AWS S3 docs, "Authenticating Requests: Using Query Parameters (AWS Signature Version 4)", example:
     * GET examplebucket/test.txt, 2013-05-24, us-east-1, 86400 s, the documentation's example keys.
     */
    @Test
    void matchesTheAwsDocumentationExample() {
        URI signed = SigV4.presign("GET", URI.create("https://examplebucket.s3.amazonaws.com/test.txt"), "us-east-1",
                "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                Instant.parse("2013-05-24T00:00:00Z"), Duration.ofSeconds(86400), null);

        assertThat(signed.toString()).isEqualTo("https://examplebucket.s3.amazonaws.com/test.txt"
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request"
                + "&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host"
                + "&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404");
    }

    @Test
    void uploadsSignTheContentTypeAndKeepSlashesInTheKey() {
        URI signed = SigV4.presign("PUT", URI.create("https://acc.r2.cloudflarestorage.com/loofie/creator-evidence/a/b.jpg"),
                "auto", "AK", "SK", Instant.parse("2026-11-18T00:00:00Z"), Duration.ofMinutes(5), "image/jpeg");
        assertThat(signed.getRawPath()).isEqualTo("/loofie/creator-evidence/a/b.jpg");
        assertThat(signed.getRawQuery()).contains("X-Amz-SignedHeaders=content-type%3Bhost", "X-Amz-Expires=300");
    }

    @Test
    void encodesLikeAws() {
        assertThat(SigV4.encode("a b+c/d~e", true)).isEqualTo("a%20b%2Bc%2Fd~e");
        assertThat(SigV4.encode("/x/y z", false)).isEqualTo("/x/y%20z");
    }
}
