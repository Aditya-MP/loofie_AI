"""Allowed values. Must match the Lists tab of Loofie_AI_Data_Collection.xlsx."""

# Loofie serves beauty and personal care. The v2 sheet also labels creators outside it; they are
# loaded with their label so briefs leave them out ("different category") instead of guessing.
IN_SCOPE = {"beauty_skincare", "personal_care"}
OUT_OF_SCOPE = {"fashion", "food_beverage", "fitness", "technology", "other"}
CATEGORY = IN_SCOPE | OUT_OF_SCOPE
SUB_NICHE = {"skincare", "haircare", "makeup", "mens_grooming", "body_care", "fragrance"} | {
    "fashion",
    "food",
    "fitness",
    "technology",
    "other",
}
LANGUAGE = {"English", "Kannada", "Hindi", "Tamil", "Telugu", "Malayalam", "Other"}
POST_TYPE = {"reel", "photo", "carousel"}
EVIDENCE = {"paid_partnership_label", "ad_hashtag", "ad_library_partnership_ad", "brand_tagged_only"}
QUALITY = {"real", "suspicious", "unsure"}
BRIEF_SOURCE = {"public_brand_post", "written_from_product_page"}
FORMAT = {"9:16 reel", "photo", "carousel", "story"}
