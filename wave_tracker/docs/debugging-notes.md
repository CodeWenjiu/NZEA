# 与 wave_tracker 相关的 RTL 调试要点

本文档只保留**可复用的结论与排查方向**，不记录单次仿真的逐周期表。旧版个案报告（addi/PR2/t=108 等）已从仓库移除，需要时可查 git 历史。

## Issue Queue 与 PRF

- IQ 侧 `rs1_ready` / `rs2_ready` 若只在入队时采样，而生产者稍后才在 PRF 就绪，可能出现 **PRF 已就绪但 IQ 仍显示未就绪** 的现象；工具里有 **PRF–IQ mismatch** 类扫描用于对照 `bank_ready` 与 IQ 位。  
- 若 IQ 内多条指令互相等待操作数、当周期又无 FU 写回，**bypass persist** 可能无法推进，表现为「全堵在 IQ」；根本修复通常回到前端：是否应在入队前保证操作数就绪（operand stall）等设计选择。  

## ROB / IQ / LSQ 一致性

- 对 load/store，需确认 **dispatch 时** `ls_alloc` 等与 ROB/IQ 入队是否一致；若 ISU 将 LSU 误标为 ALU，`ls_alloc` 可能不拉起而后续仍走 AGU，易导致 **LSQ 与指令语义不一致**。`--dispatch-lsq` 等子命令用于对照 ROB/IQ/LS 相关信号。  
- 曾出现 **IQ `count` 与 `valids` 不同步** 导致「看似满、又无可发射条目」的死锁；修复思路之一是让 `count` 与 `valids` 同源（例如由 `valids` 计数推导）。`--deadlock-tail` 在检测到 count/valids 不一致时会标注并尽量用 entry 侧信息辅助展示。  

## FreeList / RMT

- **同一物理寄存器既出现在 FreeList 可分配区间又出现在 RMT 映射** 违反常见不变式；`--bug-scan` 用于在波形里搜「PR 同时在 buf 与 `table_1(sp)`」这类模式。注意：若实现上只检查 `buf_*` 原始槽位而未按 head/tail 过滤，可能把环形缓冲区外的陈旧值也算进去，解释波形时要结合 RTL 语义。  

## 工具维护

新增分析子命令时：在 `cli/args.rs` 增加字段，在 `cli/run.rs` 分支调用 `analysis` 中对应函数，并更新 **`wave_tracker --help`** 所依赖的 clap 文档字符串；本目录文档只需保持「指向 `--help`」与高层结构说明即可，无需复制完整选项表。
