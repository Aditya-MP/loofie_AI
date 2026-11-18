package com.loofie.core.draft.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class VideoChecksTest {

    private static final Brief DEFAULTS = Brief.defaults();

    private static List<String> codes(VideoChecks.Video v, Brief b) {
        return VideoChecks.check(v, b).stream().map(VideoChecks.Failure::code).toList();
    }

    @Test
    void aFullHdPortraitWithSoundInRangePasses() {
        assertThat(codes(new VideoChecks.Video(true, true, 1080, 1920, 30, true), DEFAULTS)).isEmpty();
        assertThat(codes(new VideoChecks.Video(true, true, 2160, 3840, 30, true), DEFAULTS)).as("4K portrait").isEmpty();
        // Editors export 1080x1918 or 1088x1920: inside the 2 % tolerance.
        assertThat(codes(new VideoChecks.Video(true, true, 1088, 1920, 30, true), DEFAULTS)).isEmpty();
    }

    @Test
    void durationHasHalfASecondOfToleranceEachWay() {
        assertThat(codes(new VideoChecks.Video(true, true, 1080, 1920, 14.6, true), DEFAULTS)).isEmpty();
        assertThat(codes(new VideoChecks.Video(true, true, 1080, 1920, 14.4, true), DEFAULTS)).containsExactly("TOO_SHORT");
        assertThat(codes(new VideoChecks.Video(true, true, 1080, 1920, 90.4, true), DEFAULTS)).isEmpty();
        assertThat(codes(new VideoChecks.Video(true, true, 1080, 1920, 90.6, true), DEFAULTS)).containsExactly("TOO_LONG");
    }

    @Test
    void everyProblemIsListedAtOnce() {
        assertThat(codes(new VideoChecks.Video(true, true, 1280, 720, 200, false), DEFAULTS))
                .containsExactly("NOT_PORTRAIT", "TOO_SMALL", "TOO_LONG", "NO_AUDIO");
        assertThat(codes(new VideoChecks.Video(true, true, 1080, 1080, 30, true), DEFAULTS))
                .containsExactly("NOT_PORTRAIT", "TOO_SMALL");
        assertThat(codes(VideoChecks.Video.unreadable(), DEFAULTS)).containsExactly("UNREADABLE");
        assertThat(codes(new VideoChecks.Video(true, false, 0, 0, 30, true), DEFAULTS)).containsExactly("NO_VIDEO");
    }

    @Test
    void aBriefMustMakeSense() {
        List<Brief.ChecklistItem> one = List.of(new Brief.ChecklistItem("hook", "Hook"));
        assertThatThrownBy(() -> new Brief(one, 30, 20, null, true)).isInstanceOf(Brief.InvalidBriefException.class);
        assertThatThrownBy(() -> new Brief(one, 1, 601, null, true)).isInstanceOf(Brief.InvalidBriefException.class);
        assertThatThrownBy(() -> new Brief(List.of(), 1, 60, null, true)).isInstanceOf(Brief.InvalidBriefException.class);
        assertThatThrownBy(() -> new Brief(List.of(new Brief.ChecklistItem("a", "x"), new Brief.ChecklistItem("a", "y")),
                1, 60, null, true)).isInstanceOf(Brief.InvalidBriefException.class);
        assertThatThrownBy(() -> new Brief.ChecklistItem("Has Space", "x")).isInstanceOf(Brief.InvalidBriefException.class);
        assertThatThrownBy(() -> new Brief(one, 1, 60, 0, true)).isInstanceOf(Brief.InvalidBriefException.class);
    }
}
