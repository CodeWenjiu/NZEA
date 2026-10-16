package nzea_fpga.boards.lxb_artix7

/** The board's clocking starts here and only here: the one clock the board itself carries is the oscillator on pin J19
  * ([[BoardXdc]] maps it), and its rate is the board's fact.
  *
  * What grids the design *needs* is not a board fact and is not stated here: each is asked for where it is used
  * ([[nzea_fpga.Clocks]]), and the wizard that serves them — one generator, so the grids that must keep a phase
  * relationship do — is derived from those requests. A board whose oscillator differs states its rate here and nothing
  * else changes.
  */
object BoardClock:

    /** The oscillator's rate, in MHz: the wizard's input, and what its input buffer's own DRC is about. */
    val oscillatorMHz = 50.0
