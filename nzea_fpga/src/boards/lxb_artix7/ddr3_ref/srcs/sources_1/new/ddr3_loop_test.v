// --------------------------------------------------------------------
// Copyright (c) 2022 by MicroPhase Technologies Inc. 
// --------------------------------------------------------------------
//
// Permission:
//
//   MicroPhase grants permission to use and modify this code for use
//   in synthesis for all MicroPhase Development Boards.
//   Other use of this code, including the selling 
//   ,duplication, or modification of any portion is strictly prohibited.
//
// Disclaimer:
//
//   This VHDL/Verilog or C/C++ source code is intended as a design reference
//   which illustrates how these types of functions can be implemented.
//   It is the user's responsibility to verify their design for
//   consistency and functionality through the use of formal
//   verification methods.  MicroPhase provides no warranty regarding the use 
//   or functionality of this code.
//
// --------------------------------------------------------------------
//           
//                     MicroPhase Technologies Inc
//                     Shanghai, China
//
//                     web: http://www.microphase.cn/   
//                     email: support@microphase.cn
//
// --------------------------------------------------------------------
// --------------------------------------------------------------------
//   
// Major Functions:   
//
// --------------------------------------------------------------------
// --------------------------------------------------------------------
//
//  Revision History    
//  Date          By            Revision    Change Description
//---------------------------------------------------------------------
// 2022.09.19     Ao Guohua     1.0          Original
//                                                      
// --------------------------------------------------------------------
// --------------------------------------------------------------------
module ddr3_loop_test(
            input           clk       ,
            input           rst_n     ,
            output [14:0]   ddr3_addr     ,    
            output [2:0]    ddr3_ba       ,    
            output          ddr3_cas_n    ,    
            output          ddr3_ck_n     ,    
            output          ddr3_ck_p     ,    
            output          ddr3_cke      ,    
            output          ddr3_ras_n    ,    
            output          ddr3_reset_n  ,    
            output          ddr3_we_n     ,    
            inout  [15:0]   ddr3_dq       ,    
            inout  [1:0]    ddr3_dqs_n    ,    
            inout  [1:0]    ddr3_dqs_p    ,                    
            output [1:0]    ddr3_dm       ,    
            output          ddr3_odt       
    );
    wire        ddr3_busy        ;//1表示ddr3处于忙碌状态  
//write interface
    wire         ddr3_wr_start        ;  
    wire         ddr3_wr_addr_req     ;  
    wire [28:0]  ddr3_wr_addr         ;
    wire         ddr3_wr_data_req     ; 
    wire [127:0] ddr3_wr_data     ; 
    wire         ddr3_wr_done         ;
//read interface;
    wire         ddr3_rd_start        ;
    wire         ddr3_rd_addr_req     ;
    wire [28:0]  ddr3_rd_addr         ;
    wire         ddr3_rd_data_vld     ;
    wire  [127:0]ddr3_rd_data         ;
    wire         ddr3_rd_done         ;
    wire         ui_clk          ;
    wire         ui_rst          ;
    wire         sys_clk        ;
    wire         error          ;
    wire         locked         ;
