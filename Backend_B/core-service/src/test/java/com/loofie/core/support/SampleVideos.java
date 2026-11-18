package com.loofie.core.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Real video files for the F7 tests, made once per run with the same ffmpeg the app uses (D3: tested on real video,
 * not mocked). Plain colour frames at a low frame rate keep them a few hundred KB.
 */
public final class SampleVideos {

    private static Path dir;

    private SampleVideos() {
    }

    /** Portrait 1080x1920, 20 s, with sound: passes every check. */
    public static synchronized byte[] good() {
        return make("good.mp4", "color=c=0x3366aa:size=1080x1920:rate=5:duration=20", true, null);
    }

    /** Same, as MOV. */
    public static synchronized byte[] goodMov() {
        return make("good.mov", "color=c=0x33aa66:size=1080x1920:rate=5:duration=20", true, null);
    }

    public static synchronized byte[] landscape() {
        return make("landscape.mp4", "color=c=red:size=1920x1080:rate=5:duration=20", true, null);
    }

    /** Landscape pixels with a 90 degree display rotation: how phones store portrait video. Passes. */
    public static synchronized byte[] rotatedPortrait() {
        make("landscape.mp4", "color=c=red:size=1920x1080:rate=5:duration=20", true, null);
        return make("rotated.mp4", null, true, "landscape.mp4");
    }

    public static synchronized byte[] small720() {
        return make("small.mp4", "color=c=green:size=720x1280:rate=5:duration=20", true, null);
    }

    public static synchronized byte[] silent() {
        return make("silent.mp4", "color=c=gray:size=1080x1920:rate=5:duration=20", false, null);
    }

    /** 100 s: over the default 90 s. */
    public static synchronized byte[] tooLong() {
        return make("long.mp4", "color=c=blue:size=1080x1920:rate=5:duration=100", true, null);
    }

    /** 8 s: under the default 15 s. */
    public static synchronized byte[] tooShort() {
        return make("short.mp4", "color=c=yellow:size=1080x1920:rate=5:duration=8", true, null);
    }

    /** Not a video at all, but a playlist pointing at a local file (the classic FFmpeg file-read trick). */
    public static byte[] playlistTrick() {
        return ("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:10.0,\nfile:///etc/passwd\n#EXT-X-ENDLIST\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    public static String ffmpeg() {
        String env = System.getenv("LOOFIE_FFMPEG");
        return env == null || env.isBlank() ? "ffmpeg" : env;
    }

    public static String ffprobe() {
        String env = System.getenv("LOOFIE_FFPROBE");
        return env == null || env.isBlank() ? "ffprobe" : env;
    }

    /** Width, height and whether there is sound, read with ffprobe: "720x1280 audio". */
    public static String describe(byte[] video) {
        try {
            Path f = Files.createTempFile("probe", ".mp4");
            Files.write(f, video);
            Path out = Files.createTempFile("probe", ".txt");
            run(List.of(ffprobe(), "-v", "error", "-show_entries", "stream=codec_type,width,height", "-of", "csv=p=0",
                    f.toString()), out);
            String text = Files.readString(out);
            Files.deleteIfExists(f);
            Files.deleteIfExists(out);
            String size = text.lines().filter(l -> l.startsWith("video")).findFirst().map(l -> l.split(","))
                    .map(p -> p[1] + "x" + p[2]).orElse("none");
            return size + (text.contains("audio") ? " audio" : " silent");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * How much the brightness varies across the middle band of a frame (where the watermark is drawn). The samples are
     * one flat colour, so their band varies by almost nothing; drawn text makes it vary a lot.
     */
    public static int middleBandContrast(byte[] video) {
        try {
            Path f = Files.createTempFile("frame", ".mp4");
            Files.write(f, video);
            Path out = Files.createTempFile("frame", ".gray");
            run(List.of(ffmpeg(), "-v", "error", "-y", "-ss", "1", "-i", f.toString(), "-frames:v", "1",
                    "-vf", "crop=iw:ih/10:0:ih*9/20", "-f", "rawvideo", "-pix_fmt", "gray", out.toString()), null);
            byte[] px = Files.readAllBytes(out);
            Files.deleteIfExists(f);
            Files.deleteIfExists(out);
            int min = 255;
            int max = 0;
            for (byte b : px) {
                int v = b & 0xff;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            return max - min;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] make(String name, String lavfi, boolean audio, String rotateFrom) {
        try {
            if (dir == null) {
                dir = Files.createTempDirectory("loofie-samples");
            }
            Path out = dir.resolve(name);
            if (!Files.exists(out)) {
                List<String> cmd = new ArrayList<>(List.of(ffmpeg(), "-v", "error", "-y"));
                if (rotateFrom != null) {
                    cmd.addAll(List.of("-display_rotation", "90", "-i", dir.resolve(rotateFrom).toString(), "-c", "copy"));
                } else {
                    cmd.addAll(List.of("-f", "lavfi", "-i", lavfi));
                    if (audio) {
                        cmd.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440:duration=100", "-shortest",
                                "-c:a", "aac", "-b:a", "32k"));
                    }
                    cmd.addAll(List.of("-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p"));
                }
                cmd.add(out.toString());
                run(cmd, null);
            }
            return Files.readAllBytes(out);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot make sample video " + name + " (is ffmpeg installed?)", e);
        }
    }

    private static void run(List<String> cmd, Path stdout) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(stdout == null);
        if (stdout != null) {
            pb.redirectOutput(stdout.toFile());
        }
        Process p = pb.start();
        try {
            if (stdout == null) {
                p.getInputStream().readAllBytes();
            }
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
                throw new IOException("ffmpeg failed: " + cmd);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }
}
