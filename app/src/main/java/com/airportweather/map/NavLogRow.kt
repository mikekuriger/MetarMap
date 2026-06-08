package com.airportweather.map

/**
 * One renderable row of the nav log. Each row is exactly one [FlightLeg]; the
 * climb-and-descent splitting happens in [com.airportweather.map.utils.FlightPlanUtils.generateLegsWithPhases]
 * which inserts synthetic TOC and TOD waypoints. This wrapper just adds the
 * running fuel-remaining figure so the adapter doesn't have to recompute it.
 */
data class NavLogRow(
    val leg: FlightLeg,
    /** Fuel on board at the end of this leg, after [FlightLeg.fuelUsed] is burned. */
    val fuelRemaining: Double,
) {
    companion object {
        /** Builds nav-log rows by running [FlightPlan.startingFuel] forward,
         *  subtracting each leg's fuel-used along the way. */
        fun build(plan: FlightPlan): List<NavLogRow> {
            var remaining = plan.startingFuel
            return plan.legs.map { leg ->
                remaining -= leg.fuelUsed
                NavLogRow(leg, remaining)
            }
        }
    }
}
