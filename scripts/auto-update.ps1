<#
PhoenixScout 自动更新与守护。由计划任务 "PhoenixScout-AutoUpdate" 每 3 分钟以普通用户身份运行，不需要管理员。

每次运行：
  1. 确认守护任务 PhoenixScout-Watchdog 已注册（服务停止/卡死的检测与拉起由它每 2 分钟负责）。
  2. 更新：GitHub main 有新提交时，在源码目录拉取。
     只改了文档（docs/ 或 .md）：只复制文件，不构建、不重启。
     否则构建（含全部测试），成功后等每日扫描不在运行时再平滑停机、换上新 jar 和文件、启动；
     启动后健康检查通过且启动检查完成才算更新成功，否则自动回滚到上一版（2 小时后再试这个版本）。
     构建失败则继续跑旧版，同一提交只提醒一次。
  另外每天确认一次：接通电源时电脑不睡眠、不休眠（睡眠会让服务停掉）。
  3. 结果通过 Telegram 通知（从本机 config\application-local.yml 读取，不输出其中内容）。

从不改动：config\application-local.yml、data、logs。
服务本身由计划任务 "PhoenixScout-Service" 运行 scripts\run-service.cmd。
#>
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$AppDir  = Split-Path -Parent $PSScriptRoot
$SrcDir  = 'D:\SOFT\tools\phoenixscout-src'
$RepoUrl = 'https://github.com/leo14881-eng/PhoenixScout.git'
$Branch  = 'main'
$Port    = 8100
$Base    = "http://127.0.0.1:$Port"
$Bin     = Join-Path $AppDir 'app'
$Jar     = Join-Path $Bin 'phoenixscout.jar'
$PrevJar = Join-Path $Bin 'phoenixscout.prev.jar'
$Version = Join-Path $Bin 'version.txt'
$Failed  = Join-Path $Bin 'failed-commit.txt'
$Health  = Join-Path $Bin 'health-failures.txt'
$Denied  = Join-Path $Bin 'stop-denied.txt'
$MonFailed = Join-Path $Bin 'startup-failed-commit.txt'
$Updating = Join-Path $Bin 'updating.flag'
# 迁移到云服务器后本机停用：守护、开机启动、自动更新都不再拉起本机服务（自动更新只同步文件、维护服务器）
$Disabled = Join-Path $Bin 'disabled.flag'
$LogFile = Join-Path $AppDir 'logs\auto-update.log'

New-Item -ItemType Directory -Force -Path $Bin, (Join-Path $AppDir 'logs') | Out-Null

function Write-Log($msg) {
    $line = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
}

function Read-Text($path) {
    if (Test-Path $path) { return ([string](Get-Content $path -Raw)).Trim() }
    return ''
}

