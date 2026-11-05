<#
.SYNOPSIS
Installs the Filum server as a Windows service, or removes it again.

.DESCRIPTION
Run it from the unpacked release, the directory that holds Filum.Server.exe and
Filum.Tray.exe, in a PowerShell window started as administrator.

Installing copies the program to %ProgramFiles%\Filum, where only administrators can change
it, since the service runs as LocalSystem; registers the service "Filum", started with the
computer and restarted if it fails; opens its port and mDNS in the Windows firewall on every
profile, because an overlay network's adapter is usually filed as public
(docs/ARCHITECTURE.md section 2.4); and starts the tray icon whenever someone signs in.

The server's private key, server.pfx, stays readable to SYSTEM and administrators alone. The
tray icon runs as the signed-in user and pins the server by server.cer, the certificate the
server publishes next to the key.

Installing again replaces the program, the service, the rules and the logon entry. Removing
them leaves the backup and the key where they are: the server deletes nothing, and the same key
keeps the paired phones paired after a reinstall.

.PARAMETER Root
The backup root, where every paired phone has a directory. To keep a backup made so far, give
the root the server has used.

.PARAMETER ConfigDirectory
Where the server keeps its key. By default %ProgramData%\Filum.

.PARAMETER Port
The port the server listens on. By default 8765 (docs/PROTOCOL.md section 2).

.PARAMETER Uninstall
Removes the program, the service, the firewall rules and the logon entry instead.

.EXAMPLE
.\Install-Filum.ps1 -Root D:\Backup

.EXAMPLE
& "$env:ProgramFiles\Filum\Install-Filum.ps1" -Uninstall
#>
#Requires -RunAsAdministrator
[CmdletBinding(DefaultParameterSetName = 'Install')]
param(
    [Parameter(Mandatory, ParameterSetName = 'Install')]
    [string] $Root,

    [Parameter(ParameterSetName = 'Install')]
    [string] $ConfigDirectory = (Join-Path $env:ProgramData 'Filum'),

    [Parameter(ParameterSetName = 'Install')]
    [ValidateRange(1, 65535)]
    [int] $Port = 8765,

    [Parameter(Mandatory, ParameterSetName = 'Uninstall')]
    [switch] $Uninstall
)

$ErrorActionPreference = 'Stop'

$serviceName = 'Filum'
$installDirectory = Join-Path $env:ProgramFiles 'Filum'
$rules = 'Filum-TCP-In', 'Filum-mDNS-In'
$runKey = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Run'

# Well-known SIDs rather than names, which Windows translates: "Administratorzy" in Polish.
$system = '*S-1-5-18'
$administrators = '*S-1-5-32-544'
$users = '*S-1-5-32-545'

function Remove-Registration {
    # A running tray icon holds its executable open, and the copy would fail.
    Get-Process -Name 'Filum.Tray' -ErrorAction SilentlyContinue | Stop-Process -Force

    $service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
    if ($service) {
        if ($service.Status -ne 'Stopped') {
            Stop-Service -Name $serviceName -Force
            $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(30))
        }
        & sc.exe delete $serviceName | Out-Null

        # The service lingers, marked for deletion, while something still holds it open.
        $deadline = (Get-Date).AddSeconds(30)
        while (Get-Service -Name $serviceName -ErrorAction SilentlyContinue) {
            if ((Get-Date) -gt $deadline) {
                throw "The $serviceName service is still marked for deletion: close the Services window and try again."
            }
            Start-Sleep -Milliseconds 500
        }
    }

    foreach ($rule in $rules) {
        Remove-NetFirewallRule -Name $rule -ErrorAction SilentlyContinue
    }
    Remove-ItemProperty -Path $runKey -Name $serviceName -ErrorAction SilentlyContinue
    [Environment]::SetEnvironmentVariable('FILUM_CONFIG', $null, 'Machine')
    [Environment]::SetEnvironmentVariable('FILUM_PORT', $null, 'Machine')
}

