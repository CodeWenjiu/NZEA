_default:
    @just --list

# Install BSP metadata for editors
init:
    @mill --no-server mill.bsp.BSP/install

# Elaborate Chisel → Verilog
dump *ARGS:
    @mill --no-server nzea_cli.run {{ ARGS }}

# 4-state iverilog simulation
iv platform isa hex='hello.hex' boot='dir' wave='0':
    @nu iverilog_tb/scripts/iv-build.nu {{ platform }} {{ isa }}
    @nu iverilog_tb/scripts/iv-run.nu {{ platform }} {{ isa }} {{ hex }} {{ boot }} {{ wave }}

# Run yosys synthesis
[group('synth')]
synth *ARGS:
    @just dump --sim false {{ ARGS }}
    @nu synth/flow/synth.nu {{ ARGS }}

# Run STA (requires synthesis first)
[group('synth')]
sta *ARGS:
    @just synth {{ ARGS }}
    @nu synth/flow/sta.nu {{ ARGS }}

[group('utility')]
clean:
    @rm -rf build

[group('utility')]
clean-all: clean
    @mill mill clean

# ---- FPGA (Tang Nano 20K) ----

fpga_dev := "GW2AR-LV18QN88C8/I7"
fpga_fam := "GW2A-18C"
fpga_board := "tangnano20k"
fpga_top := "top"
fpga_rtl := "fpga/rtl/" + fpga_top + ".v"
fpga_cst := "fpga/constraints/tangnano20k.cst"
fpga_build := "build/fpga"

[group('fpga')]
_fpga-synth:
    @mkdir -p {{ fpga_build }}
    yosys -l {{ fpga_build }}/{{ fpga_top }}_synth.log \
        -p "read_verilog {{ fpga_rtl }}; synth_gowin -json {{ fpga_build }}/{{ fpga_top }}.json -family gw2a -top {{ fpga_top }}"

[group('fpga')]
_fpga-pnr: _fpga-synth
    nextpnr-himbaechel \
        --json {{ fpga_build }}/{{ fpga_top }}.json \
        --write {{ fpga_build }}/{{ fpga_top }}_pnr.json \
        --device {{ fpga_dev }} \
        --vopt family={{ fpga_fam }} \
        --vopt cst={{ fpga_cst }} \
        --placer heap \
        --router router1 \
        --timing-allow-fail

[group('fpga')]
pack: _fpga-pnr
    gowin_pack -c -d {{ fpga_fam }} -o {{ fpga_build }}/{{ fpga_top }}.fs {{ fpga_build }}/{{ fpga_top }}_pnr.json

[group('fpga')]
prog: pack
    openFPGALoader -b {{ fpga_board }} --verify {{ fpga_build }}/{{ fpga_top }}.fs

[group('fpga')]
flash: pack
    openFPGALoader -b {{ fpga_board }} -f --verify {{ fpga_build }}/{{ fpga_top }}.fs
