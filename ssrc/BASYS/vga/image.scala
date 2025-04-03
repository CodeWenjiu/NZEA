package BASYS

import chisel3._
import chisel3.util._
import chisel3.experimental.Analog

class image(width: Int, height: Int, name: String) extends Module{
    val io = IO(new Bundle{
        val vgaCtrl = Input(new VGACtrlIO)
        val pos_x = Input(UInt())
        val pos_y = Input(UInt())
        val ena   = Input(Bool())
        val hit = Output(Bool())
        val rgb = Output(UInt(12.W))
    })
    
    val (vga_match, raddr) = BASYS_utils.pos_match(io.pos_x, io.pos_y, width.U, height.U, io.vgaCtrl.xaddr, io.vgaCtrl.yaddr, io.ena & io.vgaCtrl.valid)

    val addr_width = log2Ceil(width * height)

    io.hit := vga_match

    if (sync_config.sim == true) {
        class BRAM_ui_sim       extends BRAM_sim(width_addr = addr_width, width_data = 12, "ui")
        class BRAM_subui_sim    extends BRAM_sim(width_addr = addr_width, width_data = 12, "subui")
        class BRAM_button_sim   extends BRAM_sim(width_addr = addr_width, width_data = 12, "button")
        class BRAM_pointer_sim  extends BRAM_sim(width_addr = addr_width, width_data = 12, "pointer")

        val bram = name match {
            case "ui" => Module(new BRAM_ui_sim)
            case "subui" => Module(new BRAM_subui_sim)
            case "button" => Module(new BRAM_button_sim)
            case "pointer" => Module(new BRAM_pointer_sim)
        }

        bram.io.clka := clock
        bram.io.ena  := vga_match
        bram.io.addra  := raddr
        io.rgb := bram.io.douta
    } else {
        class BRAM_framea   extends BRAM(width_addr = addr_width, width_data = 12)
        class BRAM_frameb   extends BRAM(width_addr = addr_width, width_data = 12)
        class BRAM_ui       extends BRAM(width_addr = addr_width, width_data = 12)
        class BRAM_subui    extends BRAM(width_addr = addr_width, width_data = 12)
        class BRAM_pointer  extends BRAM(width_addr = addr_width, width_data = 12)

        val bram = name match {
            case "frame_a" => Module(new BRAM_framea)
            case "frame_b" => Module(new BRAM_frameb)
            case "ui" => Module(new BRAM_ui)
            case "subui" => Module(new BRAM_subui)
            case "pointer" => Module(new BRAM_pointer)
            case _ => Module(new BRAM(16, 12))
        }
        
        bram.io.clka := clock
        bram.io.ena  := vga_match
        bram.io.addra  := raddr
        io.rgb := bram.io.douta
    }
}

class img_pointer extends Module{
    val io = IO(new Bundle{
        val vgaCtrl = Input(new VGACtrlIO)

        val hit = Output(Bool())
        val rgb = Output(UInt(12.W))

        val ps2_clk = Analog(1.W)
        val ps2_data = Analog(1.W)

        val Left_click = Output(Bool())
        val Right_click = Output(Bool())
        val mouse_xpos = Output(UInt(11.W))
        val mouse_ypos = Output(UInt(10.W))
    })

    val mouse_pointer = Module(new mouse_pointer)
    mouse_pointer.io.ps2_clk <> io.ps2_clk
    mouse_pointer.io.ps2_data <> io.ps2_data

    mouse_pointer.io.Left_click <> io.Left_click
    mouse_pointer.io.Right_click <> io.Right_click
    mouse_pointer.io.mouse_xpos <> io.mouse_xpos
    mouse_pointer.io.mouse_ypos <> io.mouse_ypos

    val img_p = Module(new image(32, 32, "pointer"))
    img_p.io.pos_x := mouse_pointer.io.mouse_xpos
    img_p.io.pos_y := mouse_pointer.io.mouse_ypos
    img_p.io.vgaCtrl := io.vgaCtrl
    img_p.io.ena   := true.B

    io.hit := img_p.io.hit
    io.rgb := img_p.io.rgb
}

