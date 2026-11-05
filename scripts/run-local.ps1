[CmdletBinding()]
param(
    [string]$DeviceSerial,
    [switch]$SkipInstall,
    [switch]$RegisterDebugDevice,
    [switch]$BackendWorker
)

# Local development only. Credentials remain in functions/.env or the external
# files it references. Neither their contents nor raw backend output are logged.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $env:ANDROID_USER_HOME -and $env:USERPROFILE) { $env:ANDROID_USER_HOME = Join-Path $env:USERPROFILE '.android' }
$jtpRepository = Split-Path -Parent $PSScriptRoot
$jtpFunctions = Join-Path $jtpRepository 'functions'
$jtpArtifacts = Join-Path $jtpRepository '.artifacts'
$jtpStdoutLog = Join-Path $jtpArtifacts 'backend-local.stdout.log'
$jtpStderrLog = Join-Path $jtpArtifacts 'backend-local.stderr.log'

function Test-JtpHealth {
    try {
        $jtpResponse = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:8787/healthz' -TimeoutSec 2
        $jtpHealth = $jtpResponse.Content | ConvertFrom-Json
        return $jtpResponse.StatusCode -eq 200 -and $jtpHealth.service -eq 'jayaguna-api' -and $jtpHealth.status -eq 'ok'
    } catch { return $false }
}

function Test-JtpPort {
    $jtpConnection = New-Object System.Net.Sockets.TcpClient
    try {
        $jtpConnectTask = $jtpConnection.ConnectAsync('127.0.0.1', 8787)
        return $jtpConnectTask.Wait(500) -and $jtpConnection.Connected
    } catch { return $false }
    finally { $jtpConnection.Dispose() }
}

function Write-JtpBackendOutput {
    param([object]$Item)
    $jtpStream = if ($Item -is [System.Management.Automation.ErrorRecord]) { 'stderr' } else { 'stdout' }
    $jtpSafe = [ordered]@{ event = 'backend_output_suppressed'; stream = $jtpStream }
    try {
        $jtpRecord = $Item.ToString() | ConvertFrom-Json -ErrorAction Stop
        if ($jtpRecord.event -eq 'api_started') {
            $jtpSafe = [ordered]@{ event = 'api_started'; port = 8787 }
            if ($jtpRecord.mediaProvider -in @('drive', 'firebase')) { $jtpSafe.mediaProvider = $jtpRecord.mediaProvider }
        } elseif ($jtpRecord.event -eq 'backend_error') {
            $jtpSafe = [ordered]@{ event = 'backend_error' }
            if ($jtpRecord.code -in @('internal', 'invalid-argument', 'unauthenticated', 'permission-denied', 'not-found',
                    'already-exists', 'aborted', 'failed-precondition', 'resource-exhausted', 'unavailable',
                    'deadline-exceeded', 'cancelled', 'unknown', 'unimplemented', 'out-of-range', 'data-loss')) {
                $jtpSafe.code = $jtpRecord.code
            }
            if ($jtpRecord.requestId -match '^[0-9a-f-]{36}$') { $jtpSafe.requestId = $jtpRecord.requestId }
            $jtpStream = 'stderr'
        }
    } catch { # Unknown output is deliberately suppressed, including exception details.
    }
    $jtpLog = if ($jtpStream -eq 'stderr') { $jtpStderrLog } else { $jtpStdoutLog }
    Add-Content -LiteralPath $jtpLog -Value ($jtpSafe | ConvertTo-Json -Compress) -Encoding UTF8
}

