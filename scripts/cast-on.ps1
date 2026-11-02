<#
.SYNOPSIS
  Поднимает беспроводной каст (AirPlay-приёмник) после перезагрузки проектора.
  Временный мост, пока нет агента (который будет делать это сам на устройстве).

.DESCRIPTION
  - останавливает стоковый каст-движок XGIMI (конкурент в mDNS),
  - запускает открытый AirPlay-приёмник io.github.jqssun.airplay,
  - проверяет, что он слушает порт 7000.

  Для Miracast (Windows Win+K) используй -Miracast: переводит проектор в режим
  трансляции. ВНИМАНИЕ: при этом проектор уходит с домашней Wi-Fi и ADB пропадает
  (нормально). Выход — кнопкой Назад/Домой на пульте.

.EXAMPLE
  .\scripts\cast-on.ps1             # поднять AirPlay
  .\scripts\cast-on.ps1 -Miracast   # включить режим Miracast для Windows
#>
param(
    [string]$Ip,
    [int]$Port = 5555,
    [switch]$Miracast
)
$ErrorActionPreference = 'Continue'
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb -and (Test-Path 'D:\tools\platform-tools\adb.exe')) { $adb = 'D:\tools\platform-tools\adb.exe' }
if (-not $adb) { throw 'adb не найден.' }
if (-not $Ip) { $Ip = (& (Join-Path $PSScriptRoot 'find-projector.ps1') | Select-Object -Last 1) }
$S = "${Ip}:${Port}"
& $adb disconnect $S 2>&1 | Out-Null; Start-Sleep 1; & $adb connect $S 2>&1 | Out-Null; Start-Sleep 2
if ((& $adb -s $S get-state 2>$null) -ne 'device') { throw "Нет связи с $S." }
function Sh($c) { & $adb -s $S shell $c 2>$null }

if ($Miracast) {
    Write-Host 'Включаю Miracast-режим (Win+K на ПК увидит проектор).'
    Write-Host 'Проектор уйдёт с домашней Wi-Fi; после отключения агент сам вернёт его в сеть.'
    # через кнопку агента: он включит стоковый каст и сам выключит его после сессии
    Sh 'am start -n os.z7.agent/.CastFromWindowsActivity' | Out-Null
    return
}

Write-Host 'Останавливаю стоковый каст...'
Sh 'am force-stop com.xgimi.wirelessscreen' | Out-Null
Sh 'am force-stop com.hpplay.happyplay.aw' | Out-Null
Write-Host 'Запускаю AirPlay-приёмник...'
Sh 'monkey -p io.github.jqssun.airplay -c android.intent.category.LAUNCHER 1' | Out-Null
Start-Sleep 5
$tcp = Sh 'cat /proc/net/tcp /proc/net/tcp6'
$open = $tcp | ForEach-Object { $f = ($_.Trim() -split '\s+'); if ($f.Count -gt 3 -and $f[3] -eq '0A') { [Convert]::ToInt32(($f[1] -split ':')[1], 16) } }
if (7000 -in $open) { Write-Host 'Готово: AirPlay (XGIMI Projector) слушает порт 7000.' }
else { Write-Host 'Похоже, приёмник не поднялся. Запусти ещё раз или проверь на экране проектора.' }
