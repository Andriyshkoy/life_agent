"""Backfill the required life-events stream for every existing owner."""

from collections.abc import Sequence

from alembic import op

revision: str = "20260803_0005"
down_revision: str | Sequence[str] | None = "20260731_0004"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.execute(
        "LOCK TABLE person, sync_stream, capture, life_event, event_revision "
        "IN SHARE ROW EXCLUSIVE MODE"
    )
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1
                FROM person
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM sync_stream
                    WHERE sync_stream.person_id = person.person_id
                      AND sync_stream.protocol_stream = 'life_events'
                )
                  AND (
                      EXISTS (
                          SELECT 1 FROM capture
                          WHERE capture.person_id = person.person_id
                      )
                      OR EXISTS (
                          SELECT 1 FROM life_event
                          WHERE life_event.person_id = person.person_id
                      )
                      OR EXISTS (
                          SELECT 1 FROM event_revision
                          WHERE event_revision.person_id = person.person_id
                      )
                  )
            ) THEN
                RAISE EXCEPTION
                    'owner data without a life-events stream prevents automatic backfill';
            END IF;
        END;
        $$
        """
    )
    op.execute(
        """
        INSERT INTO sync_stream (
            sync_stream_id,
            person_id,
            protocol_stream,
            last_server_sequence,
            minimum_available_sequence,
            purge_generation
        )
        SELECT
            gen_random_uuid(),
            person.person_id,
            'life_events',
            0,
            0,
            person.purge_generation
        FROM person
        WHERE NOT EXISTS (
            SELECT 1
            FROM sync_stream
            WHERE sync_stream.person_id = person.person_id
              AND sync_stream.protocol_stream = 'life_events'
        )
        """
    )
    op.execute(
        """
        DO $$
        BEGIN
            IF EXISTS (
                SELECT 1
                FROM person
                LEFT JOIN sync_stream
                  ON sync_stream.person_id = person.person_id
                 AND sync_stream.protocol_stream = 'life_events'
                WHERE sync_stream.sync_stream_id IS NULL
                   OR sync_stream.purge_generation <> person.purge_generation
            ) THEN
                RAISE EXCEPTION 'owner life-events stream backfill is incoherent';
            END IF;
        END;
        $$
        """
    )


def downgrade() -> None:
    # The migration repairs required owner data. Keeping those rows is safe for
    # the previous application revision and avoids deleting user sync history.
    pass
