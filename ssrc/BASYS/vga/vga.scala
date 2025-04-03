package BASYS

import chisel3._
import chisel3.util._

class VGASyncIO extends Bundle{
	val hsync = Output(Bool())
	val vsync = Output(Bool())
}

class VGACtrlIO extends Bundle{
	val valid = Output(Bool())
	val xaddr, yaddr = Output(UInt(10.W))
}

object sync_config {
	val sim = true
}

object vga_param {
	def vga_width = 640
	def vga_height = 480

	def h_front_porch = 96
	def h_active = 144
	def h_backporch = h_active + vga_width
	def h_total = 800

	def v_front_porch = 2
	def v_active = 35
	def v_backporch = v_active + vga_height
	def v_total = 525
}

class vga_sync extends Module{
	val io = IO(new VGASyncIO)
	val Ctrl = IO(new VGACtrlIO)

    val clock_devider = Module(new clock_devider(4))
    clock_devider.io.clk_in := clock
    clock_devider.io.reset := reset

	val R_clock = Wire(Bool())

	if(sync_config.sim){
		R_clock := clock.asBool
	}else{
		R_clock := clock_devider.io.clk_out
	}

	withClockAndReset(R_clock.asClock, reset){

		val x_cnt = RegInit(1.U(10.W))
		val y_cnt = RegInit(1.U(10.W))

		when(x_cnt >= vga_param.h_total.U){
			x_cnt := 1.U
		}.otherwise{
			x_cnt := x_cnt + 1.U
		}

		when(y_cnt >= vga_param.v_total.U){
			y_cnt := 1.U
		}.elsewhen(x_cnt >= vga_param.h_total.U){
			y_cnt := y_cnt + 1.U
		}

		io.hsync := (x_cnt > vga_param.h_front_porch.U)
		io.vsync := (y_cnt > vga_param.v_front_porch.U)

		val h_valid = Wire(Bool())
		val v_valid = Wire(Bool())
		h_valid := (x_cnt > vga_param.h_active.U) & (x_cnt <= vga_param.h_backporch.U)
		v_valid := (y_cnt > vga_param.v_active.U) & (y_cnt <= vga_param.v_backporch.U)
		Ctrl.valid := h_valid & v_valid

		Ctrl.xaddr := x_cnt - vga_param.h_active.U - 1.U
		Ctrl.yaddr := y_cnt - vga_param.v_active.U - 1.U
	}
}
