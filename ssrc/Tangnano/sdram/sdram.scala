package ssrc.Tangnano.sdram

import chisel3._
import chisel3.util._
import chisel3.experimental.Analog

class SDRAM_E extends Bundle{
    val O_sdram_clk = Output(Clock())
    val O_sdram_cke = Output(Bool())
    val O_sdram_cs_n = Output(Bool())
    val O_sdram_cas_n = Output(Bool())
    val O_sdram_ras_n = Output(Bool())
    val O_sdram_wen_n = Output(Bool())
    val O_sdram_dqm = Output(UInt(4.W))
    val O_sdram_addr = Output(UInt(11.W))
    val O_sdram_ba = Output(UInt(2.W))
    val IO_sdram_dq = Analog(32.W)
}

class SDRAM_I extends Bundle{
    val I_sdrc_rst_n = Input(Bool())
    val I_sdrc_clk = Input(Clock())
    val I_sdram_clk = Input(Clock())
    val I_sdrc_cmd_en = Input(Bool())
    val I_sdrc_cmd = Input(UInt(3.W))
    val I_sdrc_precharge_ctrl = Input(Bool())
    val I_sdram_power_down = Input(Bool())
    val I_sdram_selfrefresh = Input(Bool())
    val I_sdrc_addr = Input(UInt(21.W))
    val I_sdrc_dqm = Input(UInt(4.W))
    val I_sdrc_data = Input(UInt(32.W))
    val I_sdrc_data_len = Input(UInt(8.W))
    val O_sdrc_data = Output(UInt(32.W))
    val O_sdrc_init_done = Output(Bool())
    val O_sdrc_cmd_ack = Output(Bool())
}