function Send-Telegram($text) {
    try {
        $cfg = Join-Path $AppDir 'config\application-local.yml'
        if (-not (Test-Path $cfg)) { return }
        $raw = Get-Content $cfg -Raw -Encoding UTF8
        $token = [regex]::Match($raw, '(?m)^\s*bot-token:\s*["'']?([^"''\s#]+)').Groups[1].Value
        $chat = [regex]::Match($raw, '(?m)^\s*chat-id:\s*["'']?([^"''\s#]+)').Groups[1].Value
        if (-not $token -or -not $chat) { return }
        $body = @{ chat_id = $chat; text = $text } | ConvertTo-Json -Compress
        Invoke-RestMethod -Method Post -Uri "https://api.telegram.org/bot$token/sendMessage" `
            -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 20 | Out-Null
    } catch {
        Write-Log ('Telegram 发送失败：' + ($_.Exception.Message -replace 'bot[0-9]+:[A-Za-z0-9_-]+', 'bot***'))
    }
}

function Test-Healthy {
    try {
        $r = Invoke-WebRequest -Uri "$Base/api/status" -UseBasicParsing -TimeoutSec 15
        return $r.StatusCode -eq 200
    } catch { return $false }
}

function Get-ServicePid {
    $c = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($c) { return [int]$c.OwningProcess }
    return $null
}

# 只结束我们自己的服务：java 进程且命令行含 phoenixscout.jar（8100 端口可能被别的程序占用）
function Test-OurProcess($procId) {
    try {
        $p = Get-CimInstance Win32_Process -Filter "ProcessId=$procId" -ErrorAction Stop
        return ($p.Name -match '^javaw?\.exe$') -and ($p.CommandLine -match 'phoenixscout\.jar')
    } catch { return $false }
}

function Start-PhoenixScout {
    if (-not (Test-Path $Jar)) {
        $built = Join-Path $AppDir 'target\phoenixscout.jar'
        if (Test-Path $built) { Copy-Item $built $Jar -Force } else { throw '没有可运行的 jar' }
    }
    & schtasks.exe /Run /TN 'PhoenixScout-Service' | Out-Null
    Write-Log '已通过计划任务启动服务'
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Seconds 5
        if (Test-Healthy) {
            Set-Content $Health '0'
            Write-Log "服务运行正常 PID=$(Get-ServicePid)"
            return $true
        }
    }
    return $false
}

function Stop-PhoenixScout {
    $procId = Get-ServicePid
    if (-not $procId) { return $true }
    try {
        Invoke-WebRequest -Uri "$Base/api/admin/shutdown" -Method Post -Headers @{ 'X-PhoenixScout-Admin' = 'shutdown' } `
            -UseBasicParsing -TimeoutSec 10 | Out-Null
        Write-Log "已请求平滑停机 PID=$procId"
    } catch {
        Write-Log '平滑停机请求没有响应，等待后结束进程'
    }
    for ($i = 0; $i -lt 40; $i++) {
        if (-not (Get-Process -Id $procId -ErrorAction SilentlyContinue)) { return $true }
        Start-Sleep -Seconds 3
    }
    if (-not (Test-OurProcess $procId)) {
        Write-Log "端口 $Port 上的进程 PID=$procId 不是 PhoenixScout，不结束它"
        return $false
    }
    try {
        Stop-Process -Id $procId -Force
        Start-Sleep -Seconds 3
        return $true
    } catch {
        Write-Log "无法结束进程 PID=${procId}：$($_.Exception.Message)"
        return $false
    }
}

# 每日扫描正在运行时不更新（返回 $false，下一轮 3 分钟后再看）
function Test-QuietNow {
    try {
        $h = Invoke-RestMethod -Uri "$Base/api/health" -TimeoutSec 15
        return -not [bool]$h.scanRunning
    } catch { return $true }
}

function Get-UtcTime($s) {
    if (-not $s) { return $null }
    try { return [DateTimeOffset]::Parse([string]$s).UtcDateTime } catch { return $null }
}

# 新版本启动后，等到健康检查通过且启动检查（导入归档、校验快照）完成，最多约 10 分钟
function Wait-StartupOk($sinceUtc) {
    for ($i = 0; $i -lt 120; $i++) {
        Start-Sleep -Seconds 5
        try {
            $h = Invoke-RestMethod -Uri "$Base/api/health" -TimeoutSec 15
            if ($h.ok -and $h.startupDone) { return $true }
        } catch { }
    }
    return $false
}

function Report-StopDenied($what) {
    if (Test-Path $Denied) { return }
    Send-Telegram "❗ $what，但旧进程 PID=$(Get-ServicePid) 是管理员权限启动的，自动更新无法结束它。请在任务管理器里结束这个 java.exe，之后会自动接管。"
    Set-Content $Denied (Get-Date)
}

# git 输出按 UTF-8 读取。直接用 & git.exe 捕获时，Windows PowerShell 会按系统代码页（中文系统是 GBK）解码，
# 提交说明里的中文会变成乱码。
$GitExe = if ($env:PHOENIXSCOUT_GIT) { $env:PHOENIXSCOUT_GIT } else { 'git.exe' }
function Invoke-Git([string[]]$GitArgs) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $GitExe
    $quoted = @('-C', $SrcDir, '-c', 'i18n.logOutputEncoding=UTF-8', '-c', 'core.quotePath=false') + $GitArgs | ForEach-Object {
        if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
    }
    $psi.Arguments = $quoted -join ' '
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = New-Object System.Text.UTF8Encoding($false)
    $psi.StandardErrorEncoding = New-Object System.Text.UTF8Encoding($false)
    $p = [System.Diagnostics.Process]::Start($psi)
    $errTask = $p.StandardError.ReadToEndAsync()
    $output = $p.StandardOutput.ReadToEnd()
    $p.WaitForExit()
    if ($p.ExitCode -ne 0) { throw "git $($GitArgs -join ' ') 失败：$output $($errTask.Result)" }
    if ($output.Contains([char]0xFFFD)) { Write-Log "git 输出含无法解码的字符：git $($GitArgs -join ' ')" }
    return $output.Trim()
}

