package com.loofie.core.draft.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The technical checks a video must pass before the brand sees it (F7 D4). Each failure says exactly what is wrong
 * and how to fix it, in words a creator understands.
 */
public final class VideoChecks {

    /** What the file is (display size, after the phone's rotation). */
    public record Video(boolean readable, boolean hasVideo, int width, int height, double seconds, boolean hasAudio) {

        public static Video unreadable() {
            return new Video(false, false, 0, 0, 0, false);
        }
    }

    public record Failure(String code, String message) {
    }

    public static final int MIN_WIDTH = 1080;
    public static final int MIN_HEIGHT = 1920;
    /** 9:16 = 0.5625. Phones and editors round a few pixels, so a small tolerance. */
    static final double RATIO = 9.0 / 16.0;
    static final double RATIO_TOLERANCE = 0.02;
    /** Containers round durations; half a second is never the difference between two takes. */
    static final double SECONDS_TOLERANCE = 0.5;

    private VideoChecks() {
    }

    public static List<Failure> check(Video v, Brief brief) {
        List<Failure> out = new ArrayList<>();
        if (!v.readable()) {
            out.add(new Failure("UNREADABLE", "This file could not be read as a video. Export it again as MP4 (H.264) "
                    + "or MOV from your phone or editor and upload it."));
            return out;
        }
        if (!v.hasVideo() || v.width() <= 0 || v.height() <= 0) {
            out.add(new Failure("NO_VIDEO", "The file has no picture. Upload the video itself, not only its sound."));
            return out;
        }
        double ratio = (double) v.width() / v.height();
        if (Math.abs(ratio - RATIO) > RATIO * RATIO_TOLERANCE) {
            String shape = v.width() > v.height() ? "landscape" : v.width() == v.height() ? "square" : "not 9:16";
            out.add(new Failure("NOT_PORTRAIT", "The video is " + v.width() + "x" + v.height() + " (" + shape
                    + "). It must be upright 9:16, like a Reel: record in portrait or export at 1080x1920."));
        }
        if (v.width() < MIN_WIDTH || v.height() < MIN_HEIGHT) {
            out.add(new Failure("TOO_SMALL", "The video is " + v.width() + "x" + v.height() + ". It must be at least "
                    + MIN_WIDTH + "x" + MIN_HEIGHT + " (Full HD). Export at 1080p or higher."));
        }
        if (v.seconds() + SECONDS_TOLERANCE < brief.minSeconds()) {
            out.add(new Failure("TOO_SHORT", "The video is " + seconds(v.seconds()) + " long. This brief needs at least "
                    + brief.minSeconds() + " s."));
        } else if (v.seconds() - SECONDS_TOLERANCE > brief.maxSeconds()) {
            out.add(new Failure("TOO_LONG", "The video is " + seconds(v.seconds()) + " long. This brief allows at most "
                    + brief.maxSeconds() + " s."));
        }
        if (!v.hasAudio()) {
            out.add(new Failure("NO_AUDIO", "The video has no sound track. Export it with audio (voice or music)."));
        }
        return out;
    }

    private static String seconds(double s) {
        return String.format(Locale.ROOT, "%.1f s", s);
    }
}
