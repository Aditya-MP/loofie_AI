# Production image for core-service (asks 3, 7, 8 in the open-asks list).
#
# Build: the jar first (CI runs the tests), then the image:
#   ./mvnw -B package && docker build -t core-service .
#
# Contents:
# - Java 25 runtime (Temurin, Ubuntu base).
# - ffmpeg + ffprobe for F7 draft checks and previews. The distribution's build includes libx264 and is therefore GPL:
#   it runs on our servers only and is never handed to users. The lawyer confirms that position (see the open asks).
# - DejaVu Sans, the watermark font MediaProperties expects on Linux (/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf).
# - coreutils' /usr/bin/nice, so transcoding runs at low priority beside the API (MediaTools).
FROM eclipse-temurin:25-jre-noble

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/* \
    && ffmpeg -hide_banner -version | head -n 1 \
    && test -r /usr/share/fonts/truetype/dejavu/DejaVuSans.ttf \
    && useradd --system --uid 10001 --home-dir /app --shell /usr/sbin/nologin core

WORKDIR /app
COPY --chown=core:core target/core-service-*.jar /app/core-service.jar
USER core

# - MaxRAMPercentage: the heap follows the container's memory limit.
# - DNS cache 30 s: after a database failover the database's DNS name points to the new primary; the JVM must look
#   it up again instead of holding the old address (the default when a security manager is absent is 30 s, set
#   here explicitly so a platform default cannot change it).
# - ExitOnOutOfMemoryError: a broken instance stops and is replaced instead of answering slowly.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Dsun.net.inetaddr.ttl=30 -Dsun.net.inetaddr.negative.ttl=5" \
    SPRING_PROFILES_ACTIVE=prod \
    TMPDIR=/tmp

# 8081: the API, behind the load balancer. 8091: health probes and metrics, internal only.
EXPOSE 8081 8091

# The platform sends SIGTERM on deploy; Spring finishes in-flight requests (server.shutdown: graceful, 30 s phase).
# Set the platform's stop timeout above that (ECS stopTimeout about 60 s) and the load balancer's deregistration
# delay to about 30 s.
ENTRYPOINT ["java", "-jar", "/app/core-service.jar"]
