#import "@preview/cuti:0.2.1": show-cn-fakebold

#show: show-cn-fakebold
#set text(font: "Sarasa Nerd") // 小四号宋体（中文），Times New Roman（英文）

= 这几周的工作
- 写文档（这个工作真的很繁重）
- 拍视频，剪视频——由我的组员负责完成
- 调频，目前在赛事要求的平台上面，将cpu频率提升到了150MHz
    - 现在卡在那里？
    卡在了例程提供的外设桥和存储设备上
    这其实还挺正常的
    但是由于初赛限制了总线，所以没办法做CDC(clock domain crossing，即让cpu和存储设备跑在不同频率)
    所以初赛就先止步在这个性能

= 现在在做什么？
- 实现异常
- 改进调试设备
- 写其他课程的实验和大作业(期末将至)
