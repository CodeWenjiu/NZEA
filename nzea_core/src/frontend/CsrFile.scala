package nzea_core.frontend

import chisel3._
import chisel3.util.{Mux1H, Valid, switch, is}

/** CSR write from SYSU completion (integer execution cluster). */
class CsrWriteBundle extends Bundle {
  val csr_type = CsrType()
  val data     = UInt(32.W)
}

/** Machine-mode CSR register file; writes from SYSU; combinational read by 12-bit addr (e.g. IQ issue stage). */
class CsrFile extends Module {
  val io = IO(new Bundle {
    val csr_write = Input(Valid(new CsrWriteBundle))
    val read_addr = Input(UInt(12.W))
    val read_data = Output(UInt(32.W))
  })

  val csr_mstatus  = RegInit(0x1800.U(32.W))
  val csr_mtvec    = RegInit(0.U(32.W))
  val csr_mepc     = RegInit(0.U(32.W))
  val csr_mcause   = RegInit(0.U(32.W))
  val csr_mscratch = RegInit(0.U(32.W))

  when(io.csr_write.valid && io.csr_write.bits.csr_type =/= CsrType.None) {
    switch(io.csr_write.bits.csr_type) {
      is(CsrType.Mstatus)  { csr_mstatus  := io.csr_write.bits.data }
      is(CsrType.Mtvec)    { csr_mtvec    := io.csr_write.bits.data }
      is(CsrType.Mepc)     { csr_mepc     := io.csr_write.bits.data }
      is(CsrType.Mcause)   { csr_mcause   := io.csr_write.bits.data }
      is(CsrType.Mscratch) { csr_mscratch := io.csr_write.bits.data }
    }
  }

  private def readCsr(csrType: CsrType.Type): UInt =
    Mux(csrType === CsrType.None, 0.U(32.W),
      Mux1H(
        Seq(
          csrType === CsrType.Mstatus,
          csrType === CsrType.Mtvec,
          csrType === CsrType.Mepc,
          csrType === CsrType.Mcause,
          csrType === CsrType.Mscratch
        ),
        Seq(csr_mstatus, csr_mtvec, csr_mepc, csr_mcause, csr_mscratch)
      ))

  io.read_data := readCsr(CsrType.fromAddr(io.read_addr))
}