# 接电源时不睡眠、不休眠：电脑睡着服务就停了。普通用户可以改当前电源计划；每天检查一次（防止被改回去），失败只提醒一次。
# GitHub Actions 在 Windows 上跑测试的结果（公开仓库，匿名接口，每小时 60 次额度，只在有新提交时才查）。
# 返回 success / failure / pending / unknown（查不到或这个提交没有 CI 记录）。
function Get-CiState($sha) {
    try {
        $m = [regex]::Match($RepoUrl, 'github\.com[/:]([^/]+)/([^/.]+)')
        $api = "https://api.github.com/repos/$($m.Groups[1].Value)/$($m.Groups[2].Value)/commits/$sha/check-runs"
        $r = Invoke-RestMethod -Uri $api -Headers @{ 'User-Agent' = 'PhoenixScout-AutoUpdate'; 'Accept' = 'application/vnd.github+json' } -TimeoutSec 20
        $win = @($r.check_runs | Where-Object { $_.name -like '*windows*' })
        if ($win.Count -eq 0) { return 'unknown' }
        if (@($win | Where-Object { $_.status -ne 'completed' }).Count -gt 0) { return 'pending' }
        if (@($win | Where-Object { $_.conclusion -ne 'success' }).Count -gt 0) { return 'failure' }
        return 'success'
    } catch {
        return 'unknown'
    }
}

function Set-NoSleep {
    $ErrorActionPreference = 'Continue'   # powercfg 写 stderr 时不要直接抛出
    $marker = Join-Path $Bin 'nosleep.txt'
    $today = Get-Date -Format 'yyyy-MM-dd'
    $last = Read-Text $marker
    # v2：加了"合上笔记本盖子不睡眠"，旧标记不算数，升级后当天重新设置一次
    if ($last -eq "v2-$today" -or $last -eq "failed-$today") { return }
    $failed = @()
    foreach ($setting in 'standby-timeout-ac', 'hibernate-timeout-ac') {
        & powercfg.exe /change $setting 0 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { $failed += $setting }
    }
    # 合盖动作：接电源时 0 = 不采取任何操作；用电池时 1 = 睡眠（放进包里不发热耗电）。设置后重新激活当前电源计划才生效
    & powercfg.exe /setacvalueindex SCHEME_CURRENT SUB_BUTTONS LIDACTION 0 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { $failed += 'lid-ac' }
    & powercfg.exe /setdcvalueindex SCHEME_CURRENT SUB_BUTTONS LIDACTION 1 2>&1 | Out-Null
    & powercfg.exe /setactive SCHEME_CURRENT 2>&1 | Out-Null
    if ($failed.Count -eq 0) {
        if ($last -notlike 'v2-*') {
            Write-Log '已设置接电源时不睡眠、不休眠，合上盖子不睡眠'
            Send-Telegram '✅ 已设置：接着电源时，合上笔记本盖子或空闲都不再睡眠，PhoenixScout 会一直运行（用电池合盖仍会睡眠）。'
        }
        Set-Content $marker "v2-$today"
    } else {
        Write-Log "设置不睡眠失败：$($failed -join ', ')"
        if ($last -notlike 'failed-*') {
            Send-Telegram "⚠️ 无法关闭电脑自动睡眠（$($failed -join ', ')），电脑睡眠时 PhoenixScout 会停止。请在 控制面板 > 电源选项 > 选择关闭盖子的功能 里改为“不采取任何操作”，并把接通电源时的睡眠改为“从不”。"
        }
        Set-Content $marker "failed-$today"
    }
}

