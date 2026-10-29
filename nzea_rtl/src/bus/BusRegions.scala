package nzea_rtl.bus

import nzea_rtl.stat.StatRegion

/** The regions the bus components attribute their counters to. */
object BusRegions:
    val Bus = StatRegion("bus", "Off-chip bus residency")
