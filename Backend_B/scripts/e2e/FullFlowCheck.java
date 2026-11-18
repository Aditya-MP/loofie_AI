import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * End-to-end check of F1 rounds A + B against a running app, the way the web app calls it: real cookies, CSRF header,
 * OTP codes read from the fake sender's log line. Every step prints PASS / FAIL; exit code 1 on any failure.
 *
 * Needs the app on the local profile (fake sender, dev secrets), its log written to {logFile}, and
 * LOOFIE_BOOTSTRAP_FOUNDER_EMAIL={founderEmail} with no founder in the database yet.
 *
 * Usage: java FullFlowCheck.java <logFile> <founderEmail>
 */
public class FullFlowCheck {

    static final String BASE = "http://localhost:8081";
    static Path log;
    static int failures = 0;
    static int checks = 0;

    /** One browser: its own cookie jar and the CSRF token it last got. */
    static final class Browser {
        final String name;
        final HttpClient http;
        String csrf;

        Browser(String name) {
            this.name = name;
            this.http = HttpClient.newBuilder().cookieHandler(new CookieManager())
                    .connectTimeout(Duration.ofSeconds(5)).build();
        }

        Res call(String method, String path, String json, boolean sendCsrf) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(json));
            if (sendCsrf && csrf != null) {
                b.header("X-CSRF-Token", csrf);
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            Res res = new Res(r.statusCode(), r.body());
            String t = res.str("csrfToken");
            if (t != null) {
                csrf = t;
            }
            return res;
        }

        Res get(String path) throws Exception {
            return call("GET", path, null, false);
        }

