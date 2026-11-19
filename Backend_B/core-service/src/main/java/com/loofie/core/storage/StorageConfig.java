package com.loofie.core.storage;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Bean
    ObjectStore objectStore(StorageProperties props, Clock clock) {
        return switch (props.mode()) {
            case "s3" -> new S3ObjectStore(props, clock);
            case "local" -> {
                log.warn("LOCAL FILE STORAGE ACTIVE: uploads are kept on this machine. Development only.");
                yield new LocalObjectStore(props, clock);
            }
            default -> {
                log.warn("loofie.storage.mode=none: uploads answer 503 until a store is configured (LOOFIE_STORAGE_*)");
                yield new NoObjectStore();
            }
        };
    }

    /** No store configured: every use answers "unavailable" (503), the rest of the app works. */
    static final class NoObjectStore implements ObjectStore {

        private static final String MESSAGE = "File uploads are not available yet. Try again later.";

        @Override
        public boolean ready() {
            return false;
        }

        @Override
        public Ticket presignPut(String key, String contentType, long sizeBytes, Duration ttl) {
            throw new StoreUnavailableException(MESSAGE, null);
        }

        @Override
        public URI presignGet(String key, Duration ttl) {
            throw new StoreUnavailableException(MESSAGE, null);
        }

        @Override
        public Optional<ObjectInfo> stat(String key) {
            throw new StoreUnavailableException(MESSAGE, null);
        }

        @Override
        public void delete(String key) {
            throw new StoreUnavailableException(MESSAGE, null);
        }

        @Override
        public void download(String key, java.nio.file.Path target) {
            throw new StoreUnavailableException(MESSAGE, null);
        }

        @Override
        public void upload(String key, java.nio.file.Path source, String contentType) {
            throw new StoreUnavailableException(MESSAGE, null);
        }
    }
}
