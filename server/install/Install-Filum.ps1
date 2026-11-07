<#
.SYNOPSIS
Installs the Filum server as a Windows service, or removes it again.

.DESCRIPTION
Run it from the unpacked release, the directory that holds Filum.Server.exe and
Filum.Tray.exe, in a PowerShell window started as administrator.

Installing copies the program to %ProgramFiles%\Filum, where only administrators can change
what the service runs; registers the service "Filum", started with the computer and restarted
if it fails, under its own virtual account, NT SERVICE\Filum, which keeps no privilege but
traversing directories; opens its port and mDNS in the Windows firewall on every profile,
because an overlay network's adapter is usually filed as public (docs/ARCHITECTURE.md section
2.4); and starts the tray icon whenever someone signs in.

The server's private key, server.pfx, stays readable to the service, SYSTEM and administrators
alone. A key
that a server started by hand left in its user's profile moves there, so the phones paired with
that server stay paired. The tray icon runs as the signed-in user and pins the server by
server.cer, the certificate the server publishes next to the key.

The backup becomes the service's to write: the service, SYSTEM and administrators may change
it, the user who installs may read it, to browse and copy it in Explorer, and nobody else gets
in. Deleting
from it by hand then takes an administrator. Since everything under the root changes hands, the
root must be a backup's: a directory of its own, not a whole drive, holding device directories
alone and no link. The installer refuses any other.

Installing again replaces the program, the service, the rules, the logon entry and the event
log source. Removing them leaves the backup, with its rights, and the key where they are: the
server deletes nothing, and the same key keeps the paired phones paired after a reinstall.

.PARAMETER Root
The backup root, where every paired phone has a directory. To keep a backup made so far, give
the root the server has used. It must hold nothing but those directories.

.PARAMETER ConfigDirectory
Where the server keeps its key. By default %ProgramData%\Filum.

.PARAMETER Port
The port the server listens on. By default 8765 (docs/PROTOCOL.md section 2).

.PARAMETER Uninstall
Removes the program, the service, the firewall rules, the logon entry and the event log source
instead.

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

# The host logs under the application's name.
$eventSource = 'Filum.Server'

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
    if ([Diagnostics.EventLog]::SourceExists($eventSource)) { [Diagnostics.EventLog]::DeleteEventSource($eventSource) }
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

function Invoke-Sc {
    & sc.exe @args | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "sc.exe $($args -join ' ') failed." }
}

# Every directory of a backup, the root first. A link anywhere stops it: changing rights would
# follow one out of the backup, and so would the service, writing through it.
function Get-BackupDirectory([string] $directory) {
    $directory
    foreach ($item in Get-ChildItem -LiteralPath $directory -Force) {
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw "$($item.FullName) is a link, which the service would write through: remove it and install again."
        }
        if ($item.PSIsContainer) { Get-BackupDirectory $item.FullName }
    }
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

