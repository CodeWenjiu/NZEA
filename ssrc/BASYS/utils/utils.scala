package BASYS

import chisel3._
import chisel3.util._

object BASYS_utils {
    def pos_hit(x: UInt, y: UInt, w: UInt, h: UInt, target_x: UInt, target_y: UInt, ena: Bool): Bool = {
        (target_x >= x && target_x < (x +& w) && target_y >= y && target_y < (y +& h)) && ena
    }

    def pos_match(x: UInt, y: UInt, w: UInt, h: UInt, target_x: UInt, target_y: UInt, ena: Bool): (Bool, Data) = {
        (
            (target_x >= x && target_x < (x +& w) && target_y >= y && target_y < (y +& h)) && ena,
            ((target_x - x) + (target_y - y) * w)
        )
    }
}