clock clock
   (
    // Clock out ports
    .clk_out1(sys_clk),     // output clk_out1
    // Status and control signals
    .resetn(rst_n), // input resetn
    .locked(locked),       // output locked
   // Clock in ports
    .clk_in1(clk));      // input clk_in1
  ddr3_ctrl ddr3_ctrl0(
           // Memory interface ports
      .ddr3_addr    (ddr3_addr)     ,              
      .ddr3_ba      (ddr3_ba)     ,          
      .ddr3_cas_n   (ddr3_cas_n)     ,          
      .ddr3_ck_n    (ddr3_ck_n)     ,          
      .ddr3_ck_p    (ddr3_ck_p)     ,          
      .ddr3_cke     (ddr3_cke)     ,          
      .ddr3_ras_n   (ddr3_ras_n)     ,          
      .ddr3_reset_n (ddr3_reset_n)     ,          
      .ddr3_we_n    (ddr3_we_n)     ,          
      .ddr3_dq      (ddr3_dq)     ,          
      .ddr3_dqs_n   (ddr3_dqs_n)     , //用于数据同步，读时是输出,边缘与读出的数据对齐。              
      .ddr3_dqs_p   (ddr3_dqs_p)     , //写时是输入,中心与写数据对齐。                                                    
      .ddr3_dm      (ddr3_dm)     ,          
      .ddr3_odt     (ddr3_odt)     ,   
      //write interface
      .ddr3_wr_start      (ddr3_wr_start)     ,
      .ddr3_wr_addr_req   (ddr3_wr_addr_req)     ,
      .ddr3_wr_addr       (ddr3_wr_addr)     ,
      .ddr3_wr_data_req   (ddr3_wr_data_req)     ,
      .ddr3_wr_data       (ddr3_wr_data)     ,
      .ddr3_wr_done       (ddr3_wr_done)     ,
      //read interface
      .ddr3_rd_start      (ddr3_rd_start) ,
      .ddr3_rd_addr_req   (ddr3_rd_addr_req) ,
      .ddr3_rd_addr       (ddr3_rd_addr) ,
      .ddr3_rd_data_vld   (ddr3_rd_data_vld) ,
      .ddr3_rd_data       (ddr3_rd_data) ,
      .ddr3_rd_done       (ddr3_rd_done) ,
      //system control
      .sys_clk        (sys_clk),
      .sys_rst_n      (locked)  ,
      .ui_clk         (ui_clk) ,
      .ui_rst         (ui_rst),
      .ddr3_busy      (ddr3_busy)
      );
  ddr3_generate_data  ddr3_generate_data
  (
           .ui_clk    (ui_clk),
           .ui_rst    (ui_rst),
            //write interface
            .ddr3_wr_start      (ddr3_wr_start)      ,
            .ddr3_wr_data_req   (ddr3_wr_data_req)   ,
            .ddr3_wr_data       (ddr3_wr_data)   ,
            .ddr3_wr_addr_req   (ddr3_wr_addr_req)   ,
            .ddr3_wr_addr       (ddr3_wr_addr)       ,
            .ddr3_wr_done       (ddr3_wr_done)       ,
           //read interface
           .ddr3_rd_start         (ddr3_rd_start)    ,
           .ddr3_rd_data_vld      (ddr3_rd_data_vld) ,
           .ddr3_rd_data          (ddr3_rd_data) ,
           .ddr3_rd_addr_req      (ddr3_rd_addr_req) ,
           .ddr3_rd_addr          (ddr3_rd_addr)     ,
           .ddr3_rd_done          (ddr3_rd_done)     ,
           .error                 (error)
      );
ila_top ila_top (
	.clk(ui_clk), // input wire clk
    
	.probe0(ddr3_wr_start), // input wire [0:0]  probe0  
	.probe1(ddr3_wr_addr_req), // input wire [0:0]  probe1 
	.probe2(ddr3_wr_addr), // input wire [28:0]  probe2 
	.probe3(ddr3_wr_data_req), // input wire [0:0]  probe3 
	.probe4(ddr3_wr_data), // input wire [255:0]  probe4 
	.probe5(ddr3_wr_done), // input wire [0:0]  probe5  
	.probe6(ddr3_rd_start), // input wire [0:0]  probe6 
	.probe7(ddr3_rd_addr_req), // input wire [0:0]  probe7 
	.probe8(ddr3_rd_addr), // input wire [28:0]  probe8 
	.probe9(ddr3_rd_data_vld), // input wire [0:0]  probe9 
	.probe10(ddr3_rd_data), // input wire [255:0]  probe10 
	.probe11(ddr3_rd_done), // input wire [0:0]  probe11 
	.probe12(error) // input wire [0:0]  probe12
);
  endmodule