#Requires -Version 5.1
<#
    doc-agent 配置自检脚本
    ---------------------------------------------------------------
    用途：换服务商 / 换模型之后，一键确认「Key 是否有效、模型名是否存在、
          向量库是否连得上、索引有多少片段」，不用等问答报错才发现问题。

    怎么用（在 IDEA 的 Terminal 里，或 PowerShell 里）：
        cd D:\wendaxitong\Work\doc-agent
        powershell -ExecutionPolicy Bypass -File tools\check-config.ps1

    想让它顺便从当前会话的环境变量读 Key：
        先设置好 $env:DASHSCOPE_API_KEY 和 $env:SILICONFLOW_API_KEY 再执行
    注意：脚本【不会】打印完整 Key，只会显示前 8 位，避免截图泄露。
#>

$ErrorActionPreference = "Continue"
$ok = 0
$warn = 0
$fail = 0

function Write-Section($text) {
    Write-Output ""
    Write-Output "=== $text ==="
}

function Write-Ok($text)   { Write-Output "  [OK]   $text";  $script:ok++ }
function Write-Warn($text) { Write-Output "  [注意] $text"; $script:warn++ }
function Write-Fail($text) { Write-Output "  [失败] $text";    $script:fail++ }

function Mask($key) {
    if ([string]::IsNullOrWhiteSpace($key)) { return "(未设置)" }
    if ($key.Length -le 8) { return $key.Substring(0, [Math]::Min(4, $key.Length)) + "****" }
    return $key.Substring(0, 8) + "****" + $key.Substring($key.Length - 4)
}

Write-Output ""
Write-Output "doc-agent 配置自检"
Write-Output "时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"

# ==================== 1. 环境变量 ====================
Write-Section "1. 环境变量"

$dashKey = $env:DASHSCOPE_API_KEY
$sfKey   = $env:SILICONFLOW_API_KEY

if ([string]::IsNullOrWhiteSpace($dashKey)) {
    Write-Warn "DASHSCOPE_API_KEY 未设置（百炼对话模型）"
} else {
    Write-Ok "DASHSCOPE_API_KEY = $(Mask $dashKey)"
}
if ([string]::IsNullOrWhiteSpace($sfKey)) {
    Write-Warn "SILICONFLOW_API_KEY 未设置（硅基流动向量模型）"
} else {
    Write-Ok "SILICONFLOW_API_KEY = $(Mask $sfKey)"
}

# ==================== 2. 读项目配置 ====================
Write-Section "2. 项目配置"

$configPath = Join-Path (Split-Path -Parent $PSScriptRoot) "src\main\resources\application.yaml"
$chatModel = $null
$chatBase = $null
$embedModel = $null
$embedBase = $null
$vectorType = $null

if (Test-Path $configPath) {
    $lines = Get-Content $configPath -Encoding UTF8
    foreach ($line in $lines) {
        if ($line -match '^\s*base-url:\s*(\S+)')  { if (-not $chatBase)  { $chatBase  = $Matches[1] } }
        if ($line -match '^\s*model:\s*(\S+)')    { if (-not $chatModel) { $chatModel = $Matches[1] } }
        if ($line -match '^\s*type:\s*(\S+)')     { if (-not $vectorType) { $vectorType = $Matches[1] } }
    }
    # embedding 段的 base-url / model 要单独抓（它在 chat 下面的嵌套块里）
    $inEmbed = $false
    foreach ($line in $lines) {
        if ($line -match '^\s*embedding:\s*$') { $inEmbed = $true; continue }
        if ($inEmbed -and $line -match '^\s*base-url:\s*(\S+)') { $embedBase = $Matches[1] }
        if ($inEmbed -and $line -match '^\s*model:\s*(\S+)')   { $embedModel = $Matches[1]; $inEmbed = $false }
    }
    Write-Ok "已读取 application.yaml"
} else {
    Write-Fail "找不到 application.yaml（$configPath）"
}

Write-Output "    对话模型：$chatModel"
Write-Output "    对话地址：$chatBase"
Write-Output "    向量模型：$embedModel"
Write-Output "    向量地址：$embedBase"
Write-Output "    向量库  ：$vectorType"

# ==================== 3. 百炼 Key + 模型名 ====================
Write-Section "3. 阿里云百炼（对话模型）"

