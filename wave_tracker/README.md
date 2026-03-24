# wave_tracker

用 [wellen](https://crates.io/crates/wellen) 直接读 FST/VCD，配合命令行对 nzea RTL 波形做快速查看与专项分析（无需 fst2vcd）。

## 构建与运行

```bash
cd wave_tracker
cargo build --release
cargo run --release -- --help
```

默认波形路径：相对 `CARGO_MANIFEST_DIR` 解析到 `chip-dev/remu/target/trace.fst`（与 nzea 同级的 `remu` 工程）。可用 `-f` 覆盖。

## 文档

| 文件 | 内容 |
|------|------|
| [docs/wave_tracker.md](docs/wave_tracker.md) | 命令行说明、crate 结构、常用信号过滤提示 |
| [docs/debugging-notes.md](docs/debugging-notes.md) | 与波形工具相关的 RTL 调试要点（浓缩，非个案报告） |

个案级逐周期报告已移除；若需历史细节可在 git 历史中查找已删除的 `*_REPORT.md` / `DEADLOCK_*.md`。

## 快速示例

```bash
# 列出信号（默认 trace.fst）
cargo run -- -l

cargo run -- -l -g "flush"
cargo run -- -t 100 -g "freeList"

# 时间窗内 grep 到的信号变化
cargo run -- --scan 0 --scan-end 100000 -g "next_pc" --filter-value "8000611"

cargo run -- -f /path/to/trace.fst -l
```

完整选项以 `cargo run -- --help` 为准。
