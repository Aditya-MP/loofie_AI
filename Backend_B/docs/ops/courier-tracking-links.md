# Courier tracking links (open ask 38)

Ops enters each courier's link pattern in the courier table (`tracking_url_pattern`, `{tracking}` is replaced by the
number). The patterns below were tried with dummy numbers on 2026-11-19, **never with a real AWB in a browser**. Open
each one with a real number before saving it.

| Courier | Pattern to try | Confidence | Note |
|---|---|---|---|
| Delhivery | `https://www.delhivery.com/track-v2/package/{tracking}` | High | No captcha. The old `/track/package/` link redirects here. Official tracking API exists (needs a token) |
| Ekart | `https://ekartlogistics.com/shipmenttrack/{tracking}` | Medium | Redirects to `/ekartlogistics-web/shipmenttrack/...` |
| Xpressbees | `https://www.xpressbees.com/shipment/tracking?awbNo={tracking}` | Medium-low | Page is built by JavaScript; could not confirm it shows the parcel |
| Shadowfax | `https://tracker.shadowfax.in/#/track/{tracking}` | Medium-low | The number sits after `#` |
| Blue Dart | `https://www.bluedart.com/web/guest/trackdartresult?trackFor=0&trackNo={tracking}` | Medium | Captcha: the link may only open the form |
| DTDC | none (`https://www.dtdc.com/track-your-shipment/`) | Low | Captcha. The old `dtdc.in/trace.asp` links are dead |
| India Post | none | Low | Old page answers 502, new site has a captcha |
| Shiprocket | none | Low | Aggregator: track with the underlying courier's number |
| Ecom Express | none | Low | Site's certificate had expired; bought by Delhivery in July 2025. Test a real number on Delhivery's link first |

Where a courier has no working pattern, leave it empty: the app shows the number so the creator can copy it into the
courier's site.

**AWB format** (no courier publishes one): only India Post is strict, `2 letters + 9 digits + IN`
(e.g. `EE123456789IN`). Others vary between 8 and 20 letters and digits, so the app does not reject a number by
format.

**Courier proof as the clock start (founder ask 28):** only Delhivery offers a tracking API, so automatic "delivered"
proof is not available for most couriers without a paid tracking service (Ship24, AfterShip, ClickPost).