if ([string]::IsNullOrWhiteSpace($dashKey)) {
    Write-Warn "跳过（没有 Key）"
} else {
    $body = @{ model = $chatModel; messages = @(@{ role = "user"; content = "你好" }) } `
            | ConvertTo-Json -Depth 5
    try {
        $resp = Invoke-RestMethod -Uri "$chatBase/chat/completions" `
                -Method Post `
                -Headers @{ Authorization = "Bearer $dashKey" } `
                -ContentType "application/json" `
                -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) `
                -TimeoutSec 30
        $text = $resp.choices[0].message.content
        Write-Ok "Key 有效，模型 '$chatModel' 可用"
        Write-Output "    模型回答：$text"
        if ($resp.usage) {
            Write-Output "    本次用量：输入 $($resp.usage.prompt_tokens) / 输出 $($resp.usage.completion_tokens) token"
        }
    } catch {
        $msg = $_.Exception.Message
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $msg = $_.ErrorDetails.Message }
        if ($msg -match "model|模型|not found|不存在") {
            Write-Fail "模型名 '$chatModel' 可能不存在于百炼 —— 去控制台模型列表照抄正确的名字"
        } elseif ($msg -match "401|Unauthorized|InvalidApiKey|鉴权") {
            Write-Fail "Key 无效或未生效（401）。检查：1) Key 是否复制完整 2) IDEA 环境变量是否改了要重新 Run"
        } elseif ($msg -match "Arrearage|欠费|balance") {
            Write-Fail "账号欠费！百炼欠费会导致所有调用失败（之前踩过这个坑）"
        } else {
            Write-Fail "调用失败：$msg"
        }
    }
}

# ==================== 4. 硅基流动 Key + 向量模型 ====================
Write-Section "4. 硅基流动（向量模型）"

if ([string]::IsNullOrWhiteSpace($sfKey)) {
    Write-Warn "跳过（没有 Key）"
} else {
    $body = @{ model = $embedModel; input = "测试"; encoding_format = "float" } | ConvertTo-Json -Depth 5
    try {
        $resp = Invoke-RestMethod -Uri "$embedBase/embeddings" `
                -Method Post `
                -Headers @{ Authorization = "Bearer $sfKey" } `
                -ContentType "application/json" `
                -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) `
                -TimeoutSec 30
        $dim = $resp.data[0].embedding.Count
        Write-Ok "Key 有效，向量模型 '$embedModel' 可用（维度 $dim）"
        if ($dim -ne 1024) {
            Write-Warn "维度是 $dim，和 application.yaml 里的 dimensions 不是一回事，请确认是否要同步改"
        }
    } catch {
        $msg = $_.Exception.Message
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $msg = $_.ErrorDetails.Message }
        if ($msg -match "30014|Token is invalid") {
            Write-Fail "Key 无效（Token is invalid）"
        } elseif ($msg -match "429|速率|限流") {
            Write-Warn "被限速了（429）—— 稍等十几秒再试，不是配置问题"
        } else {
            Write-Fail "调用失败：$msg"
        }
    }
}

# ==================== 5. Docker 与向量库 ====================
Write-Section "5. 向量库（PostgreSQL + pgvector）"

$containers = docker ps --format "{{.Names}}|{{.Status}}" 2>$null
if (-not $containers) {
    Write-Warn "Docker 没运行或没有容器 —— 双击桌面 Docker Desktop，等右下角鲸鱼变绿后执行 docker compose up -d"
} else {
    foreach ($c in $containers) {
        if ($c -like "*doc-agent-pg*") { Write-Ok "容器在跑：$c" }
    }
    $cnt = docker exec doc-agent-pg psql -U docagent -d docagent -tAc "select count(*) from vector_store;" 2>$null
    if ($cnt) {
        Write-Ok "向量库里有 $cnt 个片段"
        if ([int]$cnt -eq 0) {
            Write-Warn "索引是空的 —— 请到页面上点「加载文档」建索引"
        }
    } else {
        Write-Fail "连不上数据库或表还不存在"
    }
}

# ==================== 汇总 ====================
Write-Output ""
Write-Output "========================================"
Write-Output "  通过 $ok    注意 $warn    失败 $fail"
Write-Output "========================================"
if ($fail -gt 0) {
    Write-Output ""
    Write-Output "有失败项：优先解决「失败」，「注意」项看情况处理。"
    Write-Output "常见对照表在项目里：docs\02-常见错误速查手册.html"
} else {
    Write-Output ""
    Write-Output "配置没问题，可以启动应用了。"
}
Write-Output ""
