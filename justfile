_default:
    @just --list

# 安装编辑器所需的 BSP 元数据（Metals 这边只需要它）。
init:
    @mill --no-server mill.bsp.BSP/install

# 模拟器调用的是 `just dump --target core --isa riscv32im [--outDir <dir>]`，所以 flag 直接透传给 CLI（默认值在那边）；
# 不给 --outDir 时布局是 build/<target>/<platform>/<isa> —— 板级则是 build/fpga/<board>/<isa>，
# 即 Vivado 工作根所在的目录。

# 把一个设计生成 Verilog（`--target core|tile|fpga`）。
[group('build')]
dump *ARGS:
    @mill --no-server nzea_cli.runMain nzea_cli.Main {{ ARGS }}

# 先生成综合用顶层（`--flow yosys`，总线外露、无 DPI bridge），再综合它；设置 PDK_PATH 时映射到 PDK。

# 跑 Yosys 综合。
[group('synth')]
synth *ARGS:
    @just dump --flow yosys {{ ARGS }}
    @nu synth/flow/synth.nu {{ ARGS }}

# 对综合后的网表跑 STA（需先 `just synth`；需要 PDK_PATH 与 iEDA）。
[group('synth')]
sta *ARGS:
    @just synth {{ ARGS }}
    @nu synth/flow/sta.nu {{ ARGS }}

# 跑某个模块的测试套件（如 `just test nzea_rtl`）。
[group('test')]
test module='nzea_rtl':
    @mill --no-server {{ module }}.test

# 构造器是 `nzea_core/test/asm/build-elf.sh`；运行属于 remu 自己的 shell —— 运行命令写在程序头部。

# 把 difftest 程序（`nzea_core/test/asm/<name>.S`）汇编成 `build/asm/` 下的 ELF。
[group('test')]
asm-elf name target='riscv32im':
    @bash nzea_core/test/asm/build-elf.sh {{ name }} {{ target }}

# 跑类名匹配 pattern 的测试（如 "PrefixAdderTest"）。
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

# 用 surfer 打开波形（走 nixGL 包装）。
[group('utility')]
wave path:
    @nix run --impure github:nix-community/nixGL#nixGLIntel -- surfer {{ path }}

# ---- FPGA (lxb_artix7 / Vivado) ----
# 板级流程住在自己的模块里，和实现它的脚本放在一起（用 `just --list fpga` 列出）。

# 板子的 RTL、Vivado 工程、比特流与 ILA 抓取 —— `just fpga dump|project|build|program|capture`。
mod fpga 'nzea_fpga/justfile'

# ---- board tools (probe/) ----
# 工具箱只和已编程的板子打交道，不依赖核心源码。两个 recipe 都是纯透传：
# 子命令与它们的默认值住在工具自己的 `--help` 里，这里不再存第二份。

# 和已编程的板子通信：probe_cli 的 load / reset / memread / monitor 子命令。
[group('probe')]
probe *ARGS:
    @cargo run --release --quiet --manifest-path probe/Cargo.toml -p probe_cli -- {{ ARGS }}

# 把测试程序汇编成 tile boot hex（`asm2hex --list` 列出内建程序）。
[group('probe')]
asm2hex *ARGS:
    @cargo run --release --quiet --manifest-path probe/Cargo.toml -p asm2hex -- {{ ARGS }}
