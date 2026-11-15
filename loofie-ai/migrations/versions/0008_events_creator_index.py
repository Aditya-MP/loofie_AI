"""events: index on the creator in the payload (track record lookups)

Revision ID: 0008
Revises: 0007
Create Date: 2026-11-15 15:30:00
"""

from alembic import op

revision = "0008"
down_revision = "0007"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("CREATE INDEX ix_events_payload_creator_ref ON events ((payload->>'creator_ref'))")
    op.execute("CREATE INDEX ix_events_payload_creator_handle ON events ((payload->>'creator_handle'))")


def downgrade() -> None:
    op.execute("DROP INDEX ix_events_payload_creator_handle")
    op.execute("DROP INDEX ix_events_payload_creator_ref")
