"""End to end on the GPU: CSVs → import → profiles → quality → brand-hire test."""

from datetime import date, timedelta

import pytest
import torch
from sqlalchemy import select

from app.db.models import Creator
from app.importer.sheet import import_sheet
from app.matcher.evaluate import evaluate, organic_vectors
from app.profile.builder import build_and_save
from app.quality.service import assess_and_save

pytestmark = pytest.mark.skipif(not torch.cuda.is_available(), reason="needs the GPU and the e5 model")

TODAY = date(2026, 11, 14)

BRANDS = """brand_id,brand_name,instagram_handle,category,sub_niche,what_they_sell,product_description
B-1,Dewdrop,dewdrop.skin,beauty_skincare,skincare,Gel sunscreens for oily skin,SPF 50 gel sunscreen with no white cast for oily and acne-prone skin in humid weather.
B-2,Kesh Roots,kesh_roots,beauty_skincare,haircare,Anti hair fall oils,Ayurvedic onion and bhringraj hair oil that reduces hair fall and dandruff.
"""

CAPTIONS = {
    "sun_sara": [
        "my oily skin sunscreen routine",
        "no white cast test on brown skin",
        "reapplying SPF in humid Bengaluru",
        "matte finish sunscreen comparison",
        "why oily skin still needs moisturiser",
        "sweat proof sunscreen test",
    ],
    "acne_anu": [
        "acne journey week 6",
        "salicylic acid for oily skin",
        "how I stopped breakouts",
        "non comedogenic sunscreen picks",
        "pimple patches review",
        "oil control routine for summer",
    ],
    "curl_kavya": [
        "curly hair wash day",
        "defining curls without frizz",
        "curly girl method update",
        "leave in conditioner test",
        "scalp care for curly hair",
        "plopping my curls",
    ],
    "hairfall_hema": [
        "hair fall update after 3 months",
        "onion oil massage routine",
        "dandruff and hair fall fix",
        "my hair growth progress",
        "weekly hair oiling ritual",
        "postpartum hair fall tips",
    ],
    "makeup_meera": [
        "bridal makeup look",
        "red lipstick swatches",
        "kajal winged liner tutorial",
        "party makeup in 10 minutes",
        "foundation shade matching",
        "smokey eye for beginners",
    ],
}
FOLLOWERS = {
    "sun_sara": 9000,
    "acne_anu": 7000,
    "curl_kavya": 11000,
    "hairfall_hema": 8000,
    "makeup_meera": 45000,
    "paid_only_pia": 30000,
}
LINKS = [
    ("dewdrop.skin", "sun_sara"),
    ("dewdrop.skin", "acne_anu"),
    ("dewdrop.skin", "paid_only_pia"),
    ("kesh_roots", "curl_kavya"),
    ("kesh_roots", "hairfall_hema"),
]


def write_fixture(folder):
    (folder / "Brands.csv").write_text(BRANDS, encoding="utf-8")
    creators = ["instagram_handle,followers,following,city"]
    posts = [
        "creator_handle,post_url,post_date,post_type,views,likes,comments,caption,paid_partnership_label,brands_tagged"
    ]
    links = ["brand_handle,creator_handle,post_url,evidence"]
    for handle in [*CAPTIONS, "paid_only_pia"]:
        creators.append(f"{handle},{FOLLOWERS[handle]},600,Bengaluru")
    for handle, caps in CAPTIONS.items():
        for i, cap in enumerate(caps):
            day = TODAY - timedelta(days=2 + 4 * i)
            posts.append(
                f"{handle},https://www.instagram.com/reel/{handle}{i}/,{day},reel,{3500 + 50 * i},350,30,{cap},no,"
            )
    # Pia's only beauty content is a sponsored post naming the brand; everything else is food.
    for i, cap in enumerate(
        [
            "biryani crawl in old Bengaluru",
            "best dosa spots",
            "street food Sunday",
            "filter coffee ranking",
            "momos taste test",
        ]
    ):
        posts.append(
            f"paid_only_pia,https://www.instagram.com/reel/pia{i}/,{TODAY - timedelta(days=3 + 4 * i)},reel,3600,350,30,{cap},no,"
        )
    posts.append(
        f"paid_only_pia,https://www.instagram.com/reel/pia_ad/,{TODAY - timedelta(days=1)},reel,3600,350,30,"
        f"Loving @dewdrop.skin gel sunscreen for oily skin #ad,yes,dewdrop.skin"
    )
    for brand, creator in LINKS:
        url = (
            "https://www.instagram.com/reel/pia_ad/"
            if creator == "paid_only_pia"
            else f"https://www.instagram.com/reel/{creator}0/"
        )
        links.append(f"{brand},{creator},{url},paid_partnership_label")
    for name, rows in (("Creators", creators), ("Posts", posts), ("Brand_Creator_Links", links)):
        (folder / f"{name}.csv").write_text("\n".join(rows) + "\n", encoding="utf-8")


def test_brand_hire_test_end_to_end(session, tmp_path):
    from app.ml.embed import get_embedder

    e = get_embedder()
    write_fixture(tmp_path)
    assert import_sheet(session, tmp_path).errors == []
    build_and_save(session, e, as_of=TODAY)
    assess_and_save(session, as_of=TODAY)
    session.commit()

    # Cheat check: the organic profile never sees the sponsored caption or the brand handle.
    pia = session.scalar(select(Creator.id).where(Creator.handle == "paid_only_pia"))
    org = organic_vectors(session, e)
    ad = e.passages(["Loving gel sunscreen for oily skin"])[0]
    food = e.passages(["biryani crawl in old Bengaluru"])[0]
    assert org[pia] @ food > org[pia] @ ad

    results = {r.brand: r for r in evaluate(session, e, k=2)}
    dew, kesh = results["dewdrop.skin"], results["kesh_roots"]

    # Haircare brand: both real hires in the top 2, far above picking 2 at random.
    assert kesh.matcher == 1.0 and kesh.filters_only < 0.5
    # Skincare brand: the two skincare creators are found; Pia (food creator who did one ad) is not.
    assert dew.matcher == pytest.approx(2 / 3)
    assert dew.first_hit_rank == 1
    assert dew.popularity < dew.matcher
