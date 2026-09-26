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
// --------------------------------------------------------------------\
module ddr3_generate_data
      #(
          parameter LENGTH = 256
      )
  
  (
            input                ui_clk,
            input                ui_rst,
            //write interface
            output               ddr3_wr_start,
            input                ddr3_wr_data_req,
            output  [127:0]      ddr3_wr_data,
            input                ddr3_wr_addr_req,
            output  reg [28:0]   ddr3_wr_addr ,
            input                ddr3_wr_done,
           //read interface
            output               ddr3_rd_start   ,
            input                ddr3_rd_data_vld,
            input      [127:0]   ddr3_rd_data    ,
            input                ddr3_rd_addr_req,
            output reg  [28:0]   ddr3_rd_addr    ,
            input                ddr3_rd_done    ,
            output  reg          error   //用来指示比对数据是否错误
      );
  reg [3:0]state;
  reg wr_rd_flag;  //0 write 1 read
  reg [7:0]wr_data_cnt;
  wire add_wr_data_cnt;
  wire end_wr_data_cnt;
  reg [7:0]rd_data_cnt;
  wire add_rd_data_cnt;
  wire end_rd_data_cnt;
  localparam IDLE = 4'b0001;
  localparam ARBIT =4'b0010;
  localparam WRITE =4'b0100;
  localparam READ  =4'b1000;
  localparam MAX_NUM = LENGTH-1;
  always @(posedge ui_clk or posedge ui_rst) begin
      if(ui_rst)
          state<=IDLE;
      else begin
             case(state)
               IDLE:begin
                   state<=ARBIT;
               end
               ARBIT:
                  begin
                      if(ddr3_wr_start)
                         state<=WRITE;
                      else if(ddr3_rd_start)
                              state<=READ;
                      else state<=ARBIT;
                  end
               WRITE:
                  begin
                      if(ddr3_wr_done)
                           state<=ARBIT;
                      else 
                           state<=WRITE;
                  end
              READ:
                  begin
                      if(ddr3_rd_done)
                          state<=ARBIT;
                      else 
                          state<=READ;
                  end
              default:state<=ARBIT;
             endcase 
      end
  end
  //wr_rd_flag
  always @(posedge ui_clk or posedge ui_rst) begin
      if(ui_rst)
          wr_rd_flag<=1'b0;//write
      else if(wr_rd_flag==1'b0&&ddr3_wr_done)
               wr_rd_flag<=1'b1;//read
      else if(wr_rd_flag==1'b1&&ddr3_rd_done)
              wr_rd_flag<=1'b0;
      else wr_rd_flag<=wr_rd_flag;
  end
  //ddr3_wr_start
  assign ddr3_wr_start= wr_rd_flag==1'b0&&state==ARBIT;
  //ddr3_rd_start
  assign ddr3_rd_start=wr_rd_flag==1'b1&&state==ARBIT;
  //wr_data_cnt
  always @(posedge ui_clk or posedge ui_rst) begin
      if(ui_rst)
          wr_data_cnt<='d0;
      else if(add_wr_data_cnt)begin
              if(end_wr_data_cnt)
                  wr_data_cnt<='d0;
              else 
                  wr_data_cnt<=wr_data_cnt+1'b1;
      end
      else wr_data_cnt<=wr_data_cnt;
  end
  assign add_wr_data_cnt=ddr3_wr_data_req;
  assign end_wr_data_cnt=ddr3_wr_data_req&&(wr_data_cnt==MAX_NUM);
  
  //rd_data_cnt
  always @(posedge ui_clk or posedge ui_rst) begin
      if(ui_rst)
          rd_data_cnt<='d0;
      else if(add_rd_data_cnt)begin
              if(end_rd_data_cnt)
                  rd_data_cnt<='d0;
              else 
                  rd_data_cnt<=rd_data_cnt+1'b1;
      end
      else rd_data_cnt<=rd_data_cnt;
  end
  assign add_rd_data_cnt=ddr3_rd_data_vld;
  assign end_rd_data_cnt=ddr3_rd_data_vld&&(rd_data_cnt==MAX_NUM);
  //ddr3_wr_addr
  always @(posedge ui_clk or posedge ui_rst) begin
      if(ui_rst)
          ddr3_wr_addr<='d0;
      else if(ddr3_wr_addr_req)
                  ddr3_wr_addr<=ddr3_wr_addr+'d8;
      else ddr3_wr_addr<=ddr3_wr_addr;
  
  end
  //ddr3_rd_addr
  always @(posedge ui_clk or posedge ui_rst) begin
      if(ui_rst)
          ddr3_rd_addr<='d0;
      else if(ddr3_rd_addr_req)
              ddr3_rd_addr<=ddr3_rd_addr+'d8;
      else ddr3_rd_addr<=ddr3_rd_addr; 
  end
  assign ddr3_wr_data ={16{wr_data_cnt}};
  //error  
  always @(posedge ui_clk or posedge ui_rst) begin
      if(ui_rst)
          error<=1'b0;
      else if(ddr3_rd_data_vld)begin
              if(ddr3_rd_data!={16{rd_data_cnt}}) 
                   error<=1'b1;
              else 
                  error<=error;
      end
      else     error<=error;
  end
  endmodule