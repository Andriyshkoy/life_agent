package ru.andriyshkoy.lifeagent.wellbeing.domain

import java.util.Locale

object WellbeingPolicy {
    const val MAX_COMMENT_CODE_POINTS = 2_000
    const val MAX_LABEL_CODE_POINTS = 64
    const val MAX_SELECTED_DIMENSIONS = 64
    const val MAX_OPTIONS_PER_DIMENSION = 64

    fun normalizePayload(
        values: List<WellbeingValueSnapshot>,
        comment: String?,
    ): WellbeingPayload {
        if (values.isEmpty()) {
            throw InvalidWellbeingException("At least one wellbeing dimension is required")
        }
        if (values.size > MAX_SELECTED_DIMENSIONS) {
            throw InvalidWellbeingException(
                "Wellbeing contains more than $MAX_SELECTED_DIMENSIONS dimensions",
            )
        }
        val dimensions = mutableSetOf<java.util.UUID>()
        values.forEach { value ->
            if (!dimensions.add(value.dimensionId)) {
                throw InvalidWellbeingException("A wellbeing dimension is selected more than once")
            }
            if (value.dimensionVersion < 1 || value.optionVersion < 1) {
                throw InvalidWellbeingException("Wellbeing snapshot versions must be positive")
            }
            validateNormalizedLabel(value.dimensionLabel, "Dimension label")
            validateNormalizedLabel(value.optionLabel, "Option label")
        }
        return WellbeingPayload(
            values = values.toList(),
            comment = normalizeOptionalComment(comment),
        )
    }

    fun normalizeCatalogLabel(value: String, field: String): String {
        val normalized = value.trim()
        if (normalized.isBlank()) {
            throw InvalidWellbeingCatalogException("$field must contain a visible character")
        }
        if (codePointCount(normalized) > MAX_LABEL_CODE_POINTS) {
            throw InvalidWellbeingCatalogException(
                "$field exceeds $MAX_LABEL_CODE_POINTS Unicode code points",
            )
        }
        return normalized
    }

    fun validateOptionDrafts(options: List<WellbeingOptionDraft>, dimensionActive: Boolean) {
        if (options.isEmpty()) {
            throw InvalidWellbeingCatalogException("A wellbeing dimension needs an option")
        }
        if (options.size > MAX_OPTIONS_PER_DIMENSION) {
            throw InvalidWellbeingCatalogException(
                "A wellbeing dimension contains more than $MAX_OPTIONS_PER_DIMENSION options",
            )
        }
        val ids = mutableSetOf<java.util.UUID>()
        val activeLabels = mutableSetOf<String>()
        options.forEach { option ->
            if (!ids.add(option.optionId)) {
                throw InvalidWellbeingCatalogException("A wellbeing option ID is repeated")
            }
            val label = normalizeCatalogLabel(option.label, "Option label")
            if (option.active && !activeLabels.add(label.lowercase(Locale.ROOT))) {
                throw InvalidWellbeingCatalogException("Active wellbeing option labels must be unique")
            }
        }
        if (dimensionActive && options.none(WellbeingOptionDraft::active)) {
            throw InvalidWellbeingCatalogException(
                "An active wellbeing dimension needs an active option",
            )
        }
    }

    private fun normalizeOptionalComment(value: String?): String? {
        val normalized = value?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        if (codePointCount(normalized) > MAX_COMMENT_CODE_POINTS) {
            throw InvalidWellbeingException(
                "Wellbeing comment exceeds $MAX_COMMENT_CODE_POINTS Unicode code points",
            )
        }
        return normalized
    }

    private fun validateNormalizedLabel(value: String, field: String) {
        if (value.isBlank()) {
            throw InvalidWellbeingException("$field must contain a visible character")
        }
        if (value != value.trim()) {
            throw InvalidWellbeingException("$field must be normalized")
        }
        if (codePointCount(value) > MAX_LABEL_CODE_POINTS) {
            throw InvalidWellbeingException(
                "$field exceeds $MAX_LABEL_CODE_POINTS Unicode code points",
            )
        }
    }

    private fun codePointCount(value: String): Int = value.codePointCount(0, value.length)
}
