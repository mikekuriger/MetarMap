package com.airportweather.map.aircraft

/**
 * Hand-curated starter templates for the most common GA aircraft. Each
 * entry produces a fully-formed [Aircraft] the user can save and then
 * edit (tail number, weights, etc.) for their specific airframe.
 *
 * Numbers are POH-derived approximations for a representative variant of
 * each type — good enough for VFR planning, but every aircraft is
 * different (engine STCs, wheel pants, tip tanks, age) and pilots are
 * expected to tune for their own bird. Treat these as a sane starting
 * point, not gospel.
 *
 * Climb rates are sea-level; cruise TAS/GPH represent ~75% power at a
 * mid-altitude cruise (8,000–10,000 ft) unless the aircraft is normally
 * flown lower. Descent values assume a typical cruise-descent profile
 * (~500 fpm at light cruise power).
 *
 * If a template's numbers feel off, fix them here — the repo is the
 * single source of truth and the change propagates to every new
 * "from template" aircraft going forward.
 */
object AircraftTemplates {

    /** A picker entry: display name plus a function that builds the [Aircraft]. */
    data class Template(
        val displayName: String,
        val build: () -> Aircraft,
    )

    /**
     * Public list, ordered roughly by training-popularity then size. The
     * dialog renders these top-to-bottom in this order.
     */
    val all: List<Template> = listOf(
        Template("Cessna 150") { c150() },
        Template("Cessna 152") { c152() },
        Template("Cessna 172 (older)") { c172Old() },
        Template("Cessna 172 SP") { c172SP() },
        Template("Cessna 182 Skylane") { c182() },
        Template("Cessna 206 Stationair") { c206() },
        Template("Piper Cherokee 140 (PA-28-140)") { pa28_140() },
        Template("Piper Warrior III") { pa28_161() },
        Template("Piper Archer III") { pa28_181() },
        Template("Piper Arrow IV (PA-28R-201)") { pa28r_201() },
        Template("Cirrus SR20") { sr20() },
        Template("Cirrus SR22") { sr22() },
        Template("Beechcraft Bonanza A36") { bonanzaA36() },
        Template("Beechcraft Bonanza V35") { bonanzaV35() },
        Template("Diamond DA20") { da20() },
        Template("Diamond DA40") { da40() },
        Template("Mooney M20J 201") { m20j() },
        Template("Grumman AA-5B Tiger") { aa5b() },
        Template("Van's RV-7") { rv7() },
    )

    // -- builders ---------------------------------------------------------

    private fun c150() = build(
        type = "Cessna 150",
        cruiseAlt = 5500, ceiling = 14000, fuel = 26.0,
        vbg = 60, glideRatio = 9.0,
        climb = Triple(65, 6.0, 670),
        cruiseT = 100, cruiseG = 5.5,
        descent = Triple(100, 4.0, 500),
    )

    private fun c152() = build(
        type = "Cessna 152",
        cruiseAlt = 5500, ceiling = 14700, fuel = 26.0,
        vbg = 60, glideRatio = 9.0,
        climb = Triple(67, 6.0, 715),
        cruiseT = 107, cruiseG = 6.0,
        descent = Triple(107, 4.5, 500),
    )

    private fun c172Old() = build(
        type = "Cessna 172 (N/P/Q)",
        cruiseAlt = 6500, ceiling = 13500, fuel = 38.0,
        vbg = 65, glideRatio = 9.0,
        climb = Triple(76, 8.0, 645),
        cruiseT = 122, cruiseG = 8.5,
        descent = Triple(122, 6.0, 500),
    )

    private fun c172SP() = build(
        type = "Cessna 172 SP",
        cruiseAlt = 6500, ceiling = 14000, fuel = 53.0,
        vbg = 68, glideRatio = 9.0,
        climb = Triple(78, 10.0, 730),
        cruiseT = 124, cruiseG = 9.0,
        descent = Triple(124, 6.5, 500),
    )

    private fun c182() = build(
        type = "Cessna 182 Skylane",
        cruiseAlt = 8500, ceiling = 18100, fuel = 88.0,
        vbg = 75, glideRatio = 9.0,
        climb = Triple(80, 13.0, 924),
        cruiseT = 142, cruiseG = 13.0,
        descent = Triple(142, 8.5, 500),
    )

    private fun c206() = build(
        type = "Cessna 206 Stationair",
        cruiseAlt = 8500, ceiling = 14800, fuel = 87.0,
        vbg = 80, glideRatio = 9.0,
        climb = Triple(80, 14.0, 920),
        cruiseT = 148, cruiseG = 14.0,
        descent = Triple(148, 9.0, 500),
    )

    private fun pa28_140() = build(
        type = "Piper Cherokee 140",
        cruiseAlt = 6500, ceiling = 14300, fuel = 50.0,
        vbg = 70, glideRatio = 9.0,
        climb = Triple(75, 8.0, 660),
        cruiseT = 108, cruiseG = 8.5,
        descent = Triple(108, 6.0, 500),
    )

    private fun pa28_161() = build(
        type = "Piper Warrior III (PA-28-161)",
        cruiseAlt = 6500, ceiling = 11000, fuel = 48.0,
        vbg = 75, glideRatio = 9.0,
        climb = Triple(79, 9.0, 644),
        cruiseT = 119, cruiseG = 9.5,
        descent = Triple(119, 7.0, 500),
    )

