// Tang Nano 20K — Running Light + Key test
// 100 MHz clock → 50M counts per half-second
//
// LEDs are ACTIVE LOW: drive 0 to illuminate.
// Keys are pulled HIGH when open, LOW when pressed.
// LED[4] mirrors S1, LED[5] mirrors S2 (inverted: key pressed → LED on).

module top(
    input  wire       clk,        // pin 13, 100 MHz
    input  wire       s1,         // user key S1
    input  wire       s2,         // user key S2
    output wire [5:0] led         // active low
);

    localparam HALF_SEC = 26'd49_999_999;

    reg [25:0] counter = 0;
    reg [3:0]  pattern = 4'b1110;  // LED[0] on (running light)

    always @(posedge clk) begin
        if (counter == HALF_SEC) begin
            counter <= 0;
            pattern <= {pattern[2:0], pattern[3]};  // rotate
        end else begin
            counter <= counter + 1;
        end
    end

    assign led[3:0] = pattern;
    assign led[4]   = ~s1;  // key pressed → LOW → LED on
    assign led[5]   = ~s2;

endmodule
