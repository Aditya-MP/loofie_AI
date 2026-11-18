package com.loofie.core.media;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FFmpeg for F7 (decision D2: inside core-service, as a queued background worker).
 *
 * @param ffmpeg            the ffmpeg binary (a name on PATH or a full path)
 * @param ffprobe           the ffprobe binary
 * @param fontFile          TrueType font for the preview watermark; empty = the usual system font of this OS
 * @param threads           encoder threads per video (a 60 s 1080x1920 video takes about 17 s on 2)
 * @param probeTimeout      ffprobe reads headers only; a file that takes longer is refused
 * @param transcodeTimeout  one preview; longer counts as a processing failure and is retried
 * @param workerEnabled     false = nothing is processed here (videos wait in the queue)
 * @param pollInterval      how often the worker looks for videos
 * @param maxConcurrent     videos processed at once on this instance (CPU heavy: 1-2)
 * @param lease             a claimed video is retried by another worker after this (crash recovery)
 */
@ConfigurationProperties("loofie.media")
public record MediaProperties(String ffmpeg, String ffprobe, String fontFile, Integer threads, Duration probeTimeout,
                              Duration transcodeTimeout, Boolean workerEnabled, Duration pollInterval,
                              Integer maxConcurrent, Duration lease) {

    public MediaProperties {
        ffmpeg = blank(ffmpeg) ? "ffmpeg" : ffmpeg.trim();
        ffprobe = blank(ffprobe) ? "ffprobe" : ffprobe.trim();
        fontFile = blank(fontFile) ? defaultFont() : fontFile.trim();
        threads = threads == null ? 2 : threads;
        probeTimeout = probeTimeout == null ? Duration.ofSeconds(30) : probeTimeout;
        transcodeTimeout = transcodeTimeout == null ? Duration.ofMinutes(10) : transcodeTimeout;
        workerEnabled = workerEnabled == null || workerEnabled;
        pollInterval = pollInterval == null ? Duration.ofSeconds(3) : pollInterval;
        maxConcurrent = maxConcurrent == null ? 1 : maxConcurrent;
        lease = lease == null ? Duration.ofMinutes(20) : lease;
        if (threads < 1 || threads > 16 || maxConcurrent < 1 || maxConcurrent > 8) {
            throw new IllegalStateException("loofie.media: threads 1-16, max-concurrent 1-8");
        }
        if (fontFile.contains("'")) {
            throw new IllegalStateException("loofie.media.font-file must not contain a quote");
        }
        if (lease.compareTo(transcodeTimeout.plusMinutes(5)) < 0) {
            throw new IllegalStateException("loofie.media.lease must be at least transcode-timeout + 5 minutes");
        }
    }

    private static String defaultFont() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "C:/Windows/Fonts/arial.ttf"
                : "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
