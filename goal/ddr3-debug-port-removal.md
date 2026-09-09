# Ddr3AdapterDebug 端口移除（清理计划）

Status: **计划中** — 暂缓，直到板上 ILA 调试收敛。

## 背景

`Ddr3Adapter` 暴露了一个很宽的观测端口：

```scala
// nzea_device/src/ddr3/Ddr3Adapter.scala:104
val debug = Output(new Ddr3AdapterDebug)
```

`Ddr3AdapterDebug` 携带约 20 个字段（`reqPending`、`respValid`、`reqFire`、`flush`、`uiState`、`uiReqGoSeen`、`uiDoneReq`、`uiAppEn`、`uiWen`、`reqWen`、`wdfWren`，以及同步器链和捕获地址探针）。它是追查 DDR3 接入 bug（写 drain 下溢、行移位地址映射、wdf mask 反相、ui 域复位覆盖）过程中逐步加进来的。

这个端口**不是**适配器功能契约的一部分：

- 普通使用者（如 xsim 仿真包装）不接它。
- 唯一真正的读取方是 `LxbArtix7Core` 里的板上 ILA 仪表化（probe4–probe11、probe14、probe16、probe17）。
- 每个字段都是内部寄存器/线的直接引出，功能上零成本，但永久加宽了模块接口。

## 为什么现在还不能拆

当前 22-probe ILA 采集（`IlaProbes` + `ila_capture.tcl` + `ila_probes.map`）是诊断板上残余 DDR3 间歇性问题的主要工具（比如促成 `wrDrainCycles` 的延迟读回陈旧问题）。22 个探针里有 11 个直接来自 `bridge.io.debug.*`。现在拆掉会弄瞎 ILA 流程。

## 拆除计划（当 ILA 调试收敛时）

触发条件：DDR3 memtest 在板上长跑有余量地通过，且没有需要适配器内部可见性的未关闭 bug。

1. 从 `Ddr3Adapter.io` 删除 `val debug = Output(new Ddr3AdapterDebug)`，并删除 `Ddr3AdapterDebug` bundle 类。
2. 删除适配器体内的 `debug` 引出（它们是对内部信号的纯 `:=` 读取，没有逻辑依赖它们）。
3. 在 `LxbArtix7Core`：删除 probe4–11、probe14、probe16、probe17 接线；相应重编或收缩 `IlaProbes`（探针数量是 `IlaProbes.widths` 里的单一事实源）。
4. 更新 `FpgaElaborate.IlaCapture.probeMap` 和 `IlaProbes.scala` 的宽度注释到新探针集。
5. 更新 `Ddr3AdapterTest` 的顶层（`nzea_device/test/src/ddr3/`），它们也在观测 IO 里例化了 `Ddr3AdapterDebug`。
6. `Ddr3SubsystemDebug`（ui_clk_sync_rst / ui_rst / calib_done）更小，如果还想保留板级 MIG 状态观测就留着；拆除时再决定。

## 备注

- debug 端口刻意放在设备层（不在 tile），让 tile 保持 ILA 无关（抽象泄漏规则：板级关注点不得渗入 `nzea_tile`）。
- 拆掉后，ILA 仍能观测 tile 边界信号（`extRamBus(1)` 的 req/resp）加上 `ddr.io.mig.*` 的 MIG 侧引脚，对总线协议级调试足够了。
