"""Usage (through the main command):
loofie instagram maintain     daily job: renew tokens close to expiry, re-fetch accounts older than a week
loofie instagram status       every connected account and its state
loofie instagram worker [-n 4] work the sync queue until stopped (Ctrl+C); run on as many machines as needed
loofie instagram new-key      print a fresh IG_TOKEN_KEY for .env (do this once, then never change it)
"""

import argparse
import sys
from collections import Counter

from sqlalchemy import select

from app.config import get_settings
from app.db.models import InstagramAccount
from app.db.session import SessionLocal
from app.instagram import security


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    ap = argparse.ArgumentParser(prog="loofie instagram")
    ap.add_argument("action", choices=["maintain", "status", "new-key", "worker"])
    ap.add_argument("-n", "--threads", type=int, default=4, help="worker threads (worker only)")
    args = ap.parse_args()

    if args.action == "new-key":
        print(security.new_token_key())
        return 0

    with SessionLocal() as session:
        if args.action == "status":
            accounts = session.scalars(select(InstagramAccount).order_by(InstagramAccount.username)).all()
            print(f"{len(accounts)} connected accounts: {dict(Counter(a.status for a in accounts))}")
            for a in accounts:
                synced = a.last_synced_at.date() if a.last_synced_at else "never"
                expires = a.token_expires_at.date() if a.token_expires_at else "-"
                print(f"  @{a.username:30} {a.status:9} synced {synced}  token until {expires}  {a.last_error or ''}")
            return 0

        s = get_settings()
        if not (s.ig_app_id and s.ig_app_secret and s.ig_token_key):
            print("Instagram login isn't configured (IG_APP_ID / IG_APP_SECRET / IG_TOKEN_KEY in .env).")
            return 2
        from app.instagram.client import InstagramClient
        from app.instagram.service import maintain
        from app.ml.embed import get_embedder

        client = InstagramClient(
            s.ig_app_id, s.ig_app_secret, s.ig_redirect_uri, flavor=s.ig_flavor or None, config_id=s.ig_fb_config_id
        )
        if args.action == "worker":
            import threading

            from app.instagram.service import work_forever

            stop = threading.Event()
            embedder = get_embedder()
            threads = [
                threading.Thread(target=work_forever, args=(SessionLocal, embedder, client, s.ig_token_key, stop))
                for _ in range(max(1, args.threads))
            ]
            for t in threads:
                t.start()
            print(f"{len(threads)} Instagram sync workers running. Ctrl+C to stop.")
            try:
                while any(t.is_alive() for t in threads):
                    for t in threads:
                        t.join(timeout=1)
            except KeyboardInterrupt:
                print("stopping after the current fetches…")
                stop.set()
                for t in threads:
                    t.join()
            return 0
        counts = maintain(session, get_embedder(), client, s.ig_token_key)
        print(f"Instagram maintenance: {counts}")
        return 1 if counts["failed"] else 0


if __name__ == "__main__":
    sys.exit(main())
