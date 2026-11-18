package com.loofie.core.messaging;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a creator-stream outbox row ({@link Outbox#CREATOR_STREAM}) into the Backend A call it stands for:
 * <ul>
 *   <li>{@code creator_profile_updated} → {@code POST /ai/creators/profile} (self-reported numbers and descriptions,
 *       {@code source: manual_profile}; their quality check runs on it)</li>
 *   <li>{@code instagram_data_delete} → {@code DELETE /ai/instagram/accounts/{ref}?delete_data=true} (account deletion,
 *       DPDP)</li>
 * </ul>
 */
@Component
public class BackendACreatorCalls {

    public static final String PROFILE_UPDATED = "creator_profile_updated";
    public static final String INSTAGRAM_DATA_DELETE = "instagram_data_delete";

    /** Backend A's primary_language accepts only these; anything else is sent as "Other". */
    static final Set<String> BACKEND_A_LANGUAGES = Set.of("English", "Kannada", "Hindi", "Tamil", "Telugu",
            "Malayalam");

    /** @param body null for calls without one */
    public record Call(String method, String path, Map<String, Object> body) {
    }

    private final JsonMapper json;

    public BackendACreatorCalls(JsonMapper json) {
        this.json = json;
    }

    public Optional<Call> toCall(String eventType, String envelope) {
        JsonNode p = json.readTree(envelope).path("payload");
        String ref = p.path("creatorRef").asString("");
        return switch (eventType) {
            case PROFILE_UPDATED -> {
                String handle = p.path("handle").asString("");
                if (handle.isEmpty() || ref.isEmpty()) {
                    yield Optional.empty(); // nothing Backend A can key it on: skip, never send half a profile
                }
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("handle", handle);
                b.put("creator_ref", ref);
                b.put("source", "manual_profile");
                put(b, "display_name", p, "displayName");
                put(b, "city", p, "city");
                put(b, "bio", p, "bio");
                if (p.path("followers").isNumber()) {
                    b.put("followers", p.path("followers").asLong());
                }
                List<String> langs = p.path("languages").valueStream().map(JsonNode::asString).toList();
                if (!langs.isEmpty()) {
                    b.put("primary_language", BACKEND_A_LANGUAGES.contains(langs.getFirst()) ? langs.getFirst()
                            : "Other");
                }
                put(b, "level", p, "level");
                yield Optional.of(new Call("POST", "/ai/creators/profile", b));
            }
            case INSTAGRAM_DATA_DELETE -> ref.isEmpty() ? Optional.empty() : Optional.of(new Call("DELETE",
                    "/ai/instagram/accounts/" + URLEncoder.encode(ref, StandardCharsets.UTF_8) + "?delete_data=true",
                    null));
            default -> Optional.empty();
        };
    }

    private static void put(Map<String, Object> b, String to, JsonNode p, String from) {
        JsonNode v = p.path(from);
        if (!v.isMissingNode() && !v.isNull() && !v.asString("").isEmpty()) {
            b.put(to, v.asString());
        }
    }
}
