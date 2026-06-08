// Commit tracker — monitors commit messages, detects finisher/timeout, prints PASS/FAIL.
// Parameters are configurable for different test scenarios.
module commit_tracker #(
    parameter MAX_CYCLES            = 200000,
    parameter FINISH_DRAIN          = 20000,
    parameter START_PC              = 32'h80000000,
    parameter POST_FINISHER_COMMITS = 15
) (
    input         clk,
    input         rst_n,
    input         cpu_running,
    input         commit_msg_valid,
    input  [31:0] commit_msg_next_pc,
    input  [4:0]  commit_msg_rd_index,
    input  [31:0] commit_msg_rd_value,
    input         finish_passed
);
    reg [31:0] cycle, commit_count;
    reg        finished_latched;
    reg [31:0] finish_cycle;
    reg        monitor_active;
    reg        cpu_running_d1;
    reg [31:0] stored_pc;
    reg [7:0]  post_finisher_commits;

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            cycle          <= 0;
            commit_count   <= 0;
            finished_latched <= 0;
            finish_cycle   <= 0;
            monitor_active <= 0;
            cpu_running_d1 <= 0;
            stored_pc      <= START_PC;
            post_finisher_commits <= 0;
        end else begin
            cpu_running_d1 <= cpu_running;
            if (cpu_running && !cpu_running_d1 && !monitor_active) begin
                monitor_active <= 1;
                $display("[%0t] CPU started (cpu_reset released)", $time);
            end
            if (monitor_active) begin
                cycle <= cycle + 1;
                if (finish_passed && !finished_latched) begin
                    $display("[%0t] Finisher triggered (cycle=%0d)", $time, cycle);
                    finished_latched <= 1;
                    finish_cycle <= cycle + FINISH_DRAIN;
                    post_finisher_commits <= POST_FINISHER_COMMITS;
                end
                if (finished_latched && cycle >= finish_cycle) begin
                    if (commit_count > 0)
                        $display("PASS: finisher triggered, %0d commits", commit_count);
                    else
                        $display("FAIL: finisher triggered but no commits");
                    $finish;
                end
                if (cycle > MAX_CYCLES) begin
                    $display("FAIL: timeout, %0d commits", commit_count);
                    $finish;
                end
                if (commit_msg_valid && !finished_latched ||
                    (finished_latched && post_finisher_commits > 0)) begin
                    if (finished_latched)
                        post_finisher_commits <= post_finisher_commits - 1;
                    commit_count <= commit_count + 1;
                    stored_pc <= commit_msg_next_pc;
                    $display("[%0t] #%0d (c%0d): pc=%08h next_pc=%08h rd=x%0d val=%08h",
                        $time, commit_count + 1, cycle, stored_pc, commit_msg_next_pc,
                        commit_msg_rd_index, commit_msg_rd_value);
                end
            end
        end
    end
endmodule
