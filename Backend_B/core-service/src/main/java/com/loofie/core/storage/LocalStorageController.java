package com.loofie.core.storage;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plays the storage service on a developer machine ({@code loofie.storage.mode=local} only, which production refuses):
 * accepts a PUT and serves a GET on URLs issued by {@link LocalObjectStore}, checking signature, type and expiry.
 */
@RestController
@ConditionalOnProperty(name = "loofie.storage.mode", havingValue = "local")
class LocalStorageController {

    private final ObjectStore store;

    LocalStorageController(ObjectStore store) {
        this.store = store;
    }

    @PutMapping(LocalObjectStore.PATH + "**")
    ResponseEntity<Void> put(HttpServletRequest request, @RequestParam("m") String method,
                             @RequestParam("ct") String contentType, @RequestParam("exp") long exp,
                             @RequestParam("sig") String sig,
                             @RequestHeader(value = "Content-Type", required = false) String sentType)
            throws java.io.IOException {
        LocalObjectStore local = (LocalObjectStore) store;
        String key = key(request);
        if (!"PUT".equals(method) || !local.valid("PUT", key, contentType, exp, sig)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        // S3 refuses a type that was not signed. Here the media type must match; parameters (";charset=...", which
        // test clients add) are ignored. Browsers send a File's type exactly, which S3 needs.
        if (sentType == null || !MediaType.parseMediaType(sentType).equalsTypeAndSubtype(MediaType.parseMediaType(
                contentType))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        // Streamed to disk (an F7 video is up to 500 MB); the size cap stands in for the bucket's own limit.
        if (!local.write(key, contentType, request.getInputStream(), LocalObjectStore.MAX_BYTES)) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping(LocalObjectStore.PATH + "**")
    ResponseEntity<byte[]> get(HttpServletRequest request, @RequestParam("m") String method,
                               @RequestParam(value = "ct", defaultValue = "") String contentType,
                               @RequestParam("exp") long exp, @RequestParam("sig") String sig) {
        LocalObjectStore local = (LocalObjectStore) store;
        String key = key(request);
        if (!"GET".equals(method) || !local.valid("GET", key, contentType, exp, sig)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return local.stat(key).map(info -> ResponseEntity.ok().contentType(MediaType.parseMediaType(
                        info.contentType().isBlank() ? "application/octet-stream" : info.contentType()))
                        .header("Content-Disposition", "inline").body(local.read(key)))
                .orElse(ResponseEntity.notFound().build());
    }

    private static String key(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getRequestURI().indexOf(LocalObjectStore.PATH)
                + LocalObjectStore.PATH.length());
    }
}