# A full path, resolved from PowerShell's own location. One ending in a backslash would escape
# the closing quote of its command-line argument, so it ends in "\." instead.
function Resolve-ArgumentPath([string] $path) {
    $full = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($path)
    if ($full.EndsWith('\')) { $full += '.' }
    $full
}

function Invoke-Icacls {
    & icacls.exe @args | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "icacls $($args -join ' ') failed." }
}

if ($Uninstall) {
    Remove-Registration
    if (Test-Path -LiteralPath $installDirectory) { Remove-Item -LiteralPath $installDirectory -Recurse -Force }
    Write-Host 'Filum is removed. The backup and the server key stay where they are.'
    return
}

foreach ($name in 'Filum.Server.exe', 'Filum.Tray.exe') {
    if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot $name))) {
        throw "$name is missing next to the script: run it from the unpacked release."
    }
}

$Root = Resolve-ArgumentPath $Root
$ConfigDirectory = Resolve-ArgumentPath $ConfigDirectory

Remove-Registration

$source = (Resolve-Path -LiteralPath $PSScriptRoot).Path.TrimEnd('\')
if ($source -ne $installDirectory.TrimEnd('\')) {
    New-Item -ItemType Directory -Force -Path $installDirectory | Out-Null
    Copy-Item -Path (Join-Path $source '*') -Destination $installDirectory -Recurse -Force
}
$server = Join-Path $installDirectory 'Filum.Server.exe'
$tray = Join-Path $installDirectory 'Filum.Tray.exe'

New-Item -ItemType Directory -Force -Path $Root | Out-Null
New-Item -ItemType Directory -Force -Path $ConfigDirectory | Out-Null

# The key is for SYSTEM and administrators. Signed-in users may list the directory, and read
# server.cer once the server has written it - never the key itself.
Invoke-Icacls $ConfigDirectory /inheritance:r /grant:r "${system}:(OI)(CI)F" "${administrators}:(OI)(CI)F" "${users}:(RX)"

# A server started by hand kept its key in its user's profile. Taking that key keeps the phones
# paired with it paired.
$serviceKey = Join-Path $ConfigDirectory 'server.pfx'
$handKey = Join-Path $env:LOCALAPPDATA 'Filum\server.pfx'
if (-not (Test-Path -LiteralPath $serviceKey) -and (Test-Path -LiteralPath $handKey)) {
    Copy-Item -LiteralPath $handKey -Destination $serviceKey
    Write-Host "Took the key of the server started by hand, $handKey, so the paired phones stay paired."
}

$arguments = "--Filum:Root=`"$Root`" --Filum:ConfigDirectory=`"$ConfigDirectory`" --Filum:Port=$Port"
New-Service -Name $serviceName -DisplayName 'Filum backup server' `
    -Description 'Receives the backups of paired phones.' `
    -BinaryPathName "`"$server`" $arguments" -StartupType Automatic | Out-Null
& sc.exe failure $serviceName reset= 86400 actions= restart/60000/restart/60000/restart/60000 | Out-Null

New-NetFirewallRule -Name 'Filum-TCP-In' -DisplayName 'Filum backup server' -Direction Inbound `
    -Protocol TCP -LocalPort $Port -Program $server -Action Allow -Profile Any | Out-Null
New-NetFirewallRule -Name 'Filum-mDNS-In' -DisplayName 'Filum backup server (mDNS)' -Direction Inbound `
    -Protocol UDP -LocalPort 5353 -Program $server -Action Allow -Profile Any | Out-Null

# The tray icon finds the key's directory and the port through these, from the next sign-in.
[Environment]::SetEnvironmentVariable('FILUM_CONFIG', $ConfigDirectory, 'Machine')
if ($Port -ne 8765) { [Environment]::SetEnvironmentVariable('FILUM_PORT', "$Port", 'Machine') }
Set-ItemProperty -Path $runKey -Name $serviceName -Value "`"$tray`""

Start-Service -Name $serviceName

$published = Join-Path $ConfigDirectory 'server.cer'
$deadline = (Get-Date).AddMinutes(1)
while (-not (Test-Path -LiteralPath $published)) {
    if ((Get-Date) -gt $deadline) { throw "The service did not come up: $published never appeared." }
    Start-Sleep -Milliseconds 500
}
Invoke-Icacls $published /grant "${users}:(R)"

Write-Host "Filum is installed: the service listens on port $Port and backs up into $Root."
Write-Host "The tray icon starts at the next sign-in, or now from $tray."