# ---------- 服务器桥：本机通过 SSH 维护云服务器（用户 2026-10-18 同意；只执行下面固定的维护动作，不开放任意命令） ----------
# 服务器上还跑着 TrendPilot，这里只碰 /opt/phoenixscout 和 phoenixscout.service。
# 登录密钥用 TrendPilot 已在服务器上登记的 root 密钥（只引用路径，不复制、不输出）；服务器 IP 在 app\server-ip.txt。
# app\server.request 写一个动作名，执行后删除请求文件，输出写 logs\server.log：
#   check          检查服务器能否访问 CoinGecko / DefiLlama / Telegram / GitHub
#   repo           检查服务器能否读取仓库（公开 → HTTPS；私有 → 生成只读 deploy key 并在日志里给出公钥）
#   install        在服务器上安装或重装（上传本机 scripts\linux 执行 install.sh）
#   status         服务器上服务状态、版本、最近日志
#   migrate-fresh  迁移到服务器、从零开始：平滑停掉本机服务并停用，只上传配置和研究文件（本机数据原样保留），
#                  启动服务器上的服务，等它完成一次每日扫描才算成功；任何一步失败都恢复本机运行
#   fallback       切回本机：停用服务器上的服务，启用并启动本机服务
# 迁移成功后（app\server-active.txt 存在），每次运行顺带同步：本机新写的研究文件 → 服务器；服务器的日报和候选 → 本机 data\reports
# （每天 10:20 的研究任务只能读写本机文件夹，靠这个同步和服务器配合）。
$ServerActive = Join-Path $Bin 'server-active.txt'
$ServerKey = 'D:\SOFT\java\TrendPilot\ssh\id_ed25519'
$ServerHome = '/opt/phoenixscout'
$ServerScripts = @{
    check    = 'bash /tmp/ps-boot/linux/install.sh --check'
    repo     = 'bash /tmp/ps-boot/linux/install.sh --repo'
    install  = 'bash /tmp/ps-boot/linux/install.sh'
    status   = "bash $ServerHome/scripts/linux/status.sh"
    'migrate-fresh' = "bash $ServerHome/scripts/linux/migrate-in.sh"
    fallback = "mkdir -p $ServerHome/app && touch $ServerHome/app/disabled.flag && systemctl stop phoenixscout.service && echo '服务器上的服务已停止并停用'"
}