try {
    if (-not (Test-Path -LiteralPath (Join-Path $jtpRepository 'gradlew.bat')) -or
        -not (Test-Path -LiteralPath (Join-Path $jtpFunctions 'package.json'))) {
        throw 'Run this script from the existing Jayaguna repository; required project files are missing.'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $jtpFunctions '.env'))) {
        throw 'functions/.env is missing. Configure Firebase Admin and Google Drive using docs/GOOGLE_DRIVE.md first.'
    }
    $jtpNodeCommand = Get-Command node.exe -ErrorAction SilentlyContinue
    if (-not $jtpNodeCommand) { throw 'Node.js is missing. Install Node.js 22 and run npm.cmd --prefix functions ci.' }
    $jtpNodeExecutable = $jtpNodeCommand.Source

    if ($BackendWorker) {
        New-Item -ItemType Directory -Path $jtpArtifacts -Force | Out-Null
        [System.IO.File]::WriteAllText($jtpStdoutLog, '')
        [System.IO.File]::WriteAllText($jtpStderrLog, '')
        Set-Location -LiteralPath $jtpFunctions
        # Native stderr is filtered before anything is written to either log.
        $ErrorActionPreference = 'Continue'
        & $jtpNodeExecutable --env-file=.env lib/server.js 2>&1 | ForEach-Object { Write-JtpBackendOutput -Item $_ }
        $jtpBackendExit = $LASTEXITCODE
        exit $jtpBackendExit
    }

    $jtpNodeVersion = & $jtpNodeExecutable --version
    if ($LASTEXITCODE -ne 0 -or $jtpNodeVersion -notmatch '^v(\d+)\.' -or [int]$Matches[1] -lt 22) {
        throw 'Use Node.js 22 or newer; Node.js 22 matches the backend runtime.'
    }

    $jtpPropertiesFile = Join-Path $jtpRepository 'local.properties'
    $jtpProperties = if (Test-Path -LiteralPath $jtpPropertiesFile) { [System.IO.File]::ReadAllLines($jtpPropertiesFile) } else { @() }
    $jtpDebugLine = $jtpProperties | Where-Object { $_ -match '^\s*backend\.debugBaseUrl\s*=' } | Select-Object -Last 1
    $jtpDebugUrl = if ($jtpDebugLine) { ($jtpDebugLine -split '=', 2)[1].Trim().TrimEnd('/') } else { '' }
    if ($jtpDebugUrl -notin @('http://10.0.2.2:8787', 'http://localhost:8787', 'http://127.0.0.1:8787')) {
        throw 'Set backend.debugBaseUrl=http://10.0.2.2:8787 in local.properties for Android Emulator, then run this script again.'
    }
    if ($env:BACKEND_BASE_URL -and $env:BACKEND_BASE_URL.Trim().TrimEnd('/') -ne $jtpDebugUrl) {
        throw 'BACKEND_BASE_URL overrides the local debug URL. Clear that environment override for this local launcher.'
    }

    $jtpAdb = $null
    if ($env:ANDROID_HOME) {
        $jtpAdbCandidate = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
        if (Test-Path -LiteralPath $jtpAdbCandidate) { $jtpAdb = $jtpAdbCandidate }
    }
    if (-not $jtpAdb) {
        $jtpSdkLine = $jtpProperties | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -Last 1
        if ($jtpSdkLine) {
            # local.properties uses Java property escaping, e.g. C\:\\Users\\...
            $jtpSdkDirectory = (($jtpSdkLine -split '=', 2)[1].Trim() -replace '\\(.)', '$1')
            $jtpAdbCandidate = Join-Path $jtpSdkDirectory 'platform-tools\adb.exe'
            if (Test-Path -LiteralPath $jtpAdbCandidate) { $jtpAdb = $jtpAdbCandidate }
        }
    }
    if (-not $jtpAdb) { throw 'adb.exe was not found. Set ANDROID_HOME or sdk.dir in local.properties to the installed Android SDK.' }

    $jtpDevicesOutput = @(& $jtpAdb devices 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'adb devices failed. Check the Android SDK and start Android Emulator.' }
    $jtpReadyDevices = @($jtpDevicesOutput | ForEach-Object {
        if ($_.ToString() -match '^(\S+)\s+device(?:\s|$)') { $Matches[1] }
    })
    if ($DeviceSerial) {
        if ($DeviceSerial -notin $jtpReadyDevices) { throw 'The requested device is not ready. Check adb devices, unlock it and authorize USB debugging if needed.' }
        $jtpSelectedDevice = $DeviceSerial
    } else {
        $jtpEmulators = @($jtpReadyDevices | Where-Object { $_ -match '^emulator-\d+$' })
        if ($jtpEmulators.Count -ne 1) { throw 'Start exactly one Android Emulator, or provide -DeviceSerial with a ready serial from adb devices.' }
        $jtpSelectedDevice = $jtpEmulators[0]
    }
    $jtpDeviceBackendUrl = if ($jtpSelectedDevice -match '^emulator-') { $jtpDebugUrl } else { 'http://localhost:8787' }

    $jtpCompiledServer = Join-Path $jtpFunctions 'lib\server.js'
    $jtpNeedsBuild = -not (Test-Path -LiteralPath $jtpCompiledServer)
    if (-not $jtpNeedsBuild) {
        $jtpCompiledAt = (Get-Item -LiteralPath $jtpCompiledServer).LastWriteTimeUtc
        $jtpNeedsBuild = [bool](Get-ChildItem -LiteralPath (Join-Path $jtpFunctions 'src') -Filter '*.ts' -Recurse |
            Where-Object { $_.LastWriteTimeUtc -gt $jtpCompiledAt } | Select-Object -First 1)
    }
    if ($jtpNeedsBuild) {
        $jtpNpm = Get-Command npm.cmd -ErrorAction SilentlyContinue
        if (-not $jtpNpm) { throw 'npm.cmd is missing. Install Node.js 22.' }
        Write-Host 'Building the local backend...'
        & $jtpNpm.Source --prefix $jtpFunctions run build
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $jtpCompiledServer)) {
            throw 'Backend build failed. Run npm.cmd --prefix functions ci, fix the reported build errors, and retry.'
        }
    }

    if (-not (Test-JtpHealth)) {
        if (Test-JtpPort) { throw 'Port 8787 is occupied but does not return the Jayaguna health response. No process was stopped; check the existing listener.' }
        New-Item -ItemType Directory -Path $jtpArtifacts -Force | Out-Null
        $jtpShellName = if ($PSVersionTable.PSEdition -eq 'Core') { 'pwsh.exe' } else { 'powershell.exe' }
        $jtpShellExecutable = Join-Path $PSHOME $jtpShellName
        $jtpWorkerArguments = @('-NoProfile', '-File', ('"' + $PSCommandPath + '"'), '-BackendWorker')
        $jtpWorker = Start-Process -FilePath $jtpShellExecutable -ArgumentList $jtpWorkerArguments -WorkingDirectory $jtpFunctions -WindowStyle Hidden -PassThru
        $jtpDeadline = [DateTime]::UtcNow.AddSeconds(30)
        $jtpHealthy = $false
        do {
            $jtpHealthy = Test-JtpHealth
            if ($jtpHealthy) { break }
            $jtpWorker.Refresh()
            if ($jtpWorker.HasExited) { break }
            Start-Sleep -Milliseconds 500
        } while ([DateTime]::UtcNow -lt $jtpDeadline)
        if (-not $jtpHealthy) {
            throw 'Local backend did not become healthy within 30 seconds. Check HOST=127.0.0.1 and PORT=8787 in functions/.env and the sanitized .artifacts/backend-local.*.log files. No credential values are printed.'
        }
    }
    Write-Host 'Jayaguna backend is healthy at http://127.0.0.1:8787. Cloud/OAuth readiness is checked separately.'

    & $jtpAdb -s $jtpSelectedDevice reverse tcp:8787 tcp:8787
    if ($LASTEXITCODE -ne 0) { throw 'adb reverse failed. Reconnect the selected emulator/device and retry.' }
    if (-not $SkipInstall) {
        $jtpPreviousAndroidSerial = $env:ANDROID_SERIAL
        $env:ANDROID_SERIAL = $jtpSelectedDevice
        Push-Location -LiteralPath $jtpRepository
        try {
            & (Join-Path $jtpRepository 'gradlew.bat') :app:installDebug ('-Pandroid.injected.device.serial=' + $jtpSelectedDevice) ('-PbackendBaseUrl=' + $jtpDeviceBackendUrl) --no-daemon
            if ($LASTEXITCODE -ne 0) { throw 'Android build/install failed. Fix the Gradle error above, then retry.' }
        } finally {
            Pop-Location
            $env:ANDROID_SERIAL = $jtpPreviousAndroidSerial
        }
    }
    & $jtpAdb -s $jtpSelectedDevice shell am start -n 'com.jayaguna.admin/.MainActivity'
    if ($LASTEXITCODE -ne 0) { throw 'The app could not be opened. Retry without -SkipInstall to build and install it.' }
    if ($RegisterDebugDevice) {
        Write-Host 'Registrasi perangkat tidak diperlukan oleh versi aplikasi ini.'
    }
    Write-Host ('Jayaguna opened on ' + $jtpSelectedDevice + '. Login remains Firebase Authentication on the real project.')
} catch {
    Write-Error -Message $_.Exception.Message -ErrorAction Continue
    exit 1
}
