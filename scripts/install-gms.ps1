<#
.SYNOPSIS
  Установка Google-сервисов (GMS) + Play Store на проектор, с проверкой подписи.
  ЭКСПЕРИМЕНТ, обратимо (ставятся как приложения, прошивка не трогается).

.DESCRIPTION
  Ставит 4 пакета В ПРАВИЛЬНОМ ПОРЯДКЕ (порядок критичен):
    1-accountmanager.apk  com.google.android.gsf.login
    2-gsf.apk             com.google.android.gsf   (Services Framework, версия под Android 9)
    3-gms.apk             com.google.android.gms   (Play Services, armeabi-v7a, Android 9+, nodpi)
    4-vending.apk         com.android.vending      (Play Store, nodpi)
  Перед установкой КАЖДЫЙ APK проверяется: подпись должна совпадать с
  официальным сертификатом Google (SHA1 38918A453D07199354F8B19AF05EC6562CED5788).

.EXAMPLE
  .\scripts\install-gms.ps1 -Dir C:\gms
  .\scripts\install-gms.ps1 -Dir C:\gms -Uninstall   # откат
#>
param(
    [Parameter(Mandatory = $true)][string]$Dir,
    [string]$Ip,
    [int]$Port = 5555,
    [switch]$Uninstall
)
$ErrorActionPreference = 'Stop'

$GOOGLE_SHA1 = '38:91:8A:45:3D:07:19:93:54:F8:B1:9A:F0:5E:C6:56:2C:ED:57:88'
$keytool = 'D:\tools\jre17\jdk-17.0.13+11-jre\bin\keytool.exe'
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb -and (Test-Path 'D:\tools\platform-tools\adb.exe')) { $adb = 'D:\tools\platform-tools\adb.exe' }
if (-not $adb) { throw 'adb не найден.' }

if (-not $Ip) { $Ip = (& (Join-Path $PSScriptRoot 'find-projector.ps1') | Select-Object -Last 1) }
$S = "${Ip}:${Port}"
& $adb connect $S | Out-Null
if ((& $adb -s $S get-state 2>$null) -ne 'device') { throw "Нет связи с $S." }

$order = @(
    @{ file = '1-accountmanager.apk'; pkg = 'com.google.android.gsf.login' },
    @{ file = '2-gsf.apk';            pkg = 'com.google.android.gsf' },
    @{ file = '3-gms.apk';            pkg = 'com.google.android.gms' },
    @{ file = '4-vending.apk';        pkg = 'com.android.vending' }
)

if ($Uninstall) {
    Write-Host 'Откат: удаляю GMS-пакеты...'
    foreach ($p in ($order | Select-Object -ExpandProperty pkg | Sort-Object -Descending)) {
        & $adb -s $S uninstall $p 2>&1 | Out-Host
    }
    Write-Host 'Готово. Перезагрузи проектор.'
    return
}

function Get-ApkSha1([string]$apk) {
    $out = & $keytool -printcert -jarfile $apk 2>$null
    ($out | Select-String -Pattern 'SHA1:\s*([0-9A-F:]+)' | Select-Object -First 1).Matches.Groups[1].Value
}

# 1) Проверка подписи всех файлов ДО установки
Write-Host '=== Проверка подписи (должна быть Google) ==='
foreach ($item in $order) {
    $apk = Join-Path $Dir $item.file
    if (-not (Test-Path $apk)) { throw "Нет файла: $apk" }
    $sha1 = Get-ApkSha1 $apk
    if ($sha1 -eq $GOOGLE_SHA1) {
        Write-Host ("  OK   {0}  (подпись Google)" -f $item.file) -ForegroundColor Green
    } else {
        throw "ПОДПИСЬ НЕ GOOGLE у $($item.file): $sha1`nУстановка прервана — файл не подлинный или повреждён."
    }
}

# 2) Установка в правильном порядке
Write-Host '=== Установка по порядку ==='
foreach ($item in $order) {
    $apk = Join-Path $Dir $item.file
    Write-Host "  install $($item.pkg) ..."
    $r = & $adb -s $S install -r -d $apk 2>&1
    if ($r -notmatch 'Success') { Write-Warning ($r -join ' ') }
}

Write-Host ''
Write-Host 'Установлено. Дальше:'
Write-Host '  1) Перезагрузи проектор (или скрипт reboot).'
Write-Host '  2) GSF ID для регистрации устройства покажу отдельной командой.'
Write-Host '  3) Зарегистрируй на https://www.google.com/android/uncertified/ и войди в аккаунт.'