class img_number extends Module{
    val io = IO(new Bundle{
        val vgaCtrl = Input(new VGACtrlIO)
        val pos_x = Input(UInt())
        val pos_y = Input(UInt())
        val ena   = Input(Bool())

        val number = Input(UInt(14.W))

        val hit = Output(Bool())
        val rgb = Output(UInt(12.W))
    })

    val x_bias = io.vgaCtrl.xaddr - io.pos_x
    val y_bias = io.vgaCtrl.yaddr - io.pos_y
    val f_addr = x_bias % 16.U + y_bias * 160.U

    val vga_matchs = (0 until 4).map(i => {
        val (vga_match, _ ) = BASYS_utils.pos_match(io.pos_x + (i * 16).U, io.pos_y, 16.U, 16.U, io.vgaCtrl.xaddr, io.vgaCtrl.yaddr, io.ena & io.vgaCtrl.valid)
        vga_match
    })

    val bits = (0 until 4).map(i => {
        val bit = WireDefault(io.number / (math.pow(10, i)).toInt.U % 10.U)
        bit
    }).reverse

    val hitRgbPairs = vga_matchs.zipWithIndex.map{ case (vga_match, i) => (vga_match, f_addr + (16.U * bits(i))) }

    io.hit := vga_matchs.reduce(_ | _)

    class BRAM_number_sim extends BRAM_sim(width_addr = log2Ceil(16 * 16 * 10), width_data = 12, "number")
    class BRAM_number   extends BRAM(width_addr = log2Ceil(16 * 16 * 10), width_data = 12)

    if (sync_config.sim) {
        val num_i = Module(new BRAM_number_sim)
        num_i.io.clka := clock
        num_i.io.ena  := io.ena

        num_i.io.addra := Mux1H(hitRgbPairs)

        io.rgb := Mux(x_bias > (16 * 3).U && x_bias <= (16 * 3 + 4).U && y_bias > (16 - 4).U && y_bias <= (16).U, "h000".U, num_i.io.douta)
    } else {
        val num_i = Module(new BRAM_number)
        num_i.io.clka := clock
        num_i.io.ena  := io.ena

        num_i.io.addra := Mux1H(hitRgbPairs)

        io.rgb := Mux(x_bias > (16 * 3).U && x_bias <= (16 * 3 + 4).U && y_bias > (16 - 4).U && y_bias <= (16).U, "h000".U, num_i.io.douta)
    }
}

class img_button extends Module{
    val io = IO(new Bundle{
        val vgaCtrl = Input(new VGACtrlIO)
        val pos_x = Input(UInt())
        val pos_y = Input(UInt())
        val ena   = Input(Bool())

        val state = Input(UInt(3.W))

        val hit = Output(Bool())
        val rgb = Output(UInt(12.W))
    })

    def hix = 92
    val x_bias = io.vgaCtrl.xaddr - io.pos_x
    val y_bias = io.vgaCtrl.yaddr - io.pos_y
    val f_addr = x_bias % (hix + 1).U % 64.U + y_bias * 128.U

    val vga_matchs = (0 until 3).map(i => {
        val (vga_match, _) = BASYS_utils.pos_match(io.pos_x - (i * hix).U, io.pos_y, 64.U, 36.U, io.vgaCtrl.xaddr, io.vgaCtrl.yaddr, io.ena & io.vgaCtrl.valid)
        vga_match
    })

    io.hit := vga_matchs.reduce(_ | _)

    val hitRgbPairs = vga_matchs.zipWithIndex.map{ case (vga_match, i) => (vga_match, x_bias % (hix + 1).U % 64.U + Mux(io.state(i), 64.U, 0.U) + y_bias * 128.U) }

    class BRAM_button_sim extends BRAM_sim(width_addr = log2Ceil(128 * 40), width_data = 12, "button")
    class BRAM_button extends BRAM(width_addr = log2Ceil(128 * 40), width_data = 12)

    if (sync_config.sim) {
        val button = Module(new BRAM_button_sim)
        button.io.clka := clock
        button.io.ena := io.ena
        button.io.addra := Mux1H(hitRgbPairs)
        io.rgb := button.io.douta
    } else {
        val button = Module(new BRAM_button)
        button.io.clka := clock
        button.io.ena := io.ena
        button.io.addra := Mux1H(hitRgbPairs)
        io.rgb := button.io.douta
    }
}
