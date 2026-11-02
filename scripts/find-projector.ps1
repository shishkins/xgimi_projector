<#
.SYNOPSIS
  Находит IP проектора в локальной сети по MAC (Wi-Fi) и проверяет порт ADB. Ничего не меняет.

.EXAMPLE
  .\scripts\find-projector.ps1
#>
param(
    [string]$Mac,
    [int]$AdbPort = 5555
)

$ErrorActionPreference = 'Stop'

# MAC проектора берём из локального (не публикуемого) конфига device/device.local.json
if (-not $Mac) {
    $cfg = Join-Path (Split-Path -Parent $PSScriptRoot) 'device\device.local.json'
    if (Test-Path $cfg) { $Mac = (Get-Content $cfg -Raw | ConvertFrom-Json).wifi_mac }
}
if (-not $Mac) {
    throw 'Укажи -Mac <aa-bb-cc-dd-ee-ff> или создай device/device.local.json с полем wifi_mac.'
}

$cfg = Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -and $_.IPv4Address.IPAddress -like '192.168.*' } | Select-Object -First 1
if (-not $cfg) { throw 'Не нашёл домашнюю сеть 192.168.x.x' }
$prefix = ($cfg.IPv4Address.IPAddress -replace '\.\d+$', '')

# Пингуем подсеть, чтобы заполнить ARP-таблицу
$tasks = 1..254 | ForEach-Object { (New-Object System.Net.NetworkInformation.Ping).SendPingAsync("$prefix.$_", 400) }
[void][Threading.Tasks.Task]::WaitAll($tasks)

$line = arp -a | Select-String -SimpleMatch $Mac | Select-Object -First 1
if (-not $line) { throw "Проектор ($Mac) не найден в $prefix.0/24. Он включён и в той же Wi-Fi сети?" }
$ip = ($line.ToString().Trim() -split '\s+')[0]

$client = New-Object Net.Sockets.TcpClient
try {
    $ar = $client.BeginConnect($ip, $AdbPort, $null, $null)
    $adbOpen = $ar.AsyncWaitHandle.WaitOne(1500) -and $client.Connected
} finally { $client.Close() }

Write-Host "Проектор: $ip"
Write-Host "ADB ${AdbPort}: $(if ($adbOpen) { 'открыт' } else { 'закрыт' })"
$ip
