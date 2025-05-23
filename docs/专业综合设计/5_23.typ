#import "@preview/cuti:0.2.1": show-cn-fakebold

#show: show-cn-fakebold
#set text(font: "Sarasa Nerd") // 小四号宋体（中文），Times New Roman（英文）

= 这几周的工作
- 写文档（这个工作真的很繁重）
- 拍视频，剪视频——由我的组员负责完成
- 调频，目前在赛事要求的平台上面，将cpu频率提升到了150MHz
    - 现在卡在那里？
    卡在了例程提供的外设桥和存储设备上\
    这其实还挺正常的\
    但是由于初赛限制了总线，所以没办法做CDC(clock domain crossing，即让cpu和存储设备跑在不同频率)\
    所以初赛就先止步在这个性能

= 现在在做什么？
- 实现异常
    #table(
        columns: (auto, auto, auto),
        [异常号], [异常描述], [目前状态],
        [0], [Instruction address misaligned], [❌],
        [1], [Instruction access fault], [❌],
        [2], [Illegal Instruction], [❌],
        [3], [Breakpoint], [?],
        [4], [Load address misaligned], [❌],
        [5], [Load access fault], [❌],
        [6], [Store/AMO address misaligned], [❌],
        [7], [Store/AMO access fault], [❌],
        [8], [Environment call from U-mode], [❌],
        [9], [Environment call from S-mode], [❌],
        [11], [Environment call from M-mode], [?],
        [12], [Instruction page fault], [❌],
        [13], [Load page fault], [❌],
        [15], [Store/AMO page fault], [❌],
    )
- 改进调试设备(Emu)
- 写其他课程的实验和大作业(期末将至)
