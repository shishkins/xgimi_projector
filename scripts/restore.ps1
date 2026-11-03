<#
.SYNOPSIS
  Аварийный откат проектора к стоковому состоянию по ADB.
  Возвращает ВСЕ отключённые/скрытые пакеты, сбрасывает HOME на стоковый,
  по запросу удаляет наши приложения. Ничего не прошивает, в разделы не пишет.

.DESCRIPTION
  Это «логический бэкап»: наша оболочка меняет только то, что обратимо
  (отключение пакетов, HOME по умолчанию, настройки). Скрипт отменяет всё это.
  Железо, калибровки, прошивка не затрагиваются — их мы не трогаем в принципе.

.EXAMPLE
  .\scripts\restore.ps1                      # найти проектор и вернуть сток
  .\scripts\restore.ps1 -Ip 192.168.1.50     # явный IP
  .\scripts\restore.ps1 -RemoveOurApps       # ещё и удалить os.z7.*
#>
param(
    [string]$Ip,
    [int]$Port = 5555,
    [switch]$RemoveOurApps
)

$ErrorActionPreference = 'Continue'
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb -and (Test-Path 'D:\tools\platform-tools\adb.exe')) { $adb = 'D:\tools\platform-tools\adb.exe' }
if (-not $adb) { throw 'adb не найден.' }

if (-not $Ip) {
    $finder = Join-Path $PSScriptRoot 'find-projector.ps1'
    $Ip = (& $finder | Select-Object -Last 1)
}
$S = "${Ip}:${Port}"
& $adb connect $S | Out-Null
if ((& $adb -s $S get-state 2>$null) -ne 'device') { throw "Нет связи с $S. Проверь ADB/сеть." }

function Sh([string]$cmd) { & $adb -s $S shell $cmd 2>$null }

Write-Host '=== 1. Включаю все отключённые пакеты ==='
$disabled = Sh 'pm list packages -d'
foreach ($p in $disabled) {
    $pkg = ($p -replace '^package:', '').Trim()
    if ($pkg) { Write-Host "  enable $pkg"; Sh "pm enable $pkg" | Out-Null }
}

Write-Host '=== 2. Возвращаю пакеты, скрытые через --user 0 (install-existing идемпотентен) ==='
# Полный список кандидатов на отключение из device/packages.yaml (green+yellow).
# install-existing безопасен: если пакет уже на месте — это no-op.
$candidates = @(
    'com.xgimi.adservice','com.xgimi.atmosphere','com.xgimi.datareporter','com.xgimi.bugreportsender',
    'com.baidu.oases','com.xgimi.payview','com.xgimi.msgcenter','com.cibn.tv','com.xgimi.doubanfm',
    'com.xgimi.duertts','com.xgimi.newappmarket','com.xgimi.iot','com.xgimi.xgimiiotserver',
    'com.xgimi.smartconnect','com.xgimi.mobilebridgeservice','com.xgimi.mateservice','com.xgimi.agilewall',
    'com.xgimi.upgrade','com.hpplay.happyplay.aw','com.xgimi.screensaver','com.xgimi.home',
    'com.sohu.inputmethod.sogou.tv','com.android.newsettings'
)
foreach ($pkg in $candidates) {
    Sh "cmd package install-existing $pkg" | Out-Null
    Sh "pm enable $pkg" | Out-Null
    Write-Host "  restore $pkg"
}

Write-Host '=== 3. Сбрасываю HOME на стоковый лаунчер XGIMI ==='
Sh 'cmd package set-home-activity com.xgimi.home/com.xgimi.module.cellview.home.ui.HomeActivity' | Out-Null
Sh 'pm enable com.xgimi.home' | Out-Null

Write-Host '=== 3b. Возвращаю стоковую оптимизацию памяти XGIMI ==='
# агент выключает её, чтобы будильник мог поднимать его после режима ожидания (см. Config.MEM_OPTIMISE_PROP)
Sh 'setprop persist.xgimi.mem_optimise.enable true' | Out-Null

if ($RemoveOurApps) {
    Write-Host '=== 4. Удаляю наши приложения os.z7.* ==='
    $ours = (Sh 'pm list packages os.z7') + (Sh 'pm list packages local.z7x')
    foreach ($p in $ours) {
        $pkg = ($p -replace '^package:', '').Trim()
        if ($pkg) { Write-Host "  uninstall $pkg"; Sh "pm uninstall $pkg" | Out-Null }
    }
}

Write-Host ''
Write-Host 'Готово. Перезагрузи проектор и пройди чек-лист из docs/02-safety.md (раздел 6).'
Write-Host 'Если устройство не грузится до рабочего стола — см. лестницу восстановления (раздел 7), ступень 5-6 (сброс/USB强刷).'