        Res post(String path, String json) throws Exception {
            return call("POST", path, json, true);
        }
    }

    record Res(int status, String body) {
        String str(String field) {
            Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
            return m.find() ? m.group(1) : null;
        }

        boolean isNull(String field) {
            return Pattern.compile("\"" + field + "\"\\s*:\\s*null").matcher(body).find();
        }

        boolean bool(String field) {
            return Pattern.compile("\"" + field + "\"\\s*:\\s*true").matcher(body).find();
        }
    }

    public static void main(String[] args) throws Exception {
        log = Path.of(args[0]);
        String founderEmail = args[1];
        String run = String.valueOf(System.currentTimeMillis() % 100_000_000L);
        String phone = "+9198" + String.format("%08d", Long.parseLong(run));
        String adminEmail = "admin-" + run + "@e2e.test";
        String memberEmail = "member-" + run + "@e2e.test";

        section("1. Creator signup with phone OTP");
        Browser creator = new Browser("creator");
        String ch = sendOtp(creator, "PHONE", phone);
        check("wrong code is refused", creator.post("/api/core/auth/otp/verify",
                "{\"challengeId\":\"" + ch + "\",\"code\":\"000000\"}").status() >= 400);
        Res v = creator.post("/api/core/auth/otp/verify", "{\"challengeId\":\"" + ch + "\",\"code\":\"" + code(phone)
                + "\"}");
        check("new phone asks for signup details", v.status() == 200 && "SIGNUP_DETAILS_REQUIRED".equals(v.str("next"))
                && "CREATOR".equals(v.str("signupAccountType")));
        check("signup without accepting terms is refused", creator.post("/api/core/auth/signup",
                "{\"challengeId\":\"" + ch + "\",\"accountType\":\"CREATOR\",\"acceptTerms\":false,"
                        + "\"confirmAdult\":true}").status() >= 400);
        Res s = creator.post("/api/core/auth/signup", "{\"challengeId\":\"" + ch + "\",\"accountType\":\"CREATOR\","
                + "\"acceptTerms\":true,\"confirmAdult\":true,\"marketing\":false}");
        check("signup logs the creator in", s.status() == 200 && "DONE".equals(s.str("next"))
                && "CREATOR".equals(s.str("role")) && creator.csrf != null);
        Res me = creator.get("/api/core/auth/me");
        check("/me after reload: CREATOR, cookie alone", me.status() == 200 && "CREATOR".equals(me.str("role")));
        check("creator can read their inbox", creator.get("/api/core/notifications").status() == 200);
        check("creator cannot open brand company", creator.get("/api/core/brand/company").status() == 403);
        check("logout without CSRF header is refused (403)",
                creator.call("POST", "/api/core/auth/logout", null, false).status() == 403);
        check("logout with CSRF header works (204)", creator.post("/api/core/auth/logout", null).status() == 204);
        check("after logout /me is 401", creator.get("/api/core/auth/me").status() == 401);

        section("2. Creator logs in again (existing account, no signup step)");
        Browser creator2 = new Browser("creator-again");
        String ch2 = sendOtp(creator2, "PHONE", phone);
        Res v2 = creator2.post("/api/core/auth/otp/verify", "{\"challengeId\":\"" + ch2 + "\",\"code\":\""
                + code(phone) + "\"}");
        check("existing phone goes straight in", v2.status() == 200 && "DONE".equals(v2.str("next"))
                && "CREATOR".equals(v2.str("role")));
        check("the used code cannot be used again", creator2.post("/api/core/auth/otp/verify",
                "{\"challengeId\":\"" + ch2 + "\",\"code\":\"" + code(phone) + "\"}").status() >= 400);

        section("2b. Creator passport: manual path with a real upload (round C)");
        Res p0 = creator2.get("/api/core/creator/profile");
        check("new creator: passport not complete, form fields listed as missing", p0.status() == 200
                && p0.body().contains("\"completed\":false") && p0.body().contains("\"insightsScreenshot\""));
        String passport = "{\"displayName\":\"E2E Creator\",\"city\":\"Pune\",\"state\":\"MH\",\"languages\":[\"Hindi\"],"
                + "\"categories\":[\"beauty_skincare\"],\"formats\":[\"REEL\"],\"ratePerReelRupees\":2500,"
                + "\"partnershipAds\":true,\"turnaroundDays\":5,\"sampleLinks\":[\"https://www.instagram.com/reel/E2E/\"],"
                + "\"instagramHandle\":\"e2e.creator." + run + "\",\"followers\":9100,\"avgViews\":2300}";
        check("passport form without CSRF is refused",
                creator2.call("PUT", "/api/core/creator/profile", passport, false).status() == 403);
        Res filled = creator2.call("PUT", "/api/core/creator/profile", passport, true);
        check("form saved; only the Insights screenshot is missing", filled.status() == 200
                && filled.body().contains("\"missing\":[\"insightsScreenshot\"]"));
        Res ticket = creator2.post("/api/core/creator/uploads", "{\"contentType\":\"image/png\"}");
        String uploadUrl = ticket.str("url");
        check("upload ticket: signed PUT URL for one image", ticket.status() == 201 && uploadUrl != null
                && uploadUrl.contains("sig="));
        int put = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(uploadUrl))
                .header("Content-Type", "image/png").PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[2048]))
                .build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        check("the browser uploads straight to storage with the signed URL", put == 200);
        Res attached = creator2.post("/api/core/creator/evidence", "{\"uploadKey\":\"" + ticket.str("key")
                + "\",\"kind\":\"INSIGHTS\"}");
        check("screenshot attached: passport complete and bookable", attached.status() == 201
                && attached.body().contains("\"completed\":true") && attached.body().contains("\"bookable\":true"));
        check("/me now says onboarding completed", creator2.get("/api/core/auth/me").bool("onboardingCompleted"));

        section("3. Brand admin signup with email OTP, then company");
        Browser admin = new Browser("brand-admin");
        String ach = sendOtp(admin, "EMAIL", adminEmail);
        Res av = admin.post("/api/core/auth/otp/verify", "{\"challengeId\":\"" + ach + "\",\"code\":\""
                + code(adminEmail) + "\"}");
        check("new email asks for signup details as BRAND", "SIGNUP_DETAILS_REQUIRED".equals(av.str("next"))
                && "BRAND".equals(av.str("signupAccountType")));
        Res as = admin.post("/api/core/auth/signup", "{\"challengeId\":\"" + ach + "\",\"accountType\":\"BRAND\","
                + "\"acceptTerms\":true,\"confirmAdult\":true}");
        check("brand signup logs in, no company yet (show company form)", as.status() == 200
                && "DONE".equals(as.str("next")) && as.isNull("orgId") && !as.bool("onboardingCompleted"));
        check("brand without company cannot use brand features (403)",
                admin.get("/api/core/notifications").status() == 403);
        String company = "{\"legalName\":\"E2E Foods Pvt Ltd " + run + "\",\"billing\":{\"line1\":\"12 MG Road\","
                + "\"city\":\"Pune\",\"state\":\"MH\",\"pincode\":\"411001\"},\"pickupState\":\"MH\"}";
        check("company form without CSRF is refused",
                admin.call("POST", "/api/core/brand/company", company, false).status() == 403);
        check("bad PIN code is refused (422)", admin.post("/api/core/brand/company",
                company.replace("411001", "41100")).status() == 422);
        Res c = admin.post("/api/core/brand/company", company);
        check("company created", c.status() == 200 || c.status() == 201);
        Res ame = admin.get("/api/core/auth/me");
        String orgId = ame.str("orgId");
        check("/me: BRAND_ADMIN of the company, onboarding done", "BRAND_ADMIN".equals(ame.str("role"))
                && orgId != null && ame.bool("onboardingCompleted"));
        check("brand inbox now works", admin.get("/api/core/notifications").status() == 200);
        Res comp = admin.get("/api/core/brand/company");
        check("company view: not ready for briefs without GSTIN", comp.status() == 200
                && comp.body().contains("\"readyForBriefs\":false"));
        check("wrong GSTIN check character is refused (422)", admin.call("PUT", "/api/core/brand/company/gstin",
                "{\"gstin\":\"27AAPFU0939F1ZA\"}", true).status() == 422);
        check("second company for the same person is refused",
                admin.post("/api/core/brand/company", company).status() >= 400);

        section("4. Teammate joins by invite");
        Res inv = admin.post("/api/core/brand/team/invites", "{\"email\":\"" + memberEmail
                + "\",\"role\":\"BRAND_MEMBER\"}");
        String link = inv.str("link");
        check("admin invites a member (link returned)", (inv.status() == 200 || inv.status() == 201)
                && link != null && link.contains("#"));
        String token = link == null ? "" : link.substring(link.indexOf('#') + 1);
        Browser member = new Browser("member");
        String mch = sendOtp(member, "EMAIL", memberEmail);
        member.post("/api/core/auth/otp/verify", "{\"challengeId\":\"" + mch + "\",\"code\":\"" + code(memberEmail)
                + "\"}");
        Browser stranger = new Browser("stranger");
        String sch = sendOtp(stranger, "EMAIL", "stranger-" + run + "@e2e.test");
        stranger.post("/api/core/auth/otp/verify", "{\"challengeId\":\"" + sch + "\",\"code\":\""
                + code("stranger-" + run + "@e2e.test") + "\"}");
        check("invite cannot be used by another email", stranger.post("/api/core/auth/invites/accept",
                "{\"challengeId\":\"" + sch + "\",\"inviteToken\":\"" + token + "\",\"acceptTerms\":true,"
                        + "\"confirmAdult\":true}").status() >= 400);
        Res ma = member.post("/api/core/auth/invites/accept", "{\"challengeId\":\"" + mch + "\",\"inviteToken\":\""
                + token + "\",\"acceptTerms\":true,\"confirmAdult\":true}");
        check("invited person joins as BRAND_MEMBER of the same company", ma.status() == 200
                && "BRAND_MEMBER".equals(ma.str("role")) && orgId != null && orgId.equals(ma.str("orgId")));
        String memberId = ma.str("id");
        check("invite cannot be used twice", new Browser("x").post("/api/core/auth/invites/accept",
                "{\"challengeId\":\"" + mch + "\",\"inviteToken\":\"" + token + "\",\"acceptTerms\":true,"
                        + "\"confirmAdult\":true}").status() >= 400);
        check("member can see the company", member.get("/api/core/brand/company").status() == 200);
        check("member cannot invite (403)", member.post("/api/core/brand/team/invites",
                "{\"email\":\"x-" + run + "@e2e.test\",\"role\":\"BRAND_MEMBER\"}").status() == 403);
        check("member cannot edit the company (403)",
                member.call("PUT", "/api/core/brand/company", company, true).status() == 403);

        section("5. Role change and removal take effect on the next request");
        check("admin promotes member", admin.call("PUT", "/api/core/brand/team/members/" + memberId + "/role",
                "{\"role\":\"BRAND_ADMIN\"}", true).status() / 100 == 2);
        check("member's next request is as BRAND_ADMIN",
                "BRAND_ADMIN".equals(member.get("/api/core/auth/me").str("role")));
        String adminId = ame.str("id");
        check("promoted member demotes the original admin", member.call("PUT", "/api/core/brand/team/members/"
                + adminId + "/role", "{\"role\":\"BRAND_MEMBER\"}", true).status() / 100 == 2);
        check("the last admin cannot leave", member.post("/api/core/brand/team/leave", null).status() >= 400);
        check("admin removes the other person", member.call("DELETE", "/api/core/brand/team/members/" + adminId,
                null, true).status() / 100 == 2);
        Res removed = admin.get("/api/core/auth/me");
        check("removed person: still logged in, no company (company form again)", removed.status() == 200
                && removed.isNull("orgId"));
        check("removed person cannot read the company", admin.get("/api/core/brand/company").status() >= 400);

        section("6. Founder: email OTP + authenticator app");
        Browser founder = new Browser("founder");
        String fch = sendOtp(founder, "EMAIL", founderEmail);
        Res fv = founder.post("/api/core/auth/otp/verify", "{\"challengeId\":\"" + fch + "\",\"code\":\""
                + code(founderEmail) + "\"}");
        String secret = fv.str("totpSecret");
        check("first founder login asks to set up the authenticator", "MFA_ENROL_REQUIRED".equals(fv.str("next"))
                && secret != null);
        check("staff session is not usable before MFA", founder.get("/api/core/ops/audit-log").status() >= 400);
        check("wrong authenticator code is refused",
                founder.post("/api/core/auth/mfa/enrol", "{\"code\":\"000000\"}").status() >= 400);
        Res fe = founder.post("/api/core/auth/mfa/enrol", "{\"code\":\"" + totp(secret) + "\"}");
        check("right authenticator code: logged in as FOUNDER with recovery codes", fe.status() == 200
                && "DONE".equals(fe.str("next")) && "FOUNDER".equals(fe.str("role"))
                && fe.body().contains("recoveryCodes\":[\""));
        check("ops can open the brand's company", founder.get("/api/core/ops/organizations/" + orgId).status() == 200);
        Res audit = founder.get("/api/core/ops/audit-log?targetOrgId=" + orgId);
        check("audit trail for the company has the team events", audit.status() == 200
                && audit.body().contains("ORG_CREATED") && audit.body().contains("MEMBER_JOINED")
                && audit.body().contains("MEMBER_REMOVED"));
        check("brand user cannot open ops pages (403)",
                member.get("/api/core/ops/organizations/" + orgId).status() == 403);
        check("log out everywhere", founder.post("/api/core/auth/logout-all", null).status() == 204
                && founder.get("/api/core/auth/me").status() == 401);

        section("6b. Account deletion (round C)");
        Res delStart = creator2.post("/api/core/account/delete/start", null);
        check("deletion asks for a fresh code to her own phone", delStart.status() == 202
                && delStart.str("challengeId") != null);
        Res wrongDel = creator2.post("/api/core/account/delete", "{\"challengeId\":\"" + delStart.str("challengeId")
                + "\",\"code\":\"000000\"}");
        check("a wrong code deletes nothing", wrongDel.status() == 400
                && creator2.get("/api/core/auth/me").status() == 200);
        Res del = creator2.post("/api/core/account/delete", "{\"challengeId\":\"" + delStart.str("challengeId")
                + "\",\"code\":\"" + code(phone) + "\"}");
        check("the right code deletes the account; the session is gone", del.status() == 204
                && creator2.get("/api/core/auth/me").status() == 401);
        Browser reborn = new Browser("same-phone-again");
        String rch = sendOtp(reborn, "PHONE", phone);
        Res rv = reborn.post("/api/core/auth/otp/verify", "{\"challengeId\":\"" + rch + "\",\"code\":\"" + code(phone)
                + "\"}");
        check("the same phone can sign up again as a new account", "SIGNUP_DETAILS_REQUIRED".equals(rv.str("next")));

        section("7. Abuse limit");
        Browser spam = new Browser("spam");
        String spamPhone = "+9197" + String.format("%08d", Long.parseLong(run));
        int last = 0;
        for (int i = 0; i < 4; i++) {
            last = spam.post("/api/core/auth/otp/send", "{\"channel\":\"PHONE\",\"destination\":\"" + spamPhone
                    + "\"}").status();
        }
        check("4th code to one number within 15 min is refused (429)", last == 429);

        System.out.printf("%n%d checks, %d failed%n", checks, failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    static String sendOtp(Browser b, String channel, String dest) throws Exception {
        Res r = b.post("/api/core/auth/otp/send", "{\"channel\":\"" + channel + "\",\"destination\":\"" + dest + "\"}");
        check(b.name + ": code sent (202)", r.status() == 202);
        return r.str("challengeId");
    }

    /** The newest code the fake sender logged (masked destination, so the newest OTP line overall). */
    static String code(String destinationHint) throws Exception {
        Pattern p = Pattern.compile("\\(OTP\\): (\\d{6}) is your Loofie");
        for (int i = 0; i < 20; i++) {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            for (int k = lines.size() - 1; k >= 0; k--) {
                Matcher m = p.matcher(lines.get(k));
                if (m.find()) {
                    return m.group(1);
                }
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("no OTP in the log for " + destinationHint);
    }

    static String totp(String base32) throws Exception {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        String s = base32.replace("=", "").toUpperCase();
        byte[] out = new byte[s.length() * 5 / 8];
        int buffer = 0, bits = 0, n = 0;
        for (char ch : s.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(ch);
            bits += 5;
            if (bits >= 8) {
                out[n++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
            }
        }
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(out, "HmacSHA1"));
        long step = System.currentTimeMillis() / 1000 / 30;
        byte[] h = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(step).array());
        int o = h[h.length - 1] & 0xf;
        int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
        return String.format("%06d", bin % 1_000_000);
    }

    static void section(String name) {
        System.out.println("\n== " + name);
    }

    static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
    }
}
