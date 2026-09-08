package nzea_config

/** Platform-specific tile configuration.
  *
  * The sealed hierarchy keeps each platform's knobs inside its own variant:
  * fpga-only parameters (e.g. the external-memory bus count) never leak into
  * the shared [[nzea_config.tile.TileConfig]] or into yosys construction sites.
  */
sealed abstract class PlatformConfig(val synthPlatform: SynthPlatform) extends Product with Serializable

object PlatformConfig {

  /** Yosys platform: fixed device set (ram/uart16550/finisher/clint) exposed
    * as `yosys_devices`; no board-level knobs.
    */
  case object Yosys extends PlatformConfig(SynthPlatform.Yosys)

  /** Fpga platform: the board attaches external memory buses at the tile
    * boundary.
    *
    * @param extMemBuses external-memory bus count (bus 0 = boot RAM, extra
    *                    buses are additional memory regions); sourced from
    *                    [[FpgaBoard.extMemBuses]].
    */
  case class Fpga(extMemBuses: Int) extends PlatformConfig(SynthPlatform.Fpga)
}
