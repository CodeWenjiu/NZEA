package utility

import chisel3._
import chisel3.util._

class RotateShifter(width: Int, init: Int) extends Module {
    val io = IO(new Bundle {
        val shamt = Input(UInt(log2Ceil(width).W))
        val left = Input(Bool())
        val shift = Input(Bool())
        val setData = Input(UInt(width.W))
        val setEnable = Input(Bool())
        val data = Output(UInt(width.W))
    })
    
    val dataReg = RegInit(init.U(width.W))
    
    when(io.setEnable) {
        dataReg := io.setData
    }.elsewhen(io.shift) {
        val leftRotate = (dataReg << io.shamt) | (dataReg >> (width.U - io.shamt))
        val rightRotate = (dataReg >> io.shamt) | (dataReg << (width.U - io.shamt))
        dataReg := Mux(io.left, leftRotate, rightRotate)
    }
    
    io.data := dataReg
    
    def shift(shamt: UInt, left: Bool = true.B): Unit = {
        io.shamt := shamt
        io.left := left
        io.shift := true.B
        io.setEnable := false.B
        io.setData := DontCare
    }
    
    def setData(data: UInt): Unit = {
        io.setData := data
        io.setEnable := true.B
        io.shift := false.B
        io.shamt := DontCare
        io.left := DontCare
    }
    
    def getData(): UInt = io.data

    def last(): Bool = {
        io.data(io.data.getWidth - 1)
    }
}

object RotateShifter {
    def apply(width: Int, init: Int): RotateShifter = {
        val module = Module(new RotateShifter(width, init))

        module.io.setEnable := false.B
        module.io.setData := DontCare
        module.io.shift := false.B
        module.io.shamt := DontCare
        module.io.left := DontCare
        module.io.data := DontCare // Ensure the output is initialized

        module
    }
}
