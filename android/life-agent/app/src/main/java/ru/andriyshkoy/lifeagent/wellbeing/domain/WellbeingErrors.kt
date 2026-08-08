package ru.andriyshkoy.lifeagent.wellbeing.domain

import java.util.UUID

sealed class WellbeingMutationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class InvalidWellbeingException(
    message: String,
) : WellbeingMutationException(message)

class WellbeingNotFoundException(
    eventId: UUID,
) : WellbeingMutationException("Wellbeing event $eventId does not exist")

class StaleWellbeingRevisionException(
    eventId: UUID,
    expected: UUID,
    actual: UUID,
) : WellbeingMutationException(
    "Wellbeing event $eventId changed: expected revision $expected, current revision is $actual",
)

class RetractedWellbeingCorrectionException(
    eventId: UUID,
) : WellbeingMutationException("Retracted wellbeing event $eventId cannot be corrected")

class WellbeingIdempotencyConflictException(
    operationId: UUID,
) : WellbeingMutationException(
    "Operation $operationId was already committed with different immutable content",
)

class WellbeingIdentityCollisionException(
    message: String,
    cause: Throwable? = null,
) : WellbeingMutationException(message, cause)

class CorruptLocalWellbeingException(
    message: String,
    cause: Throwable? = null,
) : WellbeingMutationException(message, cause)

sealed class WellbeingCatalogException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class InvalidWellbeingCatalogException(
    message: String,
) : WellbeingCatalogException(message)

class WellbeingDimensionNotFoundException(
    dimensionId: UUID,
) : WellbeingCatalogException("Wellbeing dimension $dimensionId does not exist")

class StaleWellbeingCatalogVersionException(
    dimensionId: UUID,
    expected: UUID,
    actual: UUID,
) : WellbeingCatalogException(
    "Wellbeing dimension $dimensionId changed: expected version $expected, current version is $actual",
)

class WellbeingCatalogIdentityCollisionException(
    message: String,
) : WellbeingCatalogException(message)

class CorruptWellbeingCatalogException(
    message: String,
    cause: Throwable? = null,
) : WellbeingCatalogException(message, cause)
