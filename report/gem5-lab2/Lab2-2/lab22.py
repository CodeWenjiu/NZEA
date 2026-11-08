"""Lab 2.2 configuration: O3CPU with L1I/L1D and an optional L2.

Options cover the seven rows of the required table: issue width, clock and the
L2 size/associativity. The L1s stay at the lab's initial values (L1I 32 KiB/2,
L1D 64 KiB/2).
"""

import argparse

import m5
from m5.objects import *


class L1ICache(Cache):
    size = "32KiB"
    assoc = 2
    tag_latency = 2
    data_latency = 2
    response_latency = 2
    mshrs = 4
    tgts_per_mshr = 4


class L1DCache(Cache):
    size = "64KiB"
    assoc = 2
    tag_latency = 2
    data_latency = 2
    response_latency = 2
    mshrs = 4
    tgts_per_mshr = 4


class L2Cache(Cache):
    tag_latency = 8
    data_latency = 8
    response_latency = 8
    mshrs = 16
    tgts_per_mshr = 8


parser = argparse.ArgumentParser()
parser.add_argument("--width", type=int, default=8)
parser.add_argument("--freq", default="1GHz")
parser.add_argument("--l2-size", default="256KiB")
parser.add_argument("--l2-assoc", type=int, default=8)
parser.add_argument("--binary", required=True)
parser.add_argument("--args", default="")
options = parser.parse_args()

system = System()

system.clk_domain = SrcClockDomain()
system.clk_domain.clock = "1GHz"
system.clk_domain.voltage_domain = VoltageDomain()

system.mem_mode = "timing"
system.mem_ranges = [AddrRange("1GiB")]

system.cpu = X86O3CPU()
system.cpu.issueWidth = options.width

system.cpu.clk_domain = SrcClockDomain()
system.cpu.clk_domain.clock = options.freq
system.cpu.clk_domain.voltage_domain = system.clk_domain.voltage_domain

system.membus = SystemXBar()

system.cpu.icache = L1ICache()
system.cpu.dcache = L1DCache()
system.cpu.icache_port = system.cpu.icache.cpu_side
system.cpu.dcache_port = system.cpu.dcache.cpu_side

if options.l2_size != "none":
    system.l2bus = L2XBar()
    system.l2cache = L2Cache()
    system.l2cache.size = options.l2_size
    system.l2cache.assoc = options.l2_assoc

    system.cpu.icache.mem_side = system.l2bus.cpu_side_ports
    system.cpu.dcache.mem_side = system.l2bus.cpu_side_ports
    system.l2bus.mem_side_ports = system.l2cache.cpu_side
    system.l2cache.mem_side = system.membus.cpu_side_ports
else:
    system.cpu.icache.mem_side = system.membus.cpu_side_ports
    system.cpu.dcache.mem_side = system.membus.cpu_side_ports

system.cpu.createInterruptController()
system.cpu.interrupts[0].pio = system.membus.mem_side_ports
system.cpu.interrupts[0].int_requestor = system.membus.cpu_side_ports
system.cpu.interrupts[0].int_responder = system.membus.mem_side_ports

system.mem_ctrl = MemCtrl()
system.mem_ctrl.dram = DDR3_1600_8x8()
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
