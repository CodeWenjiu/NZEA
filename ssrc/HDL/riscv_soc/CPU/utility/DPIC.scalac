package utility

import chisel3._
import chisel3.util.circt.dpi.DPIClockedVoidFunctionImport
import config.Config

trait DPI extends DPIClockedVoidFunctionImport {
    def wrap_callWithEnable(enable: Bool, args: Data*): Unit = {
        val wrappedArgs = args.map(DataConverter(_))
        if(Config.Simulate) super.callWithEnable(enable, wrappedArgs: _*)
    }
    def wrap_call(args: Data*): Unit = {
        val wrappedArgs = args.map(DataConverter(_))
        if(Config.Simulate) super.call(wrappedArgs: _*)
    }

    def DataConverter(data: Data): Data = {
        val width = data.getWidth
        
        val targetWidth = width match {
            case w if w <= 8          => 8
            case w if w <= 16         => 16
            case w if w <= 32         => 32
            case w if w <= 64         => 64
            case w                    => w
        }
        
        if (targetWidth == width) data else data.asTypeOf(UInt(targetWidth.W))
    }
}
