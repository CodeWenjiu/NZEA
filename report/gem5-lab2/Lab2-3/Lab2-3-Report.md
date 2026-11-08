# Lab 2.3 报告：NMRU 替换策略与区分负载

## 1. NMRU 的语义

NMRU（Not Most Recently Used）每次替换时**保护唯一一条最近使用的行**，在其余候选中随机选择牺牲者：

- `reset`（插入新行）：标记为有效，记录当前 tick；
- `touch`（访问命中）：更新 last-touch tick，即成为 MRU；
- `invalidate`（失效）：标记为无效，优先被淘汰；
- `getVictim`：先找无效行；否则找 last-touch 最大的行（MRU）并排除，在剩下的行里均匀随机选一个。

与 LRU 的区别只在 `getVictim`：LRU 选最久未用的行，NMRU 排除 MRU 后随机选。因此在**2 路**缓存里两者等价（除 MRU 外只剩一个候选），差异只在 4 路及以上的缓存中体现。本次实验目标是 8 路的 L2。

## 2. 实现与接入

### 2.1 C++ 替换逻辑

新增 `src/mem/cache/replacement_policies/nmru_rp.hh` / `nmru_rp.cc`，以 `lru_rp.*` 为模板：

- 替换数据为 `{ Tick lastTouchTick; bool valid; }`；
- 随机源使用 `gem5::Random::genRandom()`，与 `random_rp` 一致；
- `getVictim` 先返回任何无效候选，再求 MRU，最后在 `candidates \ {MRU}` 上随机。

### 2.2 Python 类与 SCons 注册

在 `ReplacementPolicies.py` 末尾添加：

```python
class NMRURP(BaseReplacementPolicy):
    type = "NMRURP"
    cxx_class = "gem5::replacement_policy::NMRU"
    cxx_header = "mem/cache/replacement_policies/nmru_rp.hh"
```

并在 `SConscript` 的 `SimObject(...)` 列表加入 `'NMRURP'`、`Source('nmru_rp.cc')`。

### 2.3 配置接入

`configs/deprecated/example/se.py` 原版没有替换策略选项。为了按 Lab 2.2 的初始配置直接跑：

- `configs/common/Options.py` 增加 `--l2-replacement-policy` 和 `--l1d-replacement-policy`（`choices=ObjectList.rp_list.get_names()`，默认 `None`，不影响已有脚本）；
- `configs/common/CacheConfig.py` 的 `_get_cache_opts` 在对应选项非空时把 `replacement_policy` 传给缓存 SimObject。

修改后 `--list-rp-types` 能看到 `NMRURP`。完整改动见附件 `nmru.patch`、`nmru_rp.hh`、`nmru_rp.cc`。

## 3. 区分负载 `cache_test.cpp`

### 3.1 设计

`cache_test.cpp` 对一个**略大于 L2 的工作集做循环扫描**：每 64 B 行访问一个字节，共 5120 行（320 KiB），循环 50 次，共 256,000 次访问。

参数为 Lab 2.2 的初始配置：O3CPU、1 GHz、DDR3-1600、L1I 32 KiB/2 路、L1D 64 KiB/2 路、L2 256 KiB/8 路。

### 3.2 为什么能区分

5120 行分布在 L2 的 512 个组里，平均每组 10 行，而每组只有 8 路：

- **LRU**：组满后每次替换都淘汰最久未用的行。循环扫描中「最久未用」恰好是下一轮最先要访问的行，于是每次访问都把它挤走，命中率接近 0。
- **NMRU**：MRU 被保护，牺牲者从其余 7 行中随机选。下一轮要访问的行有约 6/7 的概率逃过替换，因此能保留一部分行。

这与讲义给的「W+1 个块竞争 W 路」是同一个思想，只是放在整个 L2 上做，不依赖某个具体的组索引。

### 3.3 一个实现中发现的问题

最初尝试用「虚拟地址等步长、所有块落在同一 L2 组」来构造冲突，但 gem5 SE 模式的 cache 看到的是**物理地址**，进程页到物理页的映射不受程序控制，虚拟步长无法保证同组。最终改用循环扫描整个略大于 L2 的工作集，结论不再依赖具体的地址映射。

## 4. 结果

每组 50 次循环、256,000 次访问，其余配置完全相同：

| 策略 | L2 命中 | L2 缺失 | L2 缺失率 | simSeconds (ms) | hostSeconds (s) |
|---|---|---|---|---|---|
| LRU | 212 | 264,057 | 99.9% | 7.269 | 13.11 |
| NMRU | 150,826 | 113,447 | 42.9% | 4.425 | 10.66 |

- NMRU 把 L2 缺失率从 99.9% 降到 42.9%；
- `simSeconds` 从 7.269 ms 降到 4.425 ms，**快 39.1%**；
- 两条指令数相同（1,919,200），差异完全来自缓存命中率。

实验要求「任意两种替换策略产生至少 2% 的性能差异」，本组达到 **39.1%**；拓展目标 10% 也满足。

## 5. 为什么不是 LRU vs Random

Random 也可能保留下一轮要用的行，但在 8 路组里随机淘汰时，MRU 本身也有 1/8 的概率被换掉；NMRU 明确保护 MRU，语义更贴近「只保护最近使用的那一行」。本实验按讲义要求实现并对比 NMRU 与 LRU。

## 6. 复现命令

```sh
scons build/X86/gem5.opt -j 18

./build/X86/gem5.opt -d lab2/lab23/LRURP \
  configs/deprecated/example/se.py \
  --cpu-type=O3CPU --caches --l2cache \
  --l1i_size=32KiB --l1i_assoc=2 --l1d_size=64KiB --l1d_assoc=2 \
  --l2_size=256KiB --l2_assoc=8 --l2-replacement-policy=LRURP \
  --cpu-clock=1GHz --mem-type=DDR3_1600_8x8 \
  --cmd=/workspace/gem5-lab/lab2/cache_test --options=50

./build/X86/gem5.opt -d lab2/lab23/NMRURP \
  ... --l2-replacement-policy=NMRURP ...
```

## 7. 附件

- `nmru_rp.hh`、`nmru_rp.cc`：NMRU 的 C++ 实现
- `nmru.patch`：`ReplacementPolicies.py`、`SConscript`、`Options.py`、`CacheConfig.py` 的接入改动
- `cache_test.cpp`：区分负载
- `LRU_stats.txt`、`NMRU_stats.txt`：两种策略的完整统计
