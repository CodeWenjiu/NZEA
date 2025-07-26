author: @onlywhat

通过apb_peripheral模块将APB总线转换为外设控制信号

### mmio
| 地址范围                   | Peripheral       |
|---------------------------|-----------------|
| 0x2000_0000 - 0x2000_0000 | LED             |
| 0x2000_0004 - 0x2000_0008 | 按键             |
| 0x2000_000c - 0x2000_000c | 数码管            |

- 复位——Prst置高
- 写入——Pck上升沿出发，同时Psel&&Penable&&Pwrite，将Pwdata根据不同的Paddr写入外设寄存器，
- 读取——Pck上升沿出发，同时Psel&&Penable&&！Pwrite，将外设寄存器数值根据不同的Paddr写入Prdata
