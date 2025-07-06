package utility

import chisel3._
import chisel3.util._

class RotateShifter(width: Int) extends Module {
    val io = IO(new Bundle {
        val shamt = Input(UInt(log2Ceil(width).W))
        val left = Input(Bool())
        val shift = Input(Bool())
        val setData = Input(UInt(width.W))
        val setEnable = Input(Bool())
        val data = Output(UInt(width.W))
    })
    
    val dataReg = RegInit(0.U(width.W))
    
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
    def apply(width: Int): RotateShifter = {
        Module(new RotateShifter(width))
    }
}
