package com.loofie.core.creator.app;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * F1 round C settings.
 *
 * @param categories              the fixed category list (decision C3: Backend A's list, so filters and matching agree)
 * @param languages               languages a creator can pick (up to 3)
 * @param requireBookableToAccept decision C6: a creator who is not bookable cannot accept an invitation. Tests and
 *                                local dev headers use made-up creator ids, so they switch it off; production keeps it
 */
@ConfigurationProperties("loofie.creators")
public record CreatorProperties(List<String> categories, List<String> languages, boolean requireBookableToAccept) {

    public CreatorProperties {
        categories = categories == null || categories.isEmpty()
                ? List.of("beauty_skincare", "personal_care", "other") : List.copyOf(categories);
        languages = languages == null || languages.isEmpty()
                ? List.of("Hindi", "English", "Marathi", "Tamil", "Telugu", "Kannada", "Malayalam", "Bengali",
                "Gujarati", "Punjabi", "Odia", "Assamese", "Urdu") : List.copyOf(languages);
    }
}
