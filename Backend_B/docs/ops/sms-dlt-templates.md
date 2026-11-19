# SMS: DLT registration and the two templates (open ask 32)

Every business SMS in India must match a template registered on an operator's DLT platform (TRAI rules). Sending
starts only after registration, which takes **1-3 weeks**, so it is the critical path for login by SMS.

## Steps (the company does these, not the developer)

1. **Register the company as a Principal Entity** on one operator portal (Jio, Airtel, Vi or BSNL; the entity ID
   works on all). Documents usually asked: PAN, certificate of incorporation, GST certificate, an authorisation letter
   for the person registering, that person's ID. Fee: sources range from nothing to about Rs 5,900 one-time (BSNL's is
   not refunded if rejected). About 1-7 working days.
2. **Register the sender ID (header)**: 6 letters for service messages, for example `LOOFIE`. 1-7 working days.
3. **Register the two templates below** under the category **Service Implicit** (transactional is for banks only).
   1-7 working days.
4. **Add the SMS provider as your telemarketer** on the portal, and give the provider the entity ID, header and
   template IDs. The recommended provider is MSG91 (about Rs 0.16-0.25 per SMS plus GST; confirm on their rate card).

## The templates

They must match what the code sends, word for word. Since TRAI's direction of 18 Nov 2025 every variable is tagged
by type; `{#numeric#}` is a number.

| Use | Template to register | Code |
|---|---|---|
| Every login and phone-change code | `{#numeric#} is your Loofie verification code. It expires in {#numeric#} minutes. Do not share it with anyone.` | `OtpService.SMS_TEXT` |
| Account suspended | `Your Loofie account has been suspended. To appeal, write to Loofie support from the app or the website.` | `AccountAdminService.SUSPENDED_SMS` |

- One code template serves every purpose (login, phone change, staff): no rule against it was found, and it keeps
  one template to maintain.
- The suspension SMS has no variable on purpose: a typed variable holds at most 40 characters, and the reason ops
  types can be 500. The reason goes by email only (email has no template rule). An SMS cannot be replied to, so the
  text points to support instead.
- No links in either. A link in an SMS must be whitelisted first and public link shorteners are banned.
- If anyone changes these texts in the code, the template must be registered again first, or delivery fails.

## Email (open ask 33)

Recommended: Amazon SES in Mumbai if hosting is AWS (about USD 0.10 per 1,000), otherwise Zoho ZeptoMail. Send from a
subdomain (for example `mail.<domain>`), with SPF, DKIM (2048-bit) and DMARC starting at `p=none` with reports, then
`quarantine`, then `reject` after 2-4 weeks of clean reports.

## WhatsApp, if notices go there (open ask 34)

Create the WhatsApp Business account **billing in INR** with India as the primary business location. Accounts still
billing in USD stop delivering on 1 Jan 2027 and cannot be converted. Reminders and refund decisions are "utility"
templates (about Rs 0.115 each); any promotional wording turns them into marketing at about 7.5 times the price.
Collect the opt-in inside the app; an opt-in message on WhatsApp is itself billed as marketing.
