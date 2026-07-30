from __future__ import annotations

import argparse
import asyncio
import json
import sys
from collections.abc import Sequence
from uuid import UUID

import sqlalchemy as sa
from pydantic import ValidationError
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession

from life_agent_backend import models
from life_agent_backend.api_errors import canonical_server_time
from life_agent_backend.auth_crypto import RandomSource
from life_agent_backend.auth_service import (
    MAX_REPLAY_GC_BATCH_SIZE,
    MAX_REPLAY_RECONCILE_DEVICE_BATCH_SIZE,
    AuthService,
    ExistingEnrollmentGrantError,
    IssuedEnrollmentGrant,
    ReplayQuotaReconciliation,
)
from life_agent_backend.clock import Clock, SystemClock
from life_agent_backend.database import (
    ConfiguredKeyEpochs,
    DatabaseReadinessProbe,
    create_database_engine,
    create_session_factory,
)
from life_agent_backend.ids import IdGenerator, Uuid4Generator
from life_agent_backend.settings import Settings

_OWNER_PROVISION_LOCK_KEY = 7_411_736_157_251_527_091


class AdminCliError(RuntimeError):
    """A safe operator-facing failure with no secret-bearing context."""


async def issue_local_enrollment_code(
    settings: Settings,
    *,
    requested_person_id: UUID | None,
    replacement_allowed: bool,
    rotate_existing_code: bool,
    clock: Clock | None = None,
    id_generator: IdGenerator | None = None,
    random_source: RandomSource | None = None,
) -> IssuedEnrollmentGrant:
    resolved_clock = clock if clock is not None else SystemClock()
    resolved_ids = id_generator if id_generator is not None else Uuid4Generator()
    engine = create_database_engine(settings)
    try:
        readiness = DatabaseReadinessProbe(
            engine=engine,
            timeout_seconds=settings.readiness_timeout_seconds,
            configured_key_epochs=ConfiguredKeyEpochs.from_settings(settings),
        )
        if not await readiness.check():
            raise AdminCliError("database is not ready at the expected migration")
        session_factory = create_session_factory(engine)
        service = AuthService(
            settings=settings,
            session_factory=session_factory,
            clock=resolved_clock,
            id_generator=resolved_ids,
            random_source=random_source,
        )
        async with session_factory() as session, session.begin():
            await session.execute(
                sa.text("SELECT pg_advisory_xact_lock(:lock_key)"),
                {"lock_key": _OWNER_PROVISION_LOCK_KEY},
            )
            person_id = await _resolve_or_provision_person(
                session,
                requested_person_id=requested_person_id,
                id_generator=resolved_ids,
            )
            grant = await service.issue_enrollment_grant_in_session(
                session,
                person_id=person_id,
                replacement_allowed=replacement_allowed,
                rotate_existing=rotate_existing_code,
            )
        return grant
    finally:
        await engine.dispose()


async def purge_expired_replays(
    settings: Settings,
    *,
    batch_size: int,
    clock: Clock | None = None,
) -> int:
    resolved_clock = clock if clock is not None else SystemClock()
    engine = create_database_engine(settings)
    try:
        readiness = DatabaseReadinessProbe(
            engine=engine,
            timeout_seconds=settings.readiness_timeout_seconds,
            configured_key_epochs=ConfiguredKeyEpochs.from_settings(settings),
        )
        if not await readiness.check():
            raise AdminCliError("database is not ready at the expected migration")
        service = AuthService(
            settings=settings,
            session_factory=create_session_factory(engine),
            clock=resolved_clock,
            id_generator=Uuid4Generator(),
        )
        return await service.purge_expired_replays(batch_size=batch_size)
    finally:
        await engine.dispose()


async def reconcile_replay_quotas(
    settings: Settings,
    *,
    device_batch_size: int,
    repair: bool,
) -> ReplayQuotaReconciliation:
    """Scan one captured UUID key range in bounded database transactions.

    Devices inserted ahead of the in-process cursor may join until authorized
    enrollment churn quiesces; devices inserted behind it wait for the next run.
    """

    engine = create_database_engine(settings)
    try:
        readiness = DatabaseReadinessProbe(
            engine=engine,
            timeout_seconds=settings.readiness_timeout_seconds,
            configured_key_epochs=ConfiguredKeyEpochs.from_settings(settings),
        )
        if not await readiness.check():
            raise AdminCliError("database is not ready at the expected migration")
        service = AuthService(
            settings=settings,
            session_factory=create_session_factory(engine),
            clock=SystemClock(),
            id_generator=Uuid4Generator(),
        )
        checked = 0
        drifted = 0
        repaired = 0
        cursor = None
        while True:
            page = await service.reconcile_replay_quotas(
                device_batch_size=device_batch_size,
                repair=repair,
                _cursor=cursor,
            )
            checked += page.checked_devices
            drifted += page.drifted_devices
            repaired += page.repaired_devices
            if page.completed:
                return ReplayQuotaReconciliation(
                    completed=True,
                    checked_devices=checked,
                    drifted_devices=drifted,
                    repaired_devices=repaired,
                )
            if page._contended or page._next_cursor is None:
                return ReplayQuotaReconciliation(
                    completed=False,
                    checked_devices=checked,
                    drifted_devices=drifted,
                    repaired_devices=repaired,
                )
            cursor = page._next_cursor
    finally:
        await engine.dispose()


