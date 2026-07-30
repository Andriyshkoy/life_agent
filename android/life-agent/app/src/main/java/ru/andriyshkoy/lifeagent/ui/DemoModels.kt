package ru.andriyshkoy.lifeagent.ui

enum class DemoRoute {
    Add,
    Catalogs,
    Settings,
    CaptureFood,
    CaptureWellbeing,
    CaptureMedication,
    CaptureNote,
    CatalogFood,
    CatalogWellbeing,
    CatalogMedication,
    SyncSetup,
    HealthConnect,
    TimeZone,
    Diagnostics,
    Privacy,
}

enum class TopLevelDestination(val label: String) {
    Add("Добавить"),
    Catalogs("Справочники"),
    Settings("Настройки"),
}

fun DemoRoute.topLevelDestination(): TopLevelDestination? = when (this) {
    DemoRoute.Add -> TopLevelDestination.Add
    DemoRoute.Catalogs -> TopLevelDestination.Catalogs
    DemoRoute.Settings -> TopLevelDestination.Settings
    else -> null
}

fun DemoRoute.isCaptureRoute(): Boolean = when (this) {
    DemoRoute.CaptureFood,
    DemoRoute.CaptureWellbeing,
    DemoRoute.CaptureMedication,
    DemoRoute.CaptureNote,
    -> true

    else -> false
}

fun DemoRoute.backTarget(): DemoRoute = when (this) {
    DemoRoute.CaptureFood,
    DemoRoute.CaptureWellbeing,
    DemoRoute.CaptureMedication,
    DemoRoute.CaptureNote,
    -> DemoRoute.Add

    DemoRoute.CatalogFood,
    DemoRoute.CatalogWellbeing,
    DemoRoute.CatalogMedication,
    -> DemoRoute.Catalogs

    DemoRoute.SyncSetup,
    DemoRoute.HealthConnect,
    DemoRoute.TimeZone,
    DemoRoute.Diagnostics,
    DemoRoute.Privacy,
    -> DemoRoute.Settings

    DemoRoute.Add,
    DemoRoute.Catalogs,
    DemoRoute.Settings,
    -> DemoRoute.Add
}

enum class MealType(val label: String) {
    Breakfast("Завтрак"),
    Lunch("Обед"),
    Dinner("Ужин"),
    Snack("Перекус"),
}

enum class WellbeingOption(val label: String, val emoji: String) {
    Great("Отлично", "●"),
    Calm("Спокойно", "●"),
    Tired("Устал", "●"),
    Anxious("Тревожно", "●"),
    Bad("Плохо", "●"),
}

enum class CatalogKind(
    val title: String,
    val subtitle: String,
    val countLabel: String,
) {
    Food(
        title = "Питание",
        subtitle = "Продукты, блюда и обычные порции",
        countLabel = "24 позиции",
    ),
    Wellbeing(
        title = "Самочувствие",
        subtitle = "Состояния и личные шкалы",
        countLabel = "8 вариантов",
    ),
    Medication(
        title = "Лекарства и БАДы",
        subtitle = "Список и варианты дозировки",
        countLabel = "6 позиций",
    ),
}

data class DemoCatalogItem(
    val title: String,
    val subtitle: String,
    val badge: String? = null,
    val active: Boolean = true,
)

object DemoContent {
    val foodItems = listOf(
        DemoCatalogItem("Овсянка с ягодами", "Готовое блюдо · 350 г", "Любимое"),
        DemoCatalogItem("Греческий йогурт", "Продукт · 100 г"),
        DemoCatalogItem("Омлет", "Готовое блюдо · 1 порция", "Недавнее"),
        DemoCatalogItem("Банан", "Продукт · 1 шт."),
    )

    val wellbeingItems = listOf(
        DemoCatalogItem("Спокойно", "Базовое состояние"),
        DemoCatalogItem("Устал", "Базовое состояние"),
        DemoCatalogItem("Тревожно", "Базовое состояние"),
        DemoCatalogItem("Собранно", "Личный вариант"),
    )

    val medicationItems = listOf(
        DemoCatalogItem("Витамин D", "Добавка · 1 капсула", "Утро"),
        DemoCatalogItem("Магний", "Добавка · 200 мг", "Вечер"),
        DemoCatalogItem("Омега-3", "Добавка · 2 капсулы"),
        DemoCatalogItem("Архивный пример", "Не показывается при добавлении", active = false),
    )
}