function Write-ServerLog($msg) {
    Add-Content -Path (Join-Path $AppDir 'logs\server.log') -Value ('{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg) -Encoding UTF8
}

# 运行 ssh / scp（最多 50 分钟），标准输出和错误按 UTF-8 追加到 logs\server.log（$quiet 时只在失败时写），返回退出码
function Invoke-ServerTool($exe, $argLine, $stdinText, [switch]$quiet) {
    $tmpIn = Join-Path $Bin 'server-in.tmp'; $tmpOut = Join-Path $Bin 'server-out.tmp'; $tmpErr = Join-Path $Bin 'server-err.tmp'
    $p = @{ FilePath = $exe; ArgumentList = $argLine; NoNewWindow = $true; PassThru = $true
        RedirectStandardOutput = $tmpOut; RedirectStandardError = $tmpErr }
    if ($stdinText) {
        [IO.File]::WriteAllText($tmpIn, $stdinText + "`n", (New-Object Text.UTF8Encoding $false))
        $p.RedirectStandardInput = $tmpIn
    }
    $proc = Start-Process @p
    $null = $proc.Handle   # Windows PowerShell 5.1：不先取 Handle，进程退出后 ExitCode 读出来是空的
    if (-not $proc.WaitForExit(50 * 60 * 1000)) { try { $proc.Kill() } catch { }; Write-ServerLog '超过 50 分钟，已中止' }
    $code = $proc.ExitCode
    foreach ($f in $tmpOut, $tmpErr) {
        if (Test-Path $f) {
            $text = [IO.File]::ReadAllText($f, [Text.Encoding]::UTF8).TrimEnd()
            if ($text -and (-not $quiet -or $code -ne 0)) { Add-Content -Path (Join-Path $AppDir 'logs\server.log') -Value $text -Encoding UTF8 }
        }
    }
    Remove-Item $tmpIn, $tmpOut, $tmpErr -ErrorAction SilentlyContinue
    return $code
}

# 连接参数；条件不具备返回 $null（原因写 server.log）
function Get-ServerConn {
    $ip = Read-Text (Join-Path $Bin 'server-ip.txt')
    if ($ip -notmatch '^\d{1,3}(\.\d{1,3}){3}$') { Write-ServerLog "服务器 IP 无效：$ip"; return $null }
    $ssh = Join-Path $env:WINDIR 'System32\OpenSSH\ssh.exe'
    $scp = Join-Path $env:WINDIR 'System32\OpenSSH\scp.exe'
    if (-not (Test-Path $ServerKey) -or -not (Test-Path $ssh)) { Write-ServerLog '无法执行：登录密钥或系统自带的 ssh 不存在'; return $null }
    $sshDir = Join-Path $AppDir 'ssh'
    New-Item -ItemType Directory -Force -Path $sshDir | Out-Null
    $opts = "-i `"$ServerKey`" -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o `"UserKnownHostsFile=$(Join-Path $sshDir 'known_hosts')`" -o ConnectTimeout=20 -o ServerAliveInterval=30"
    return @{ Ip = $ip; Ssh = $ssh; Scp = $scp; Opts = $opts }
}

function Invoke-ServerSsh($c, $script, [switch]$quiet) {
    return Invoke-ServerTool $c.Ssh "$($c.Opts) root@$($c.Ip) bash -s" $script -quiet:$quiet
}

# 上传本机文件到服务器目录（一次一批）
function Send-ServerFiles($c, [string[]]$files, $remoteDir, [switch]$quiet) {
    if ($files.Count -eq 0) { return 0 }
    $list = ($files | ForEach-Object { "`"$_`"" }) -join ' '
    return Invoke-ServerTool $c.Scp "$($c.Opts) -q $list root@$($c.Ip):$remoteDir/" $null -quiet:$quiet
}

# 本机研究文件里比上次同步新的（全部为 $all）
function Get-NewResearch([switch]$all) {
    $dir = Join-Path $AppDir 'data\research'
    if (-not (Test-Path $dir)) { return @() }
    $mark = Join-Path $Bin 'research-synced.txt'
    $since = [datetime]::MinValue
    if (-not $all -and (Test-Path $mark)) { $since = (Get-Item $mark).LastWriteTime }
    return @(Get-ChildItem $dir -Filter '*.json' | Where-Object { $_.LastWriteTime -gt $since } | ForEach-Object { $_.FullName })
}

function Sync-Server {
    if (-not (Test-Path $ServerActive) -or -not (Test-Path $Disabled)) { return }
    $c = Get-ServerConn
    if (-not $c) { return }
    # 研究文件 → 服务器（服务每 10 分钟读取；同名同大小的文件服务器不会重复处理）
    $mark = Join-Path $Bin 'research-synced.txt'
    $started = Get-Date
    $new = Get-NewResearch
    if ($new.Count -gt 0) {
        if ((Send-ServerFiles $c $new "$ServerHome/data/research" -quiet) -eq 0) {
            Set-Content $mark $started.ToString('o'); (Get-Item $mark).LastWriteTime = $started
            Write-ServerLog "已上传研究文件 $($new.Count) 个"
        } else {
            Write-ServerLog '上传研究文件失败，下次再试'
        }
    }
    # 服务器的日报和候选 → 本机 data\reports（每 10 分钟一次）
    $rmark = Join-Path $Bin 'reports-synced.txt'
    if (-not (Test-Path $rmark) -or (Get-Item $rmark).LastWriteTime -lt (Get-Date).AddMinutes(-10)) {
        $code = Invoke-ServerTool $c.Scp "$($c.Opts) -q -r root@$($c.Ip):$ServerHome/data/reports `"$(Join-Path $AppDir 'data')`"" $null -quiet
        if ($code -eq 0) { Set-Content $rmark (Get-Date).ToString('o') } else { Write-ServerLog "下载服务器日报失败（退出码 $code）" }
    }
}

function Invoke-ServerRequest {
    $ErrorActionPreference = 'Continue'
    $request = Join-Path $Bin 'server.request'
    if (-not (Test-Path $request)) { return }
    $action = (Read-Text $request).ToLower()
    Remove-Item $request -Force -ErrorAction SilentlyContinue
    if (-not $ServerScripts.ContainsKey($action)) { Write-ServerLog "未知动作：$action"; return }
    $c = Get-ServerConn
    if (-not $c) { return }
    Write-ServerLog "== $action"
    if ($action -in 'check', 'repo', 'install') {
        # 私有仓库时服务器还拉不到代码：先把本机的 scripts\linux 传上去执行
        $ok = (Invoke-ServerSsh $c 'rm -rf /tmp/ps-boot && mkdir -p /tmp/ps-boot') -eq 0
        if ($ok) { $ok = (Invoke-ServerTool $c.Scp "$($c.Opts) -q -r `"$(Join-Path $AppDir 'scripts\linux')`" root@$($c.Ip):/tmp/ps-boot/" $null) -eq 0 }
        if (-not $ok) { Write-ServerLog '上传安装脚本失败'; return }
        $code = Invoke-ServerSsh $c ("sed -i 's/\r$//' /tmp/ps-boot/linux/*.sh && " + $ServerScripts[$action])
        Write-ServerLog "退出码 $code"
    } elseif ($action -eq 'migrate-fresh') {
        # 先平滑停掉本机（数据库写完整）并停用，再传配置和研究文件；任何一步失败都恢复本机运行
        Set-Content $Disabled (Get-Date).ToString('o')
        if (-not (Stop-PhoenixScout)) {
            Remove-Item $Disabled -ErrorAction SilentlyContinue
            Write-ServerLog '本机服务停不下来，取消迁移'
            return
        }
        Write-ServerLog '本机服务已停止并停用'
        $ok = (Invoke-ServerSsh $c "test -f $ServerHome/app/phoenixscout.jar && mkdir -p $ServerHome/config $ServerHome/data/research && rm -f $ServerHome/data/phoenixscout.db*") -eq 0
        if (-not $ok) { Write-ServerLog '服务器上还没有安装好（先执行 install）' }
        $cfg = @(Join-Path $AppDir 'config\application-local.yml')
        $keyFile = Join-Path $AppDir 'config\coingecko-demo.key'
        if (Test-Path $keyFile) { $cfg += $keyFile }
        if ($ok) { $ok = (Send-ServerFiles $c $cfg "$ServerHome/config") -eq 0; Write-ServerLog "上传配置：$(if ($ok) { '成功' } else { '失败' })" }
        $research = Get-NewResearch -all
        if ($ok -and $research.Count -gt 0) {
            $ok = (Send-ServerFiles $c $research "$ServerHome/data/research") -eq 0
            Write-ServerLog "上传研究文件 $($research.Count) 个：$(if ($ok) { '成功' } else { '失败' })"
        }
        if ($ok) { $ok = (Invoke-ServerSsh $c $ServerScripts[$action]) -eq 0 }
        if ($ok) {
            Set-Content $ServerActive (Get-Date).ToString('o')
            $m = Join-Path $Bin 'research-synced.txt'; Set-Content $m (Get-Date).ToString('o')
            Write-ServerLog '迁移完成'
            Send-Telegram '✅ PhoenixScout 已迁移到云服务器运行，数据从零开始（旧数据原样保留在电脑上）。电脑上的服务已停用，只保留自动更新和研究文件同步。'
        } else {
            Remove-Item $Disabled, $ServerActive -ErrorAction SilentlyContinue
            if (Start-PhoenixScout) { Write-ServerLog '迁移没有完成，本机服务已恢复运行' }
            Send-Telegram '⚠️ PhoenixScout 迁移到服务器没有完成，已恢复由电脑运行'
        }
    } elseif ($action -eq 'fallback') {
        Invoke-ServerSsh $c $ServerScripts.fallback | Out-Null
        Remove-Item $Disabled, $ServerActive -ErrorAction SilentlyContinue
        if (Start-PhoenixScout) { Write-ServerLog '本机服务已启动'; Send-Telegram '✅ PhoenixScout 已切回电脑运行' }
    } else {
        $code = Invoke-ServerSsh $c $ServerScripts[$action]
        Write-ServerLog "退出码 $code"
    }
}

# 防止两次运行重叠（构建可能超过 3 分钟）
$mutex = New-Object System.Threading.Mutex($false, 'Local\PhoenixScoutAutoUpdate')
if (-not $mutex.WaitOne(0)) { exit 0 }

try {
    # ---------- 1. 计划任务齐全（守护任务由 install-autoupdate.ps1 注册，缺了就补装） ----------
    # 安装脚本有变化（例如服务改为隐藏窗口运行）或守护任务缺失时重新注册
    $installer = Join-Path $PSScriptRoot 'install-autoupdate.ps1'
    $hash = (Get-FileHash $installer -Algorithm SHA256).Hash
    $hashFile = Join-Path $Bin 'installer.sha256'
    & schtasks.exe /Query /TN 'PhoenixScout-Watchdog' 2>&1 | Out-Null
    $wdMissing = $LASTEXITCODE -ne 0
    # 守护任务注册了但超过 15 分钟没跑过（心跳文件没更新）：也重新注册，并提醒
    $wdBeat = Join-Path $Bin 'watchdog-last.txt'
    $wdDead = (Test-Path $wdBeat) -and ((Get-Item $wdBeat).LastWriteTime -lt (Get-Date).AddMinutes(-15))
    if ($wdDead) { Send-Telegram '⚠️ PhoenixScout 守护任务超过 15 分钟没有运行，已重新注册' }
    if ($wdMissing -or $wdDead -or $hash -ne (Read-Text $hashFile)) {
        Set-Content $hashFile $hash
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'install-autoupdate.ps1') | Out-Null
        Write-Log '已重新注册计划任务（服务、自动更新、守护）'
    }

    try { Set-NoSleep } catch { Write-Log "设置不睡眠出错：$($_.Exception.Message)" }
    try { Invoke-ServerRequest } catch { Write-Log "服务器请求执行出错：$($_.Exception.Message)" }
    try { Sync-Server } catch { Write-Log "服务器同步出错：$($_.Exception.Message)" }

    # ---------- 2. 检查新提交 ----------
    if (-not (Test-Path (Join-Path $SrcDir '.git'))) {
        & git.exe clone --quiet --branch $Branch $RepoUrl $SrcDir 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'git clone 失败' }
    }
    Invoke-Git @('fetch', '--quiet', 'origin', $Branch) | Out-Null
    $latest = Invoke-Git @('rev-parse', "origin/$Branch")
    if ($latest -eq (Read-Text $Version) -or $latest -eq (Read-Text $Failed)) { exit 0 }
    # 上次因为启动检查没完成而回滚的版本，2 小时后再试
    if ($latest -eq (Read-Text $MonFailed) -and (Get-Item $MonFailed).LastWriteTime -gt (Get-Date).AddHours(-2)) { exit 0 }
    $short = $latest.Substring(0, 7)
    $subject = Invoke-Git @('log', '-1', '--format=%s', $latest)
    Write-Log "发现新提交 ${short}：$subject"

    # ---------- 2a. 本机已停用（服务在云服务器上跑）：只同步文件（含服务器脚本），不构建、不启动 ----------
    if (Test-Path $Disabled) {
        Invoke-Git @('checkout', '--quiet', '--force', $latest) | Out-Null
        & robocopy.exe $SrcDir $AppDir /E /NFL /NDL /NJH /NJS /NP /XD .git .mvn data logs target app ssh /XF application-local.yml | Out-Null
        Set-Content $Version $latest
        Write-Log "本机已停用，只同步文件到 $short（服务器自行更新）"
        exit 0
    }

    # ---------- 2b. 只改文档：复制文件，不构建、不重启 ----------
    $current = Read-Text $Version
    if ($current -and (Test-Path $Jar)) {
        $changed = @()
        try { $changed = @((Invoke-Git @('diff', '--name-only', $current, $latest)) -split "`r?`n" | Where-Object { $_ }) } catch { $changed = @() }
        $docsOnly = $changed.Count -gt 0 -and -not ($changed | Where-Object { $_ -notmatch '^docs/' -and $_ -notmatch '\.md$' })
        if ($docsOnly) {
            Invoke-Git @('checkout', '--quiet', '--force', $latest) | Out-Null
            & robocopy.exe $SrcDir $AppDir /E /NFL /NDL /NJH /NJS /NP /XD .git .mvn data logs target app ssh /XF application-local.yml | Out-Null
            Set-Content $Version $latest
            Write-Log "只更新文档 $short（未重启）"
            Send-Telegram "📄 PhoenixScout 文档已更新到 $short（只改文档，服务没有重启）`n$subject"
            exit 0
        }
    }

    # ---------- 2c. 合并前的 Windows CI 必须通过 ----------
    # 失败：不安装、记下并提醒；还在跑：下次再看；查不到（网络、没有 CI 记录）：照常本机构建，本机测试仍是第二道关。
    $ci = Get-CiState $latest
    if ($ci -eq 'pending') {
        $commitTime = [datetime](Invoke-Git @('log', '-1', '--format=%cI', $latest))
        if ($commitTime -gt (Get-Date).AddMinutes(-40)) { Write-Log "等待 $short 的 Windows CI 结果"; exit 0 }
        Write-Log "$short 的 Windows CI 超过 40 分钟没有结果，改为本机构建测试"
    }
    if ($ci -eq 'failure') {
        Set-Content $Failed $latest
        Write-Log "$short 的 Windows CI 失败，不安装，继续运行旧版"
        Send-Telegram "⚠️ PhoenixScout 新版本 $short 在 Windows CI 上测试失败，没有安装，继续运行旧版。`n$subject"
        exit 1
    }

    # 每日扫描进行中：先不构建也不换版本，3 分钟后再看（避免反复构建）
    if (-not (Test-QuietNow)) { Write-Log "每日扫描进行中，$short 稍后再装"; exit 0 }

    # ---------- 3. 构建（含全部测试） ----------
    Invoke-Git @('checkout', '--quiet', '--force', $latest) | Out-Null
    $newJar = Join-Path $SrcDir 'target\phoenixscout.jar'
    Remove-Item $newJar -ErrorAction SilentlyContinue
    $buildLog = Join-Path $AppDir 'logs\auto-update-build.log'
    & cmd.exe /c "`"$SrcDir\build.cmd`" > `"$buildLog`" 2>&1"
    if (-not (Test-Path $newJar)) {
        Set-Content $Failed $latest
        Write-Log "构建失败 $short，继续运行旧版"
        Send-Telegram "⚠️ PhoenixScout 新版本 $short 构建或测试失败，继续运行旧版。`n$subject`n日志：logs\auto-update-build.log"
        exit 1
    }

    # ---------- 4. 等扫描空档 → 平滑停机 → 替换 → 启动 → 等启动检查完成（期间守护任务不干预） ----------
    # 标记里写本进程 PID：更新程序中途被结束时，守护任务发现这个进程不在了就立刻接手，不用等 30 分钟
    Set-Content $Updating "$PID $((Get-Date).ToString('o'))"
    Set-Content $Updating "$PID $((Get-Date).ToString('o'))"
    if (-not (Test-QuietNow)) { Write-Log "构建期间扫描开始了，$short 稍后再装"; exit 0 }
    if (-not (Stop-PhoenixScout)) {
        Report-StopDenied "新版本 $short 已构建好"
        exit 1
    }
    if (Test-Path $Jar) { Copy-Item $Jar $PrevJar -Force }
    Copy-Item $newJar $Jar -Force
    & robocopy.exe $SrcDir $AppDir /E /NFL /NDL /NJH /NJS /NP /XD .git .mvn data logs target app ssh /XF application-local.yml | Out-Null

    $startedUtc = (Get-Date).ToUniversalTime()
    $started = Start-PhoenixScout
    $startupOk = $started -and (Wait-StartupOk $startedUtc)
    if ($startupOk) {
        Set-Content $Version $latest
        Remove-Item $Failed, $Denied, $MonFailed -ErrorAction SilentlyContinue
        Write-Log "已更新到 $short"
        # 提交说明全文（策略优化提交里写了改了什么、旧值新值、依据数据、回滚方式），去掉署名行
        $body = (Invoke-Git @('log', '-1', '--format=%B', $latest)) -split "`r?`n" |
            Where-Object { $_ -notmatch '^(Co-Authored-By|Claude-Session):' }
        Send-Telegram ("✅ PhoenixScout 已更新到 $short，运行正常`n本次改善：" + (($body -join "`n").Trim()))
    } else {
        $why = if ($started) { '启动后约 10 分钟内启动检查没有完成' } else { '启动后健康检查失败' }
        Write-Log "新版本 $short ${why}，回滚"
        if ($started) { Set-Content $MonFailed $latest } else { Set-Content $Failed $latest }
        Stop-PhoenixScout | Out-Null
        if (Test-Path $PrevJar) { Copy-Item $PrevJar $Jar -Force }
        if (Start-PhoenixScout) {
            Send-Telegram "⚠️ PhoenixScout 新版本 $short ${why}，已回滚到上一版，运行正常。"
        } else {
            Send-Telegram "❗ PhoenixScout 新版本 $short 启动失败，回滚后上一版也没能启动，详见 logs\console.log"
        }
    }
} catch {
    Write-Log "自动更新出错：$($_.Exception.Message)"
} finally {
    Remove-Item $Updating -ErrorAction SilentlyContinue
    $mutex.ReleaseMutex()
}
