<#
.SYNOPSIS
  Снимает слепок состояния проектора по ADB. ТОЛЬКО ЧТЕНИЕ — на устройстве ничего не меняется.

.EXAMPLE
  .\scripts\collect-device-info.ps1 -Ip 192.168.1.50
  .\scripts\collect-device-info.ps1 -Ip 192.168.1.50 -PullApks
#>
param(
    [Parameter(Mandatory = $true)][string]$Ip,
    [int]$Port = 5555,
    # Скачать APK пакетов XGIMI/MediaTek/MStar/Hpplay для анализа (может занять сотни МБ)
    [switch]$PullApks
)

$ErrorActionPreference = 'Stop'

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb -and (Test-Path 'D:\tools\platform-tools\adb.exe')) { $adb = 'D:\tools\platform-tools\adb.exe' }
if (-not $adb) { throw 'adb не найден. Установи Android platform-tools.' }

$serial = "${Ip}:${Port}"
& $adb connect $serial | Out-Host
$state = (& $adb -s $serial get-state 2>$null)
if ($state -ne 'device') { throw "Устройство $serial не в состоянии 'device' (получено: '$state'). Проверь ADB на проекторе." }

$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root ("device\snapshots\" + (Get-Date -Format 'yyyy-MM-dd_HH-mm'))
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Save-Shell([string]$name, [string]$command) {
    Write-Host "  $name"
    # PowerShell 5.1 превращает stderr нативной команды в ошибку; часть путей на устройстве может отсутствовать — это нормально
    $ErrorActionPreference = 'Continue'
    $out = & $adb -s $serial shell $command 2>$null
    $out | Out-File -FilePath (Join-Path $outDir "$name.txt") -Encoding utf8
}

Write-Host "Сохраняю в $outDir"

# Система
Save-Shell 'getprop'            'getprop'
Save-Shell 'id'                 'id; getenforce'
Save-Shell 'uname'              'uname -a; cat /proc/version'
Save-Shell 'cpuinfo'            'cat /proc/cpuinfo'
Save-Shell 'meminfo'            'cat /proc/meminfo'
Save-Shell 'partitions'         'cat /proc/partitions; ls -l /dev/block/by-name /dev/block/platform/*/by-name /dev/block/platform/*/*/by-name'
Save-Shell 'mounts'             'mount; df -h'
Save-Shell 'display'            'wm size; wm density; dumpsys display | head -n 80'

# Пакеты
Save-Shell 'packages-all'       'pm list packages -f -u -U'
Save-Shell 'packages-disabled'  'pm list packages -d'
Save-Shell 'packages-system'    'pm list packages -s'
Save-Shell 'packages-3rdparty'  'pm list packages -3'
Save-Shell 'system-app-dirs'    'ls -la /system/app /system/priv-app /vendor/app /vendor/priv-app /product/app'
Save-Shell 'dumpsys-package'    'dumpsys package'
Save-Shell 'home-activity'      'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME; cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
Save-Shell 'users-accounts'     'pm list users; dumpsys account | head -n 60; dumpsys device_policy'

# Сервисы и активность
Save-Shell 'service-list'       'service list'
Save-Shell 'activities'         'dumpsys activity activities | head -n 120'
Save-Shell 'running-services'   'dumpsys activity services | grep -E "ServiceRecord|app="'
Save-Shell 'processes'          'ps -A -o USER,PID,NAME'
Save-Shell 'accessibility'      'dumpsys accessibility; settings get secure enabled_accessibility_services'
Save-Shell 'input'              'dumpsys input | head -n 200; ime list -a -s'

# Настройки
Save-Shell 'settings-system'    'settings list system'
Save-Shell 'settings-secure'    'settings list secure'
Save-Shell 'settings-global'    'settings list global'
Save-Shell 'locale'             'getprop persist.sys.locale; getprop ro.product.locale; settings get system system_locales'

# Медиа (для AirPlay)
Save-Shell 'media-codecs'       'cat /vendor/etc/media_codecs.xml /vendor/etc/media_codecs_mediatek_video.xml /vendor/etc/media_codecs_performance.xml'
Save-Shell 'audio'              'dumpsys audio | head -n 120'

# Сеть и BT (без паролей: Android их через shell не отдаёт)
Save-Shell 'network'            'ip addr; dumpsys connectivity | head -n 60'
Save-Shell 'bluetooth'          'dumpsys bluetooth_manager | head -n 120'

# Init-скрипты вендора (ищем сервисы вроде xgimi_ssh, свойства ADB)
Save-Shell 'init-rc-list'       'ls -la /vendor/etc/init /vendor/etc/init/hw /system/etc/init'
Save-Shell 'init-rc-xgimi'      'grep -rlE "xgimi|ssh|adb" /vendor/etc/init /system/etc/init'

if ($PullApks) {
    $apkDir = Join-Path $outDir 'apks'
    New-Item -ItemType Directory -Force -Path $apkDir | Out-Null
    $lines = Get-Content (Join-Path $outDir 'packages-all.txt')
    foreach ($line in $lines) {
        # формат: package:/system/app/X/X.apk=com.example uid:1000
        if ($line -notmatch '^package:(?<path>.+\.apk)=(?<pkg>\S+)') { continue }
        $pkg = $Matches.pkg
        $apkPath = $Matches.path
        if ($pkg -notmatch '^(com\.xgimi|com\.mediatek|mediatek|com\.mstar|com\.hpplay)') { continue }
        Write-Host "  pull $pkg"
        $ErrorActionPreference = 'Continue'
        & $adb -s $serial pull $apkPath (Join-Path $apkDir "$pkg.apk") 2>$null | Out-Null
    }
}

Write-Host ''
Write-Host "Готово: $outDir"
Write-Host 'На устройстве ничего не изменено.'
