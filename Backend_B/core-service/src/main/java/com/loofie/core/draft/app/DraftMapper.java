package com.loofie.core.draft.app;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.loofie.core.draft.domain.Brief;
import com.loofie.core.draft.domain.VideoChecks;
import com.loofie.core.draft.infra.DraftRepository;
import com.loofie.core.draft.infra.ReviewRepository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Rows to views, and the JSON columns both ways. */
@Component
class DraftMapper {

    private final JsonMapper json;

    DraftMapper(JsonMapper json) {
        this.json = json;
    }

    DraftViews.Draft draft(DraftRepository.Draft d) {
        return new DraftViews.Draft(d.id(), d.attempt(), d.kind(), d.status(), failures(d.failuresJson()),
                probe(d.probeJson()), d.submittedAt(), d.readyAt());
    }

    DraftViews.Review review(ReviewRepository.Review r) {
        return new DraftViews.Review(r.draftId(), r.decision(), answers(r.answersJson()), r.note(), r.reviewedAt());
    }

    static DraftViews.BriefView brief(Brief b) {
        return new DraftViews.BriefView(b.checklist(), b.minSeconds(), b.maxSeconds(), b.fromBrief());
    }

    String failuresJson(List<VideoChecks.Failure> failures) {
        return json.writeValueAsString(failures);
    }

    String probeJson(DraftViews.Probe p) {
        return p == null ? null : json.writeValueAsString(p);
    }

    String answersJson(List<DraftViews.Answer> answers) {
        return json.writeValueAsString(answers);
    }

    List<VideoChecks.Failure> failures(String s) {
        if (s == null) {
            return List.of();
        }
        return json.readValue(s, new TypeReference<List<VideoChecks.Failure>>() {
        });
    }

    DraftViews.Probe probe(String s) {
        if (s == null) {
            return null;
        }
        JsonNode n = json.readTree(s);
        return new DraftViews.Probe(n.path("width").asInt(0), n.path("height").asInt(0), n.path("seconds").asDouble(0),
                n.path("hasAudio").asBoolean(false));
    }

    List<DraftViews.Answer> answers(String s) {
        List<Map<String, Object>> raw = json.readValue(s, new TypeReference<List<Map<String, Object>>>() {
        });
        return raw.stream().map(m -> new DraftViews.Answer((String) m.get("key"), (String) m.get("label"),
                Boolean.TRUE.equals(m.get("ok")), (String) m.get("note"))).toList();
    }
}
