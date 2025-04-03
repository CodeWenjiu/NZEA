package BASYS

import chisel3._
import chisel3.util._
import chisel3.experimental.Analog

object II_final_state_Enum extends ChiselEnum{
    val s_idle, s_subui_1, s_subui_2, s_subui_3 = Value
}

class II_final extends Module{
    val io = IO(new Bundle{
        val sync = new VGASyncIO
        val rgb = Output(UInt(12.W))
        val ps2_clk = Analog(1.W)
        val ps2_data = Analog(1.W)
    })
    
    val vga_sync = Module(new vga_sync)

    if(sync_config.sim == true){
        val vga_valid = IO(Output(Bool()))
        vga_valid := vga_sync.Ctrl.valid
    }

    io.sync <> vga_sync.io
    
    val img_pointer = Module(new img_pointer)
    img_pointer.io.vgaCtrl := vga_sync.Ctrl
    img_pointer.io.ps2_clk <> io.ps2_clk
    img_pointer.io.ps2_data <> io.ps2_data

    val (window_match, _) = BASYS_utils.pos_match(20.U, 0.U, 600.U, 480.U, vga_sync.Ctrl.xaddr, vga_sync.Ctrl.yaddr, true.B)

    def ui_posx = 170
    def ui_posy = 90
    def subui_posx = 220
    def subui_posy = 180

    val img_ui = Module(new image(300, 300, "ui"))
    img_ui.io.pos_x := ui_posx.U
    img_ui.io.pos_y := ui_posy.U
    img_ui.io.vgaCtrl := vga_sync.Ctrl
    img_ui.io.ena   := true.B

    val img_subui = Module(new image(200, 120, "subui"))
    img_subui.io.pos_x := subui_posx.U
    img_subui.io.pos_y := subui_posy.U
    img_subui.io.vgaCtrl := vga_sync.Ctrl
    img_subui.io.ena   := true.B

    val button_select = WireDefault(0.U(6.W))

    val mainui_insert_cond = Seq(
        BASYS_utils.pos_hit((ui_posx + 125).U, (ui_posy + 245).U, 47.U, 25.U, img_pointer.io.mouse_xpos, img_pointer.io.mouse_ypos, true.B),
        BASYS_utils.pos_hit((ui_posx + 180).U, (ui_posy + 245).U, 47.U, 25.U, img_pointer.io.mouse_xpos, img_pointer.io.mouse_ypos, true.B),
        BASYS_utils.pos_hit((ui_posx + 230).U, (ui_posy + 245).U, 50.U, 25.U, img_pointer.io.mouse_xpos, img_pointer.io.mouse_ypos, true.B),
    )

    val mainui_buy_cond = Seq(
        BASYS_utils.pos_hit(200.U, 240.U, 64.U, 40.U, img_pointer.io.mouse_xpos, img_pointer.io.mouse_ypos, true.B),
        BASYS_utils.pos_hit(290.U, 240.U, 64.U, 40.U, img_pointer.io.mouse_xpos, img_pointer.io.mouse_ypos, true.B),
        BASYS_utils.pos_hit(380.U, 240.U, 64.U, 40.U, img_pointer.io.mouse_xpos, img_pointer.io.mouse_ypos, true.B),
    )

    val subui_define_cond = Seq(
        BASYS_utils.pos_hit((subui_posx + 14).U, (subui_posy + 80).U, 56.U, 28.U, img_pointer.io.mouse_xpos, img_pointer.io.mouse_ypos, true.B),
        BASYS_utils.pos_hit((subui_posx + 130).U, (subui_posy + 80).U, 56.U, 28.U, img_pointer.io.mouse_xpos, img_pointer.io.mouse_ypos, true.B),
    )

    val subui_change_cond = Seq(
        BASYS_utils.pos_hit((subui_posx + 43).U, (subui_posy + 31).U, 22.U, 20.U, img_pointer.io.mouse_xpos, img_pointer.io.mouse_ypos, true.B),
        BASYS_utils.pos_hit((subui_posx + 132).U, (subui_posy + 31).U, 22.U, 20.U, img_pointer.io.mouse_xpos, img_pointer.io.mouse_ypos, true.B),
    )

    val subui_select = MuxCase(0.U, subui_define_cond.zip(Seq(1.U, 2.U)))

    val ui_state = RegInit(II_final_state_Enum.s_idle)

    val num_total = RegInit(0.U(14.W))
    val num_buy = RegInit(0.U(7.W))

    def state_subtransmit(value: Int): II_final_state_Enum.Type = {
        MuxCase(ui_state, Seq(
            (subui_select === 1.U) -> II_final_state_Enum.s_idle,
            (subui_select === 2.U) -> Mux(num_total >= (num_buy * value.U), II_final_state_Enum.s_idle, ui_state),
        ))
    }

    when(img_pointer.io.Left_click){
        ui_state := MuxLookup(ui_state, II_final_state_Enum.s_idle)(Seq(
            II_final_state_Enum.s_idle -> MuxCase(ui_state, mainui_buy_cond.zip(Seq(
                II_final_state_Enum.s_subui_1,
                II_final_state_Enum.s_subui_2,
                II_final_state_Enum.s_subui_3
            ))),
            II_final_state_Enum.s_subui_1 -> state_subtransmit(10),
            II_final_state_Enum.s_subui_2 -> state_subtransmit(25),
            II_final_state_Enum.s_subui_3 -> state_subtransmit(40),
        ))
    }

    button_select := Mux1H(mainui_buy_cond.zip(Seq(4.U, 2.U, 1.U)))