# Installing gives the root and everything in it to the service, so before anything changes, the
# root has to prove a backup's: a directory of its own, holding device directories alone, with
# no link anywhere.
$fullRoot = [IO.Path]::GetFullPath($Root)
if ($fullRoot -eq [IO.Path]::GetPathRoot($fullRoot)) {
    throw "$fullRoot is a whole drive: give the backup a directory of its own, such as $(Join-Path $fullRoot 'Filum')."
}
$directories = @()
if (Test-Path -LiteralPath $Root) {
    if ((Get-Item -LiteralPath $Root -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw "$Root is a link, which the service would write through: give the backup a directory of its own."
    }
    $foreign = @(Get-ChildItem -LiteralPath $Root -Force | Where-Object {
        -not ($_.PSIsContainer -and (Test-Path -LiteralPath (Join-Path $_.FullName '.filum')))
    })
    if ($foreign) {
        throw "$Root holds more than backups ($(($foreign | Select-Object -First 5 | ForEach-Object Name) -join ', ')): give the backup a directory of its own."
    }
    $directories = @(Get-BackupDirectory $Root)
}

Remove-Registration

$source = (Resolve-Path -LiteralPath $PSScriptRoot).Path.TrimEnd('\')
if ($source -ne $installDirectory.TrimEnd('\')) {
    New-Item -ItemType Directory -Force -Path $installDirectory | Out-Null
    Copy-Item -Path (Join-Path $source '*') -Destination $installDirectory -Recurse -Force
}
$server = Join-Path $installDirectory 'Filum.Server.exe'
$tray = Join-Path $installDirectory 'Filum.Tray.exe'

# The service runs under its own virtual account, NT SERVICE\Filum, rather than LocalSystem: a
# flaw in a server that listens on the network then reaches only what that account may touch,
# the backup and the key. Of its privileges it keeps the one to traverse directories alone;
# without SeImpersonatePrivilege, which every service account has by default, it cannot take on
# the identity of a client that connects to it. The account exists only with the service, so
# the service comes first and the rights after.
$arguments = "--Filum:Root=`"$Root`" --Filum:ConfigDirectory=`"$ConfigDirectory`" --Filum:Port=$Port"
New-Service -Name $serviceName -DisplayName 'Filum backup server' `
    -Description 'Receives the backups of paired phones.' `
    -BinaryPathName "`"$server`" $arguments" -StartupType Automatic | Out-Null
Invoke-Sc config $serviceName obj= "NT SERVICE\$serviceName"
Invoke-Sc privs $serviceName SeChangeNotifyPrivilege
Invoke-Sc failure $serviceName reset= 86400 actions= restart/60000/restart/60000/restart/60000
$serviceAccount = '*' + (New-Object Security.Principal.NTAccount "NT SERVICE\$serviceName").Translate(
    [Security.Principal.SecurityIdentifier]).Value

New-Item -ItemType Directory -Force -Path $Root | Out-Null

# The backup is the service's to write. Whoever else could write to it could make a link there,
# through which the service would write anywhere on the disk, or a device record of their own,
# and any program they run could destroy the backup. The user who installs may read it, to
# browse and copy it in Explorer. The root inherits nothing from its parent, which on a data
# drive lets every signed-in user change everything below. The root and every directory already
# in it go to administrators, since an owner may always change an object's rights, and those
# directories lose rights given to them on their own; a right on a file changes that file alone.
# /L changes a link itself rather than what it points to, should one appear after the check.
# Granting with :r replaces only the named accounts' rights, so the root's own rights for anyone
# else go first.
$reader = '*' + [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
Invoke-Icacls $Root /reset /L
Invoke-Icacls $Root /inheritance:r /grant:r "${system}:(OI)(CI)F" "${administrators}:(OI)(CI)F" "${serviceAccount}:(OI)(CI)M" "${reader}:(OI)(CI)RX" /L
Invoke-Icacls $Root /setowner $administrators /L
foreach ($directory in $directories | Select-Object -Skip 1) {
    Invoke-Icacls $directory /reset /L
    Invoke-Icacls $directory /setowner $administrators /L
}

New-Item -ItemType Directory -Force -Path $ConfigDirectory | Out-Null

# The key is for the service, SYSTEM and administrators. Signed-in users may list the directory,
# and read server.cer once the server has written it - never the key itself. The directory's own
# rights for anyone else go first, since granting with :r replaces only the named accounts'.
Invoke-Icacls $ConfigDirectory /reset
Invoke-Icacls $ConfigDirectory /inheritance:r /grant:r "${system}:(OI)(CI)F" "${administrators}:(OI)(CI)F" "${serviceAccount}:(OI)(CI)M" "${users}:(RX)"

# A server started by hand kept its key in its user's profile, where every program that user
# runs can read it. The key moves here, so the phones paired with that server stay paired and
# the profile keeps no copy. It is written anew and the original removed, rather than moved in
# place: a file moved within a volume keeps the rights it had, and the new one takes this
# directory's.
$serviceKey = Join-Path $ConfigDirectory 'server.pfx'
$handKey = Join-Path $env:LOCALAPPDATA 'Filum\server.pfx'
if (-not (Test-Path -LiteralPath $serviceKey) -and (Test-Path -LiteralPath $handKey)) {
    Copy-Item -LiteralPath $handKey -Destination $serviceKey
    Remove-Item -LiteralPath $handKey
    Write-Host "Moved the key of the server started by hand from $handKey, so the paired phones stay paired."
}

# The service's account may write to the event log but not create a source there, and without
# one the host stops logging at its first warning.
[Diagnostics.EventLog]::CreateEventSource($eventSource, 'Application')

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
