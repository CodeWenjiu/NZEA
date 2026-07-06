package nzea_tile.platform.yosys

import chisel3._
import nzea_rtl.FabricBusRW

/** Simulation latency helpers — placeholder for future random-stall injection.
  *
  * Currently passthrough: `sim=true` creates DPI bridges; `sim=false` connects directly to hardware ports. No delay is
  * injected yet.
  */
object AccessLatency {

  def connect(
      sim: Boolean,
      cpuHz: Int,
      devHz: Option[Double],
      fabricPort: FabricBusRW,
      hwPort: FabricBusRW,
      simPortWidth: Int,
      simPortData: Int,
      simPortUser: Int,
      simPortId: Int
  ): Unit = {
    if (sim) {
      val dev = Module(new SimDeviceDpiBridge(simPortWidth, simPortData, simPortUser, simPortId))
      fabricPort <> dev.io.bus
    } else {
      fabricPort <> hwPort
    }
  }

}
