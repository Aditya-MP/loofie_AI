package com.loofie.core.storage;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4, query-string ("presigned URL") form, for S3 and S3-compatible stores (Cloudflare R2, MinIO).
 * No SDK: the signing is ~60 lines and checked against AWS's published example in {@code SigV4Test}.
 *
 * <p>Signed headers are {@code host} plus, for uploads, {@code content-type}: the browser must then send exactly the
 * type we allowed, or the store refuses the upload.
 */
final class SigV4 {

    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private SigV4() {
    }

    /**
     * @param url         the object URL without a query (path-style or virtual-host style)
     * @param contentType sign this content type too (uploads), or null
     */
    static URI presign(String method, URI url, String region, String accessKey, String secretKey, Instant now,
                       Duration expires, String contentType) {
        return presign(method, url, region, accessKey, secretKey, now, expires, contentType, null);
    }

    /**
     * @param contentLength sign this exact body size too (uploads), or null. The store then refuses a body of any other
     *                      size: a signed URL alone does not limit size (S3 accepts up to 5 GB in one PUT).
     */
    static URI presign(String method, URI url, String region, String accessKey, String secretKey, Instant now,
                       Duration expires, String contentType, Long contentLength) {
        String amzDate = AMZ_DATE.format(now);
        String date = DATE.format(now);
        String scope = date + "/" + region + "/s3/aws4_request";
        String host = url.getPort() == -1 ? url.getHost() : url.getHost() + ":" + url.getPort();
        // Canonical headers are sorted by name: content-length, content-type, host.
        String signedHeaders = (contentLength == null ? "" : "content-length;")
                + (contentType == null ? "" : "content-type;") + "host";

        Map<String, String> query = new TreeMap<>();
        query.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        query.put("X-Amz-Credential", accessKey + "/" + scope);
        query.put("X-Amz-Date", amzDate);
        query.put("X-Amz-Expires", String.valueOf(expires.toSeconds()));
        query.put("X-Amz-SignedHeaders", signedHeaders);
        String canonicalQuery = query.entrySet().stream()
                .map(e -> encode(e.getKey(), true) + "=" + encode(e.getValue(), true))
                .collect(Collectors.joining("&"));

        String canonicalHeaders = (contentLength == null ? "" : "content-length:" + contentLength + "\n")
                + (contentType == null ? "" : "content-type:" + contentType.trim() + "\n")
                + "host:" + host + "\n";
        String canonicalRequest = method + "\n" + encode(url.getRawPath().isEmpty() ? "/" : decodedPath(url), false)
                + "\n" + canonicalQuery + "\n" + canonicalHeaders + "\n" + signedHeaders + "\nUNSIGNED-PAYLOAD";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonicalRequest));

        byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, "s3");
        key = hmac(key, "aws4_request");
        String signature = hex(hmac(key, stringToSign));
        return URI.create(url.getScheme() + "://" + host + encode(decodedPath(url), false) + "?" + canonicalQuery
                + "&X-Amz-Signature=" + signature);
    }

    private static String decodedPath(URI url) {
        return url.getPath();
    }

    /** RFC 3986 encoding as AWS wants it: unreserved characters stay, everything else %XX; '/' kept in paths. */
    static String encode(String s, boolean encodeSlash) {
        StringBuilder out = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_'
                    || c == '.' || c == '~' || (c == '/' && !encodeSlash)) {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return out.toString();
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }
}
