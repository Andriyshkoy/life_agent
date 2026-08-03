"""Add durable replay quota accounting and owner identity uniqueness."""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "20260730_0002"
down_revision: str | Sequence[str] | None = "20260730_0001"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.execute("LOCK TABLE device, http_replay IN SHARE ROW EXCLUSIVE MODE")
    op.create_unique_constraint(
        "uq_device_local_owner_id",
        "device",
        ["local_owner_id"],
    )
    op.execute(
        """
        CREATE TABLE device_replay_quota (
            person_id UUID NOT NULL,
            device_id UUID NOT NULL,
            record_count BIGINT DEFAULT 0 NOT NULL,
            response_body_plaintext_bytes BIGINT DEFAULT 0 NOT NULL,
            updated_at TIMESTAMP WITH TIME ZONE
                DEFAULT CURRENT_TIMESTAMP NOT NULL,
            CONSTRAINT pk_device_replay_quota
                PRIMARY KEY (person_id, device_id),
            CONSTRAINT fk_device_replay_quota_person_device
                FOREIGN KEY (person_id, device_id)
                REFERENCES device (person_id, device_id)
                ON DELETE CASCADE,
            CONSTRAINT ck_device_replay_quota_record_count_range
                CHECK (record_count BETWEEN 0 AND 100000),
            CONSTRAINT ck_device_replay_quota_plaintext_bytes_range
                CHECK (
                    response_body_plaintext_bytes
                    BETWEEN 0 AND 536870912
                ),
            CONSTRAINT ck_device_replay_quota_state_coherent CHECK (
                (
                    record_count = 0
                    AND response_body_plaintext_bytes = 0
                )
                OR (
                    record_count > 0
                    AND response_body_plaintext_bytes >= record_count
                )
            )
        )
        """
    )
    op.create_index(
        "uq_enrollment_grant_one_issued_per_person",
        "enrollment_grant",
        ["person_id"],
        unique=True,
        postgresql_where=sa.text("status = 'issued'"),
    )
    op.execute(
        """
        INSERT INTO device_replay_quota (
            person_id,
            device_id,
            record_count,
            response_body_plaintext_bytes
        )
        SELECT
            d.person_id,
            d.device_id,
            count(r.http_replay_id),
            coalesce(sum(r.response_body_plaintext_bytes), 0)
        FROM device AS d
        LEFT JOIN http_replay AS r
          ON r.person_id = d.person_id
         AND r.device_id = d.device_id
        GROUP BY d.person_id, d.device_id
        """
    )
    op.create_foreign_key(
        "fk_http_replay_person_device_quota",
        "http_replay",
        "device_replay_quota",
        ["person_id", "device_id"],
        ["person_id", "device_id"],
        deferrable=True,
        initially="DEFERRED",
    )
    op.create_index(
        "ix_http_replay_person_device_retention",
        "http_replay",
        ["person_id", "device_id", "retention_until"],
    )
    op.create_index(
        "ix_http_replay_family_retention",
        "http_replay",
        ["credential_family_id", "retention_until"],
    )
    op.execute(
        """
        CREATE FUNCTION create_device_replay_quota()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            INSERT INTO device_replay_quota (person_id, device_id)
            VALUES (NEW.person_id, NEW.device_id);
            RETURN NEW;
        END;
        $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER create_device_replay_quota_after_insert
        AFTER INSERT ON device
        FOR EACH ROW
        EXECUTE FUNCTION create_device_replay_quota()
        """
    )
    op.execute(
        """
        CREATE FUNCTION maintain_device_replay_quota()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            IF TG_OP = 'INSERT' THEN
                UPDATE device_replay_quota
                SET
                    record_count = record_count + 1,
                    response_body_plaintext_bytes =
                        response_body_plaintext_bytes
                        + NEW.response_body_plaintext_bytes,
                    updated_at = CURRENT_TIMESTAMP
                WHERE person_id = NEW.person_id
                  AND device_id = NEW.device_id;
                IF NOT FOUND THEN
                    RAISE EXCEPTION
                        'replay quota owner is unavailable'
                        USING ERRCODE = '23503';
                END IF;
                RETURN NEW;
            END IF;

            UPDATE device_replay_quota
            SET
                record_count = record_count - 1,
                response_body_plaintext_bytes =
                    response_body_plaintext_bytes
                    - OLD.response_body_plaintext_bytes,
                updated_at = CURRENT_TIMESTAMP
            WHERE person_id = OLD.person_id
              AND device_id = OLD.device_id
              AND record_count > 0
              AND response_body_plaintext_bytes
                  >= OLD.response_body_plaintext_bytes;
            IF NOT FOUND AND EXISTS (
                SELECT 1
                FROM device
                WHERE person_id = OLD.person_id
                  AND device_id = OLD.device_id
            ) THEN
                RAISE EXCEPTION
                    'replay quota counter is incoherent'
                    USING ERRCODE = '23514';
            END IF;
            RETURN OLD;
        END;
        $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER maintain_device_replay_quota_after_change
        AFTER INSERT OR DELETE ON http_replay
        FOR EACH ROW
        EXECUTE FUNCTION maintain_device_replay_quota()
        """
    )
    op.execute(
        """
        CREATE FUNCTION reject_http_replay_accounting_update()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
            IF (
                NEW.person_id,
                NEW.device_id,
                NEW.response_body_plaintext_bytes
            ) IS DISTINCT FROM (
                OLD.person_id,
                OLD.device_id,
                OLD.response_body_plaintext_bytes
            ) THEN
                RAISE EXCEPTION
                    'replay accounting fields are immutable'
                    USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END;
        $$
        """
    )
    op.execute(
        """
        CREATE TRIGGER reject_http_replay_accounting_update_before_update
        BEFORE UPDATE ON http_replay
        FOR EACH ROW
        EXECUTE FUNCTION reject_http_replay_accounting_update()
        """
    )


def downgrade() -> None:
    op.execute(
        """
        DROP TRIGGER reject_http_replay_accounting_update_before_update
        ON http_replay
        """
    )
    op.execute("DROP FUNCTION reject_http_replay_accounting_update()")
    op.execute(
        """
        DROP TRIGGER maintain_device_replay_quota_after_change
        ON http_replay
        """
    )
    op.execute("DROP FUNCTION maintain_device_replay_quota()")
    op.execute(
        """
        DROP TRIGGER create_device_replay_quota_after_insert
        ON device
        """
    )
    op.execute("DROP FUNCTION create_device_replay_quota()")
    op.drop_index(
        "uq_enrollment_grant_one_issued_per_person",
        table_name="enrollment_grant",
    )
    op.drop_constraint(
        "fk_http_replay_person_device_quota",
        "http_replay",
        type_="foreignkey",
    )
    op.drop_index(
        "ix_http_replay_family_retention",
        table_name="http_replay",
    )
    op.drop_index(
        "ix_http_replay_person_device_retention",
        table_name="http_replay",
    )
    op.drop_table("device_replay_quota")
    op.drop_constraint(
        "uq_device_local_owner_id",
        "device",
        type_="unique",
    )
