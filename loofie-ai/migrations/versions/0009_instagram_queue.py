"""instagram accounts: durable sync queue (next_sync_at, attempts, lease) + business id (unified login)

Revision ID: 0009
Revises: 0008
Create Date: 2026-11-17 14:00:00
"""

import sqlalchemy as sa
from alembic import op

revision = "0009"
down_revision = "0008"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("instagram_accounts", sa.Column("business_id", sa.String(40), nullable=True))
    op.add_column("instagram_accounts", sa.Column("next_sync_at", sa.DateTime(timezone=True), nullable=True))
    op.add_column("instagram_accounts", sa.Column("sync_attempts", sa.Integer(), server_default="0", nullable=False))
    op.add_column("instagram_accounts", sa.Column("lease_until", sa.DateTime(timezone=True), nullable=True))
    # Workers look for due rows constantly: keep that lookup an index scan, not a table scan.
    op.create_index(
        "ix_instagram_accounts_due",
        "instagram_accounts",
        ["next_sync_at"],
        postgresql_where=sa.text("next_sync_at IS NOT NULL"),
    )


def downgrade() -> None:
    op.drop_index("ix_instagram_accounts_due", table_name="instagram_accounts")
    for col in ("lease_until", "sync_attempts", "next_sync_at", "business_id"):
        op.drop_column("instagram_accounts", col)
