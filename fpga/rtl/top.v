// Tang Nano 20K — Running Light (0.5s per step)
// 27 MHz clock → 13.5M counts per half-second
//
// LEDs are ACTIVE LOW: drive 0 to illuminate.
// CST uses PULL_MODE=UP so un-driven pins are pulled high (LED off).

module top(
    input  wire       clk,        // pin 4, 27 MHz
    output wire [5:0] led         // pins 15-20, active low
);

    localparam HALF_SEC = 24'd13_499_999;

    reg [23:0] counter = 0;
    reg [5:0]  pattern = 6'b111110;  // LED[0] on

    always @(posedge clk) begin
        if (counter == HALF_SEC) begin
            counter <= 0;
            pattern <= {pattern[4:0], pattern[5]};  // rotate right: LED[n] → LED[n+1]
        end else begin
            counter <= counter + 1;
        end
    end

    assign led = pattern;

endmodule
