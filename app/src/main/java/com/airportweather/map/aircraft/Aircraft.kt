package com.airportweather.map.aircraft

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A single airframe the pilot owns or rents. Most fields live at this level;
 * the multi-profile structure under [profiles] only holds performance numbers
 * (climb/cruise/descent speeds + fuel burns + rates) since those are what
 * legitimately change with power setting.
 *
 * Persisted via [AircraftRepository] as a JSON file under filesDir.
 */
@Serializable
data class Aircraft(
    val id: String = UUID.randomUUID().toString(),
    val general: AircraftGeneral = AircraftGeneral(),
    val profiles: List<PerformanceProfile> = listOf(PerformanceProfile()),
    /** Points at one of [profiles] by id; falls back to the first profile if null. */
    val defaultProfileId: String? = null,
    val glide: GlidePerformance = GlidePerformance(),
    val altitudes: Altitudes = Altitudes(),
    val weights: Weights = Weights(),
    /** Placeholder for full weight-and-balance worksheet — filled in later. */
    val weightAndBalance: WeightAndBalance = WeightAndBalance(),
    val fuel: Fuel = Fuel(),
) {
    /** Profile flagged as default, or the first one if no flag is set. */
    val defaultProfile: PerformanceProfile?
        get() = profiles.firstOrNull { it.id == defaultProfileId } ?: profiles.firstOrNull()
}

@Serializable
data class AircraftGeneral(
    val tailNumber: String = "",
    val serialNumber: String = "",
    /** Free text now; an N-number autofill will populate this later. */
    val aircraftType: String = "",
    val primaryColor: String = "",
    val secondaryColor: String = "",
    val category: Category = Category.AIRPLANE,
    /** Home airport identifier (e.g. "KWHP"). */
    val homeAirport: String = "",
    val airspeedUnits: AirspeedUnits = AirspeedUnits.KNOTS,
)

/**
 * Power-setting-specific performance. A typical PA-28 might have:
 *   - "65% Cruise": climb 90 / cruise 110 / descent 120 KTAS, 8/9/7 GPH
 *   - "75% Cruise": climb 90 / cruise 120 / descent 120 KTAS, 8/11/7 GPH
 *   - "Long Range":  climb 90 / cruise 100 / descent 120 KTAS, 8/7/6 GPH
 */
@Serializable
data class PerformanceProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Default",
    val climbTas: Int = 0,
    val climbGph: Double = 0.0,
    /** Feet per minute. */
    val climbRate: Int = 0,
    val cruiseTas: Int = 0,
    val cruiseGph: Double = 0.0,
    val descentTas: Int = 0,
    val descentGph: Double = 0.0,
    /** Feet per minute. */
    val descentRate: Int = 0,
)

@Serializable
data class GlidePerformance(
    /** In the aircraft's airspeed units. */
    val bestGlideSpeed: Int = 0,
    /** Stored as a single number — e.g. 9:1 → 9.0. */
    val bestGlideRatio: Double = 0.0,
)

@Serializable
data class Altitudes(
    /** Preferred VFR cruise altitude in feet MSL. */
    val defaultCruiseAltitude: Int = 0,
    /** Service ceiling in feet MSL. */
    val maxCeiling: Int = 0,
)

@Serializable
data class Weights(
    val units: WeightUnits = WeightUnits.POUNDS,
    val basicEmptyWeight: Double = 0.0,
    val maxZeroFuelWeight: Double = 0.0,
    val maxRampWeight: Double = 0.0,
    val maxTakeoffWeight: Double = 0.0,
    val maxLandingWeight: Double = 0.0,
)

/** Placeholder. Real W&B (CG envelope, station moments) gets added later. */
@Serializable
data class WeightAndBalance(
    val notes: String = "",
)

@Serializable
data class Fuel(
    val type: FuelType = FuelType.AVGAS_100LL,
    val units: FuelUnits = FuelUnits.GALLONS_US,
    /** Total usable fuel capacity in [units]. Used by the nav log to cap a
     *  pilot-entered starting fuel quantity, and by W&B (later) to validate
     *  CG envelope. v2 will split this into per-tank capacities with arms. */
    val totalUsableCapacity: Double = 0.0,
    /** Fuel consumed before reaching cruise altitude. Used by the planner to
     *  pad total fuel above what cruise-only math returns. */
    val startTaxiTakeoffFuel: Double = 0.0,
    /** Dollar/hour for non-fuel operating costs (oil, reserves, maintenance). */
    val dryOpCostPerHr: Double = 0.0,
)

@Serializable
enum class Category(val label: String) {
    AIRPLANE("Airplane"),
    HELICOPTER("Helicopter"),
    GLIDER("Glider"),
    GYROPLANE("Gyroplane"),
    POWERED_LIFT("Powered-lift"),
    LIGHTER_THAN_AIR("Lighter-than-air"),
    ULTRALIGHT("Ultralight"),
    OTHER("Other"),
}

@Serializable
enum class AirspeedUnits(val label: String) {
    KNOTS("kt"),
    MPH("mph"),
}

@Serializable
enum class WeightUnits(val label: String) {
    POUNDS("lb"),
    KILOGRAMS("kg"),
}

@Serializable
enum class FuelType(val label: String) {
    AVGAS_100LL("100LL"),
    AVGAS_100("Avgas 100"),
    JET_A("Jet A"),
    JET_A1("Jet A-1"),
    MOGAS("Mogas"),
    UL94("UL94"),
    DIESEL("Diesel"),
    ELECTRIC("Electric"),
}

@Serializable
enum class FuelUnits(val label: String) {
    GALLONS_US("gal"),
    LITERS("L"),
    POUNDS("lb"),
    KILOGRAMS("kg"),
}
