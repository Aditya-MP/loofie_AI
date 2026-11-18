package com.loofie.core.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs ffprobe and ffmpeg on local files (F7). Hardening, because the input is an uploaded file:
 * <ul>
 *   <li>fixed argument lists, no shell; nothing a user typed reaches a command line (the watermark is built from
 *       the job id's hex digits only);</li>
 *   <li>the MP4/MOV reader is forced ({@code -f mov}): a file pretending to be a playlist or another format is not
 *       interpreted as one, and only local files may be opened ({@code -protocol_whitelist file});</li>
 *   <li>a time limit on every run (killed after it), an output size cap, no stdin.</li>
 * </ul>
 */
@Component
@EnableConfigurationProperties(MediaProperties.class)
public class MediaTools {

    /** What the uploaded file is, from its headers. Display width / height already account for rotation. */
    public record MediaInfo(String container, boolean hasVideo, int width, int height, int rotation, double seconds,
                            boolean hasAudio, String videoCodec) {
    }

    /** ffprobe / ffmpeg ran and refused the file: unreadable, or not a video we can work with. */
    public static final class MediaFileException extends Exception {
        public MediaFileException(String message) {
            super(message);
        }
    }

    /** The tools could not run (missing binary, I/O, time limit): retried later, never the creator's fault. */
    public static final class MediaUnavailableException extends Exception {
        private final boolean timedOut;

        public MediaUnavailableException(String message, Throwable cause) {
            this(message, cause, false);
        }

        MediaUnavailableException(String message, Throwable cause, boolean timedOut) {
            super(message, cause);
            this.timedOut = timedOut;
        }

        /** The tool ran but hit its time limit (possibly the file's fault after repeated tries). */
        public boolean timedOut() {
            return timedOut;
        }
    }

    private static final Pattern HEX = Pattern.compile("^[0-9a-f]{1,32}$");
    /** The preview never grows beyond this, whatever the input does. */
    private static final String MAX_PREVIEW_BYTES = String.valueOf(200L * 1024 * 1024);

    private final MediaProperties props;
    private final JsonMapper json;

    public MediaTools(MediaProperties props, JsonMapper json) {
        this.props = props;
        this.json = json;
    }

    /** Whether ffmpeg, ffprobe and the watermark font are usable here (checked by the worker at start). */
    public String problem() {
        if (!Files.isReadable(Path.of(props.fontFile()))) {
            return "watermark font not found: " + props.fontFile() + " (set loofie.media.font-file)";
        }
        for (String bin : List.of(props.ffmpeg(), props.ffprobe())) {
            try {
                run(List.of(bin, "-version"), Duration.ofSeconds(10), null);
            } catch (MediaFileException | MediaUnavailableException e) {
                return bin + " cannot run: " + e.getMessage() + " (install FFmpeg or set loofie.media.ffmpeg / ffprobe)";
            }
        }
        return null;
    }

    public MediaInfo probe(Path file) throws MediaFileException, MediaUnavailableException {
        Path out = tempFile("probe", ".json");
        try {
            run(List.of(props.ffprobe(), "-v", "error", "-protocol_whitelist", "file", "-f", "mov",
                    "-print_format", "json", "-show_format", "-show_streams", file.toAbsolutePath().toString()),
                    props.probeTimeout(), out);
            JsonNode root = json.readTree(Files.readAllBytes(out));
            return info(root);
        } catch (IOException e) {
            throw new MediaUnavailableException("Cannot read ffprobe output: " + e, e);
        } catch (RuntimeException e) {
            throw new MediaFileException("The file's headers could not be read");
        } finally {
            deleteQuietly(out);
        }
    }

    /**
     * The brand's preview: 720 px wide, H.264 + AAC, watermarked "LOOFIE PREVIEW" with the job reference, streamable
     * ({@code +faststart}), at most {@code maxSeconds} long.
     *
     * @param jobRef hex digits only (the start of the job id): it is drawn on every frame, so a leaked preview is traceable
     */
    public void preview(Path in, Path out, String jobRef, int maxSeconds)
            throws MediaFileException, MediaUnavailableException {
        if (!HEX.matcher(jobRef).matches()) {
            throw new IllegalArgumentException("jobRef must be hex digits");
        }
        String font = props.fontFile().replace("\\", "/").replace(":", "\\:");
        String filter = "scale=720:-2,"
                + "drawtext=fontfile='" + font + "':text='LOOFIE PREVIEW':fontsize=72:fontcolor=white@0.35"
                + ":x=(w-text_w)/2:y=(h-text_h)/2,"
                + "drawtext=fontfile='" + font + "':text='LOOFIE PREVIEW':fontsize=40:fontcolor=white@0.30"
                + ":x=(w-text_w)/2:y=h/6,"
                + "drawtext=fontfile='" + font + "':text='job " + jobRef + "':fontsize=28:fontcolor=white@0.5"
                + ":x=(w-text_w)/2:y=h-80";
        // Threads capped on the decoder and filters too, not only the encoder (uncapped, the decoder takes a thread
        // per core): measured about 2.7 cores per video with these settings.
        String threads = String.valueOf(props.threads());
        List<String> cmd = List.of(props.ffmpeg(), "-nostdin", "-v", "error", "-y",
                "-threads", threads, "-filter_threads", "1",
                "-protocol_whitelist", "file", "-f", "mov", "-i", in.toAbsolutePath().toString(),
                "-map", "0:v:0", "-map", "0:a:0?", "-vf", filter,
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "28", "-maxrate", "2M", "-bufsize", "4M",
                "-threads", String.valueOf(props.threads()),
                "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart",
                "-t", String.valueOf(maxSeconds), "-fs", MAX_PREVIEW_BYTES,
                "-f", "mp4", out.toAbsolutePath().toString());
        run(lowPriority(cmd), props.transcodeTimeout(), null);
        try {
            if (!Files.exists(out) || Files.size(out) == 0) {
                throw new MediaFileException("ffmpeg produced no preview");
            }
        } catch (IOException e) {
            throw new MediaUnavailableException("Cannot read the preview: " + e, e);
        }
    }

    /**
     * Transcoding is background work: on Linux (production) it runs under {@code nice -n 15}, so API requests on the
     * same instance always get the CPU first. Windows (development) has no equivalent on a command line.
     */
    private static List<String> lowPriority(List<String> cmd) {
        if (System.getProperty("os.name", "").toLowerCase().contains("win") || !Files.isExecutable(NICE)) {
            return cmd;
        }
        List<String> out = new ArrayList<>(List.of(NICE.toString(), "-n", "15"));
        out.addAll(cmd);
        return out;
    }

    private static final Path NICE = Path.of("/usr/bin/nice");

    MediaInfo info(JsonNode root) {
        JsonNode format = root.path("format");
        String container = format.path("format_name").asString("");
        double seconds = parseDouble(format.path("duration").asString(""));
        boolean hasVideo = false;
        boolean hasAudio = false;
        int width = 0;
        int height = 0;
        int rotation = 0;
        String codec = null;
        for (JsonNode s : root.path("streams")) {
            String type = s.path("codec_type").asString("");
            if (type.equals("video") && !hasVideo && s.path("disposition").path("attached_pic").asInt(0) == 0) {
                hasVideo = true;
                width = s.path("width").asInt(0);
                height = s.path("height").asInt(0);
                codec = s.path("codec_name").asString(null);
                rotation = rotation(s);
                if (seconds <= 0) {
                    seconds = parseDouble(s.path("duration").asString(""));
                }
            } else if (type.equals("audio")) {
                hasAudio = true;
            }
        }
        boolean sideways = Math.abs(rotation) % 180 == 90;
        return new MediaInfo(container, hasVideo, sideways ? height : width, sideways ? width : height, rotation,
                seconds, hasAudio, codec);
    }

    /** Phones store portrait video as landscape pixels plus a rotation; what the viewer sees is what counts. */
    private static int rotation(JsonNode stream) {
        for (JsonNode side : stream.path("side_data_list")) {
            if (side.has("rotation")) {
                return side.path("rotation").asInt(0);
            }
        }
        String tag = stream.path("tags").path("rotate").asString("");
        return tag.isBlank() ? 0 : (int) parseDouble(tag);
    }

    private static double parseDouble(String s) {
        try {
            return s == null || s.isBlank() ? 0 : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Runs one command; stdout to {@code stdout} (or discarded), stderr kept for the error message. */
    private void run(List<String> cmd, Duration timeout, Path stdout) throws MediaFileException, MediaUnavailableException {
        Path err = tempFile("ffmpeg", ".err");
        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(cmd))
                    .redirectInput(ProcessBuilder.Redirect.from(nullFile()))
                    .redirectError(err.toFile());
            pb.redirectOutput(stdout == null ? ProcessBuilder.Redirect.DISCARD : ProcessBuilder.Redirect.to(stdout.toFile()));
            p = pb.start();
        } catch (IOException e) {
            deleteQuietly(err);
            throw new MediaUnavailableException(e.getMessage(), e);
        }
        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
                throw new MediaUnavailableException("timed out after " + timeout.toSeconds() + " s", null, true);
            }
            if (p.exitValue() != 0) {
                throw new MediaFileException(lastLine(err));
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new MediaUnavailableException("interrupted", e);
        } finally {
            deleteQuietly(err);
        }
    }

    private static java.io.File nullFile() {
        return new java.io.File(System.getProperty("os.name", "").toLowerCase().contains("win") ? "NUL" : "/dev/null");
    }

    private static String lastLine(Path err) {
        try {
            List<String> lines = Files.readAllLines(err, StandardCharsets.UTF_8);
            String last = lines.isEmpty() ? "failed" : lines.getLast().strip();
            return last.length() > 300 ? last.substring(0, 300) : last;
        } catch (IOException e) {
            return "failed";
        }
    }

    private static Path tempFile(String prefix, String suffix) throws MediaUnavailableException {
        try {
            return Files.createTempFile(prefix, suffix);
        } catch (IOException e) {
            throw new MediaUnavailableException("No temp space: " + e, e);
        }
    }

    static void deleteQuietly(Path p) {
        try {
            if (p != null) {
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
            // temp file; the OS cleans its temp folder
        }
    }
}