async def _resolve_or_provision_person(
    session: AsyncSession,
    *,
    requested_person_id: UUID | None,
    id_generator: IdGenerator,
) -> UUID:
    if requested_person_id is not None:
        existing = await session.scalar(
            sa.select(models.person.c.person_id)
            .where(models.person.c.person_id == requested_person_id)
            .with_for_update()
        )
        if isinstance(existing, UUID):
            return existing
        raise AdminCliError("the requested person does not exist")

    people = (
        await session.scalars(
            sa.select(models.person.c.person_id)
            .order_by(models.person.c.person_id)
            .limit(2)
            .with_for_update()
        )
    ).all()
    if len(people) == 1 and isinstance(people[0], UUID):
        return people[0]
    if len(people) > 1:
        raise AdminCliError("more than one person exists; pass --person-id explicitly")
    person_id = id_generator.new_id()
    await session.execute(
        sa.insert(models.person).values(
            person_id=person_id,
            subject_id=id_generator.new_id(),
        )
    )
    return person_id


def _canonical_uuid(value: str) -> UUID:
    try:
        parsed = UUID(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("expected a canonical lowercase UUID") from error
    if str(parsed) != value:
        raise argparse.ArgumentTypeError("expected a canonical lowercase UUID")
    return parsed


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="life-agent-admin")
    commands = parser.add_subparsers(dest="command", required=True)
    issue = commands.add_parser(
        "issue-enrollment-code",
        help="provision the local owner if needed and issue one code",
    )
    issue.add_argument("--person-id", type=_canonical_uuid)
    issue.add_argument(
        "--allow-device-replacement",
        action="store_true",
    )
    issue.add_argument(
        "--rotate-existing-code",
        action="store_true",
    )
    issue.add_argument(
        "--allow-non-tty-output",
        action="store_true",
        help="explicitly allow the one-time code on redirected stdout",
    )
    purge = commands.add_parser(
        "purge-expired-replays",
        help="delete one bounded batch of expired encrypted responses",
    )
    purge.add_argument(
        "--batch-size",
        type=int,
        choices=range(1, MAX_REPLAY_GC_BATCH_SIZE + 1),
        default=512,
        metavar=f"1..{MAX_REPLAY_GC_BATCH_SIZE}",
    )
    reconcile = commands.add_parser(
        "reconcile-replay-quotas",
        help="check or repair an aggregate-only key-range scan in bounded transactions",
    )
    reconcile.add_argument(
        "--device-batch-size",
        type=int,
        choices=range(1, MAX_REPLAY_RECONCILE_DEVICE_BATCH_SIZE + 1),
        default=64,
        metavar=f"1..{MAX_REPLAY_RECONCILE_DEVICE_BATCH_SIZE}",
        help="maximum devices checked per database transaction",
    )
    reconcile.add_argument("--repair", action="store_true")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    if (
        arguments.command == "issue-enrollment-code"
        and not sys.stdout.isatty()
        and not arguments.allow_non_tty_output
    ):
        print(
            "life-agent-admin: refusing to emit a code to non-TTY stdout; "
            "pass --allow-non-tty-output explicitly",
            file=sys.stderr,
        )
        return 1
    try:
        settings = Settings.from_environment()
        if arguments.command == "purge-expired-replays":
            deleted_count = asyncio.run(
                purge_expired_replays(
                    settings,
                    batch_size=arguments.batch_size,
                )
            )
            print(deleted_count)
            return 0
        if arguments.command == "reconcile-replay-quotas":
            reconciliation = asyncio.run(
                reconcile_replay_quotas(
                    settings,
                    device_batch_size=arguments.device_batch_size,
                    repair=arguments.repair,
                )
            )
            print(
                json.dumps(
                    {
                        "checked_devices": reconciliation.checked_devices,
                        "completed": reconciliation.completed,
                        "drifted_devices": reconciliation.drifted_devices,
                        "repaired_devices": reconciliation.repaired_devices,
                    },
                    separators=(",", ":"),
                    sort_keys=True,
                )
            )
            if not reconciliation.completed:
                return 1
            if reconciliation.drifted_devices > reconciliation.repaired_devices:
                return 2
            return 0
        grant = asyncio.run(
            issue_local_enrollment_code(
                settings,
                requested_person_id=arguments.person_id,
                replacement_allowed=arguments.allow_device_replacement,
                rotate_existing_code=arguments.rotate_existing_code,
            )
        )
    except ExistingEnrollmentGrantError:
        print(
            "life-agent-admin: an enrollment code is already active; "
            "pass --rotate-existing-code to replace it",
            file=sys.stderr,
        )
        return 1
    except AdminCliError as error:
        print(f"life-agent-admin: {error}", file=sys.stderr)
        return 1
    except (SQLAlchemyError, ValidationError, ValueError, RuntimeError):
        print(
            "life-agent-admin: operation failed; verify local configuration",
            file=sys.stderr,
        )
        return 1

    print(grant.code, flush=True)
    print(
        f"Enrollment code expires at {canonical_server_time(grant.expires_at)}.",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
