_default:
    @just --list

# 安装编辑器所需的 BSP 元数据。
init:
    @mill --no-server mill.bsp.BSP/install

# 模拟器调用的是 `just dump --target core --isa riscv32im [--outDir <dir>]`，所以 flag 直接透传给 CLI（默认值在那边）；
# 不给 --outDir 时布局是 build/<target>/<platform>/<isa> —— 板级则是 build/fpga/<board>/<isa>，
# 即 Vivado 工作根所在的目录。`--target` 选层：core / tile / fpga。

# 把一个设计生成 Verilog。
[group('build')]
dump *ARGS:
    @mill --no-server nzea_cli.runMain nzea_cli.Main {{ ARGS }}

# 三段一条链：生成综合用顶层（`--flow yosys`，总线外露、无 DPI bridge）、Yosys 综合（设置 PDK_PATH 时映
# 射到 PDK）、STA。需要 PDK_PATH 与 iEDA；面积写在 synth_stat.txt，时序在 *.rpt。

# 综合并对网表跑 STA。
[group('build')]
sta *ARGS:
    @just dump --flow yosys {{ ARGS }}
    @nu synth/flow/synth.nu {{ ARGS }}
    @nu synth/flow/sta.nu {{ ARGS }}

# 跑某个模块的测试套件。
[group('test')]
test module='nzea_rtl':
    @mill --no-server {{ module }}.test

# 构造器是 `nzea_core/test/asm/build-elf.sh`，程序是 `nzea_core/test/asm/<name>.S`；运行属于 remu 自己的
# shell —— 运行命令写在程序头部。

# 把 difftest 程序汇编成 `build/asm/` 下的 ELF。
[group('test')]
asm-elf name target='riscv32im':
    @bash nzea_core/test/asm/build-elf.sh {{ name }} {{ target }}

# 跑类名匹配 pattern 的测试。
[group('test')]
test-match module pattern:
    @mill --no-server {{ module }}.test.testOnly "*{{ pattern }}*"

# `./.zaozi` 变化时重跑。用本仓库的 dev shell（zaozi 自己的 shell 还会兑现 mtf 锁定的 Maven 仓库，这里不需要）；
# JAVA_TOOL_OPTIONS=-Xss32m 是因为 scalac 要在 95K 行 jextract 出来的 CAPI.java 上递归。

# 把 `./.zaozi` fork 发布到本地 ivy 仓库（`me.jiuyang::*`）。
[group('zaozi')]
zaozi-publish:
    @nix develop --command bash -c 'cd .zaozi && JAVA_TOOL_OPTIONS=-Xss32m mill --no-server mlirlib.publishLocal && mill --no-server circtlib.publishLocal && mill --no-server zaozi.publishLocal && mill --no-server decoder.publishLocal && mill --no-server stdlib.publishLocal && mill --no-server zaozi-compiler-plugin.publishLocal'

# 删除 build 树。
[group('utility')]
clean:
    @rm -rf build

# 删除 build 树与 Mill 缓存。
[group('utility')]
clean-all: clean
    @mill --no-server clean

# 用 surfer 打开波形。
[group('utility')]
wave path:
    @nixGLIntel surfer {{ path }}

# ---- FPGA (lxb_artix7 / Vivado) ----
# 板级流程住在自己的模块里，和实现它的脚本放在一起（用 `just --list fpga` 列出）。
# RTL 走本表的 `just dump --target fpga`，模块从那份 dump 树出发。

# 板子的 Vivado 工程、比特流与 ILA 抓取。
mod fpga 'nzea_fpga/justfile'

# ---- board tools (probe/) ----
# 工具箱只和已编程的板子打交道，不依赖核心源码。两个 recipe 都是纯透传：
# 子命令与它们的默认值住在工具自己的 `--help` 里，这里不再存第二份。

# 和已编程的板子通信。
[group('probe')]
probe *ARGS:
    @cargo run --release --quiet -p probe_cli -- {{ ARGS }}

# 内建程序用 `--list` 列出（`just asm2hex --list`）。

# 把测试程序汇编成 tile boot hex。
[group('probe')]
asm2hex *ARGS:
    @cargo run --release --quiet -p asm2hex -- {{ ARGS }}