    val img_button = Module(new img_button)
    img_button.io.pos_x := 380.U
    img_button.io.pos_y := 240.U
    img_button.io.vgaCtrl := vga_sync.Ctrl
    img_button.io.ena   := true.B
    img_button.io.state := Mux(ui_state === II_final_state_Enum.s_idle, button_select(2, 0), 0.U)

    when(img_pointer.io.Left_click){
        num_total := MuxLookup(ui_state, num_total)(Seq(
            II_final_state_Enum.s_idle -> MuxCase(num_total, mainui_insert_cond.zip(Seq(5, 10, 100)).map{ 
                case (cond, value) => cond -> (num_total + value.U) 
            }),
            II_final_state_Enum.s_subui_1 -> Mux(subui_select === 2.U && num_total >= (num_buy * 10.U), num_total - (num_buy * 10.U), num_total),
            II_final_state_Enum.s_subui_2 -> Mux(subui_select === 2.U && num_total >= (num_buy * 25.U), num_total - (num_buy * 25.U), num_total),
            II_final_state_Enum.s_subui_3 -> Mux(subui_select === 2.U && num_total >= (num_buy * 40.U), num_total - (num_buy * 40.U), num_total),
        ))
    }

    when(img_pointer.io.Left_click){
        when(ui_state =/= II_final_state_Enum.s_idle){
            num_buy := MuxCase(num_buy, subui_change_cond.zip(Seq(
                    Mux(num_buy > 0.U, num_buy - 1.U, num_buy), 
                    Mux(num_buy < 99.U, num_buy + 1.U, num_buy)
                )).map{
                case (cond, value) => cond -> (value)
            })
        }.otherwise{
            num_buy := 0.U
        }
    }

    def cp1_xpos = 298
    def cp1_ypos = 210
    def cp2_xpos = 282
    def cp2_ypos = 232

    def num_cp1_cut = BASYS_utils.pos_hit(cp1_xpos.U, cp1_ypos.U, 32.U, 16.U, vga_sync.Ctrl.xaddr, vga_sync.Ctrl.yaddr, true.B)
    def num_cp1_hit = (ui_state =/= II_final_state_Enum.s_idle) && num_cp1_cut
    def num_cp2_cut = BASYS_utils.pos_hit(cp2_xpos.U, cp2_ypos.U, 64.U, 16.U, vga_sync.Ctrl.xaddr, vga_sync.Ctrl.yaddr, true.B)
    def num_cp2_hit = (ui_state =/= II_final_state_Enum.s_idle) && num_cp2_cut

    val img_num = Module(new img_number)
    img_num.io.pos_x := Mux(num_cp1_hit, cp1_xpos.U, Mux(num_cp2_hit, cp2_xpos.U, 200.U))
    img_num.io.pos_y := Mux(num_cp1_hit, cp1_ypos.U, Mux(num_cp2_hit, cp2_ypos.U, 330.U))
    img_num.io.vgaCtrl := vga_sync.Ctrl
    img_num.io.ena   := Mux(num_cp1_hit, num_cp1_cut, Mux(num_cp2_hit, num_cp2_cut, true.B))
    img_num.io.number := Mux(num_cp1_hit, num_buy * 100.U, Mux(num_cp2_hit, MuxLookup(ui_state, 0.U)(Seq(
        II_final_state_Enum.s_subui_1 -> 10.U * num_buy,
        II_final_state_Enum.s_subui_2 -> 25.U * num_buy,
        II_final_state_Enum.s_subui_3 -> 40.U * num_buy,
    )), num_total))


    def tar_seq = (0 until 3).map(i => {
        val (tar, _) = BASYS_utils.pos_match((ui_posx + 25 + 90 * i).U, (ui_posy + 60).U, 70.U, 80.U, vga_sync.Ctrl.xaddr, vga_sync.Ctrl.yaddr, true.B)
        tar
    })
    def buy_seq = Seq(
        II_final_state_Enum.s_subui_1,
        II_final_state_Enum.s_subui_2,
        II_final_state_Enum.s_subui_3
    ).map(state => RegNext(ui_state === state) && ui_state === II_final_state_Enum.s_idle && (subui_select === 2.U) && (num_buy =/= 0.U))

    class blink_reg extends Bundle{
        val state = UInt(1.W)
        val count = UInt(27.W)
    }

    val tar3_blink = RegInit(VecInit(Seq.fill(3)(0.U.asTypeOf(new blink_reg))))

    tar3_blink.zipWithIndex.map{ case (reg, i) =>
        reg.state := MuxLookup(reg.state, 0.U)(Seq(
            0.U -> Mux(buy_seq(i), 1.U, 0.U),
            1.U -> Mux(reg.count === ((1 << 27) - 1).U, 0.U, 1.U)
        ))
        when(reg.state === 1.U){
            reg.count := Mux(reg.count === ((1 << 27) - 1).U, 0.U, reg.count + 1.U)
        }
    }

    val ui_rgb = Mux(
        tar_seq.zipWithIndex.map{ case (tar, i) => tar && tar3_blink(i).count(27 - 3) }.reduce(_ | _),
        "hfff".U, img_ui.io.rgb)

    val rgb_cond = Seq(
        img_pointer.io.hit,
        img_num.io.hit,
        img_subui.io.hit && ui_state =/= II_final_state_Enum.s_idle,
        img_button.io.hit,
        img_ui.io.hit,
        window_match,
        !window_match
    )

    io.rgb := PriorityMux(rgb_cond.zip(Seq(
        img_pointer.io.rgb,
        img_num.io.rgb,
        img_subui.io.rgb,
        img_button.io.rgb,
        ui_rgb,
        "hfff".U,
        "h000".U
    )))
}