    private fun pa28_181() = build(
        type = "Piper Archer III (PA-28-181)",
        cruiseAlt = 6500, ceiling = 13650, fuel = 48.0,
        vbg = 76, glideRatio = 8.0,
        climb = Triple(79, 10.0, 740),
        cruiseT = 124, cruiseG = 10.0,
        descent = Triple(124, 7.0, 500),
    )

    private fun pa28r_201() = build(
        type = "Piper Arrow IV (PA-28R-201)",
        cruiseAlt = 8500, ceiling = 16200, fuel = 72.0,
        vbg = 85, glideRatio = 8.0,
        climb = Triple(90, 13.0, 831),
        cruiseT = 137, cruiseG = 11.0,
        descent = Triple(137, 8.0, 500),
    )

    private fun sr20() = build(
        type = "Cirrus SR20",
        cruiseAlt = 8500, ceiling = 17500, fuel = 56.0,
        vbg = 87, glideRatio = 9.0,
        climb = Triple(90, 14.0, 781),
        cruiseT = 155, cruiseG = 12.5,
        descent = Triple(155, 9.0, 500),
    )

    private fun sr22() = build(
        type = "Cirrus SR22",
        cruiseAlt = 9500, ceiling = 17500, fuel = 92.0,
        vbg = 88, glideRatio = 9.0,
        climb = Triple(100, 22.0, 1270),
        cruiseT = 183, cruiseG = 17.0,
        descent = Triple(183, 12.0, 500),
    )

    private fun bonanzaA36() = build(
        type = "Beechcraft Bonanza A36",
        cruiseAlt = 9500, ceiling = 18500, fuel = 74.0,
        vbg = 105, glideRatio = 9.0,
        climb = Triple(105, 19.0, 1066),
        cruiseT = 173, cruiseG = 16.0,
        descent = Triple(173, 11.0, 500),
    )

    private fun bonanzaV35() = build(
        type = "Beechcraft Bonanza V35",
        cruiseAlt = 9500, ceiling = 18500, fuel = 74.0,
        vbg = 105, glideRatio = 9.0,
        climb = Triple(100, 17.0, 1136),
        cruiseT = 169, cruiseG = 13.0,
        descent = Triple(169, 9.0, 500),
    )

    private fun da20() = build(
        type = "Diamond DA20",
        cruiseAlt = 6500, ceiling = 13100, fuel = 24.0,
        vbg = 73, glideRatio = 10.0,
        climb = Triple(70, 6.0, 1000),
        cruiseT = 138, cruiseG = 5.5,
        descent = Triple(138, 4.0, 500),
    )

    private fun da40() = build(
        type = "Diamond DA40",
        cruiseAlt = 7500, ceiling = 16400, fuel = 41.0,
        vbg = 76, glideRatio = 10.0,
        climb = Triple(78, 9.0, 1120),
        cruiseT = 144, cruiseG = 8.5,
        descent = Triple(144, 6.0, 500),
    )

    private fun m20j() = build(
        type = "Mooney M20J 201",
        cruiseAlt = 8500, ceiling = 18800, fuel = 64.0,
        vbg = 91, glideRatio = 10.0,
        climb = Triple(90, 12.0, 1030),
        cruiseT = 162, cruiseG = 9.0,
        descent = Triple(162, 7.0, 500),
    )

    private fun aa5b() = build(
        type = "Grumman AA-5B Tiger",
        cruiseAlt = 7500, ceiling = 13800, fuel = 51.0,
        vbg = 80, glideRatio = 9.0,
        climb = Triple(90, 10.0, 850),
        cruiseT = 139, cruiseG = 9.5,
        descent = Triple(139, 6.0, 500),
    )

    private fun rv7() = build(
        type = "Van's RV-7",
        cruiseAlt = 8500, ceiling = 21000, fuel = 42.0,
        vbg = 80, glideRatio = 9.0,
        climb = Triple(100, 12.0, 1500),
        cruiseT = 175, cruiseG = 8.5,
        descent = Triple(175, 6.0, 500),
    )

    // -- shared builder ---------------------------------------------------

    /**
     * Common assembly. Tail/serial/etc. are left blank so the user fills
     * them in for their specific airframe. The single profile is named
     * "75% Cruise" since that's what the numbers represent.
     */
    private fun build(
        type: String,
        cruiseAlt: Int,
        ceiling: Int,
        fuel: Double,
        vbg: Int,
        glideRatio: Double,
        climb: Triple<Int, Double, Int>,
        cruiseT: Int,
        cruiseG: Double,
        descent: Triple<Int, Double, Int>,
    ): Aircraft {
        val (climbTas, climbGph, climbRate) = climb
        val (descentTas, descentGph, descentRate) = descent
        val profile = PerformanceProfile(
            name = "75% Cruise",
            climbTas = climbTas,
            climbGph = climbGph,
            climbRate = climbRate,
            cruiseTas = cruiseT,
            cruiseGph = cruiseG,
            descentTas = descentTas,
            descentGph = descentGph,
            descentRate = descentRate,
        )
        return Aircraft(
            general = AircraftGeneral(aircraftType = type),
            profiles = listOf(profile),
            defaultProfileId = profile.id,
            glide = GlidePerformance(bestGlideSpeed = vbg, bestGlideRatio = glideRatio),
            altitudes = Altitudes(defaultCruiseAltitude = cruiseAlt, maxCeiling = ceiling),
            fuel = Fuel(totalUsableCapacity = fuel),
        )
    }
}
