package nzea_tile.platform.yosys

import chisel3._
import nzea_rtl.{FabricBusRW, FabricBusRandomStall}

/** Simulation latency helpers. */
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
    val devPort = if (sim) {
      val dev = Module(new SimDeviceDpiBridge(simPortWidth, simPortData, simPortUser, simPortId))
      dev.io.bus
    } else {
      hwPort
    }

    devHz match {
      case Some(hz) =>
        val pipe = Module(
          new FabricBusRandomStall(simPortWidth, simPortData, simPortUser, simPortId, (cpuHz.toDouble / hz).max(1.0))
        )
        pipe.io.in <> fabricPort
        pipe.io.out <> devPort
      case _ =>
        fabricPort <> devPort
    }
  }

}
