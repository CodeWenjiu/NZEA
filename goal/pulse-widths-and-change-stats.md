# Pulse：信号位宽与变化统计（功能计划）

Status: **已实现**（2026-09-06）——两个原语都已落地：
`signal --json` 携带 `width`/`vector`，`property` 增加了值级 `change(sig)` 内建函数，以及无时钟波形（ILA 导出）的自动采样表周期回退。

## 背景

在修复 ILA 采集 → `probe vcd-rename` 映射时，在写重命名映射表之前必须先回答两个问题：

1. **元数据**：每个导出信号的精确 `$var` 引用（包括宽度如 `[16:0]`）是什么？`pulse signal` 返回不带宽度的纯名字列表，不足以精确匹配 Vivado 折叠后的 net 名。
2. **相关性**：两个 1-bit 变量是不是同一个 net（完全相同的变化集）？一个信号的变化集是不是另一个的子集？一个信号在窗口内翻转多少次？分辨（probe4 被折叠成常量、`addr_1`/`addr` 是高位地址位、与 `addr != 0` 同步）来自全 trace 的变化集 diff——pulse 没有 `change` 命令，`property` 只报告事件时刻，所以这个分析退化成一次性的 Python 脚本。

## 目标

### 1. 带宽度的输出

- `signal` 在 `--json` 里包含每个信号的位宽（文本输出可选），如 `{"name":"ram_req_addr","width":17,"vector":true}`。
- `value` 采样已经渲染向量了；保留它，但让 `signal` 显示宽度，这样"元数据问题"只用 pulse 就能回答。

### 2. 变化统计

添加一种对窗口内信号变化计数/定位的方式，例如：

- `property --eval "change ram_req_addr"`（或专门的 `stat`/`changes` 命令）返回变化数 + 变化时间戳。
- 像其他命令一样接受 `--cycles FROM-TO`、`--max`、`--json`。

## 目标用例（验收）

下次 ILA 导出需要重命名映射时，整个分类必须只用 pulse 完成：

1. `pulse signal --scope dut --json` → 宽度 + 引用，构建映射键。
2. 每个候选信号 `pulse property --eval "change a" / "change b"` → 计数；变化集相等（同一 net）通过比较时间戳、或用 `a == b` 稳态查询（`!(a ^ b)` 对所有采样为真 = 同一 net）判断。
3. 常量折叠检测：只有 1 次变化（只有初始值）的信号是折叠常量 → 归类为垃圾/`probeN` 回退。

## 非目标

- 不做波形 diff 或全集代数 UI；上面两个原语（宽度 + 变化统计）是消除 Python 回退的最小集合。
