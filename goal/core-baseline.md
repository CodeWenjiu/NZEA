# 核心基线：最简 in-order 核（MinCore）

Status: **进行中（MinCore 已实现并通过冒烟 TB）** — nzea_core 重构第一步：最简基线已落地，三段合同进入验证阶段。

## 目标形态（三段）

```
前端 = 取指 + 译码（+BP 插件，基线没有）
中端 = 分发/重排 + 提交（顺序端）    ← 多态点：in-order 中端 vs OoO 中端
后端 = 执行 + 写回                    ← 两端共享（复用 IntegerExecutionCluster / MemUnit）
```

- 合同：前端→中端 `DecodedInst`（架构级）；中端→后端 `IssueUop`（就绪操作数）；
  后端→中端 `Writeback(dest+data)`；中端→前端 `Redirect`。
- in-order 是同一合同的退化实现：ARF 当 PRF，无 RAT/ROB，提交=顺序完成。
- 微架构选择 = 原子类型（EngineKind），不是布尔参数矩阵。

## 基线定义（MinCore）

- 顺序单发射 in-order；接口与现有 `Core` 一致：**ibus / dbus / commit_msg**，可直接替换接入 tile。
- 取指永远 `pc+4`（always-not-taken 推测），分支在 BRU 解析后 flush + redirect，无 BPU。
- 数据冒险：**冻结式单飞行 + WB 级一级前递**。同一时刻最多一条指令在途，
  下一条在提交拍发射，操作数缺失由 wb 总线前递覆盖（无需多级 bypass / load-use 检测）。
- CSR 写只发生在提交拍（EX 只算数据），flush 不污染架构状态 —— 与现有核
  "CSR updates only on ROB commit" 同一原则。
- 异常/中断：**不做**（后续挂载项）。验证范围 = 普通程序 + memtest。
- 可选：`sim` 统计（沿用 StatsRegs 模式）先不加。

## 实施与验证步骤

1. ✅ `MinCore` 实现：`nzea_core/src/min/`（MinIfu/MinIdu/MinEx/MinCore），复用
   `IntegerExecutionCluster`（含 MUL/DIV/NNU）、`MemUnit`、译码表、CSR 文件。
   - 架构寄存器文件（ARF）在 EX 级：发射后读取（单飞行保证前一条已提交，无 hazard 检测、无 bypass）
   - CSR 写只发生在提交拍
2. ✅ Chisel 冒烟 TB（`MinCoreTest`）：9 条提交序列覆盖前递链、store/load、
   load 后使用、分支跳过（flush+redirect）、ecall。
3. ⏳ 接入 tile 跑 memtest（替换前先确认接口宽度与现有公式一致——ibus/dbus 直接复用 IbusUser/MemUnit 保证一致）。
4. ⏳ difftest 子集（后续）。

## 挂载顺序（每步正交、独立验证）

1. 基线（无 BP in-order）→ 验证三段合同
2. 挂 BP：前端换插件（PHT/BTB/RAS 返回取指器）——中/后端不动
3. 挂 OoO 中端：RAT+PRF+ROB+IQ 收拢版（现有乱序部分原样搬入）——前/后端不动
4. 向量后端：后端扩展——前/中端不动

## 待定问题（不做结论，写问题）

- in-order 中端是否需要一个极小退休缓冲来承载将来的精确异常（基线先
  "完成即提交"）。若做，它与 OoO 的 ROB 是否共享同一合同语义？
- store 完成语义：基线等 resp 才提交（保证 MMIO store 完成）。OoO 的
  提交/访存顺序语义不同，合同上"store 完成"如何统一表达？