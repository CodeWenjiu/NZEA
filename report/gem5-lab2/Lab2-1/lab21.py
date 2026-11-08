"""Lab 2.1 configuration, derived from configs/learning_gem5/part1/simple.py.

The CPU model, clock and DRAM are command-line options so one script covers
every row of the comparison table.
"""

import argparse

import m5
from m5.objects import *

parser = argparse.ArgumentParser()
parser.add_argument(
    "--cpu", default="TimingSimpleCPU", choices=["TimingSimpleCPU", "MinorCPU"]
)
parser.add_argument("--freq", default="1GHz")
parser.add_argument(
    "--mem", default="DDR3_1600_8x8", choices=["DDR3_1600_8x8", "DDR3_2133_8x8"]
)
parser.add_argument("--binary", required=True)
parser.add_argument("--args", default="")
options = parser.parse_args()

system = System()

system.clk_domain = SrcClockDomain()
system.clk_domain.clock = "1GHz"
system.clk_domain.voltage_domain = VoltageDomain()

system.mem_mode = "timing"
system.mem_ranges = [AddrRange("512MiB")]

system.cpu = (
    X86TimingSimpleCPU() if options.cpu == "TimingSimpleCPU" else X86MinorCPU()
)

system.cpu.clk_domain = SrcClockDomain()
system.cpu.clk_domain.clock = options.freq
system.cpu.clk_domain.voltage_domain = system.clk_domain.voltage_domain

system.membus = SystemXBar()

system.cpu.icache_port = system.membus.cpu_side_ports
system.cpu.dcache_port = system.membus.cpu_side_ports

system.cpu.createInterruptController()
system.cpu.interrupts[0].pio = system.membus.mem_side_ports
system.cpu.interrupts[0].int_requestor = system.membus.cpu_side_ports
system.cpu.interrupts[0].int_responder = system.membus.mem_side_ports

system.mem_ctrl = MemCtrl()
system.mem_ctrl.dram = (
    DDR3_1600_8x8() if options.mem == "DDR3_1600_8x8" else DDR3_2133_8x8()
)
system.mem_ctrl.dram.range = system.mem_ranges[0]
system.mem_ctrl.port = system.membus.mem_side_ports

system.system_port = system.membus.cpu_side_ports

system.workload = SEWorkload.init_compatible(options.binary)

process = Process()
process.cmd = [options.binary] + options.args.split()
system.cpu.workload = process
system.cpu.createThreads()

root = Root(full_system=False, system=system)
m5.instantiate()

print("Beginning simulation!")
exit_event = m5.simulate()
print(f"Exiting @ tick {m5.curTick()} because {exit_event.getCause()}")
