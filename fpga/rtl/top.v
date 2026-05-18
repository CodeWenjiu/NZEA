// Tang Nano 20K — LED Blink (1 Hz)
// 27 MHz clock → 1 second period → 0.5s on, 0.5s off
//
// LEDs are ACTIVE LOW: drive 0 to illuminate.
// CST uses PULL_MODE=UP so un-driven pins are pulled high (LED off).

module top(
    input  wire       clk,        // pin 4, 27 MHz
    output wire [5:0] led         // pins 15-20, active low
);

    // 27 MHz / 2 = 13.5M counts per half-second → 24-bit counter
    // 13,500,000 - 1 = 13,499,999
    localparam HALF_SEC = 24'd13_499_999;

    reg [23:0] counter = 0;
    reg        blink   = 0;

    always @(posedge clk) begin
        if (counter == HALF_SEC) begin
            counter <= 0;
            blink   <= ~blink;
        end else begin
            counter <= counter + 1;
        end
    end

    // All 6 LEDs blink together, active low: 0 = on, 1 = off
    assign led = {6{blink}};

endmodule
