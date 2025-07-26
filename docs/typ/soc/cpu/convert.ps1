# 获取当前脚本所在的目录
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

# 定义要搜索的文件扩展名
$fileExtension = ".typ"

# 定义输出文件名
$outputFileName = "merged.docx"

# 递归搜索当前目录及其子目录中指定扩展名的文件
$files = Get-ChildItem -Path "." -Recurse -Filter "*$fileExtension" | Sort-Object FullName

# 构建 Pandoc 转换命令，将所有找到的.typ 文件作为输入
$pandocCommand = "pandoc $files -o $outputFileName"

# 执行 Pandoc 命令
$files | ForEach-Object {$_.FullName}
& $pandocCommand