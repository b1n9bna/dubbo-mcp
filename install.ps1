# ==============================================================================
# Dubbo-MCP Standalone Installer (Windows PowerShell)
# ==============================================================================

$ErrorActionPreference = "Stop"

Write-Host "Starting Dubbo-MCP installation for Windows..." -ForegroundColor Cyan

# 1. Check Java
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Host "Java 8 or above is required but was not found in PATH." -ForegroundColor Yellow
    Write-Host "Please install Java and try again."
    Exit 1
}

# 2. Setup install directories
$InstallDir = "$HOME\.dubbo-mcp"
$BinDir = "$HOME\.local\bin"
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
New-Item -ItemType Directory -Force -Path $BinDir | Out-Null

$JarPath = "$InstallDir\dubbo-mcp.jar"
$CmdPath = "$BinDir\dubbo-mcp.cmd"

# 3. Stage and validate; failed downloads must preserve the existing installation.
$ReleaseUrl = "https://github.com/b1n9bna/dubbo-mcp/releases/latest/download/dubbo-mcp.jar"
$StagedJar = Join-Path $InstallDir ([System.IO.Path]::GetRandomFileName() + ".jar")
$VerifyOut = "$StagedJar.stdout.log"
$VerifyErr = "$StagedJar.stderr.log"
try {
    # The versioned Maven JAR is thin; only use the shaded artifact or the legacy fat-JAR.
    $LocalJar = @("./target/dubbo-mcp.jar", "./target/dubbo-mcp-1.0.0-SNAPSHOT.jar") |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -First 1
    if ($LocalJar) {
        Copy-Item -LiteralPath $LocalJar -Destination $StagedJar
    } else {
        Invoke-WebRequest -Uri $ReleaseUrl -OutFile $StagedJar -TimeoutSec 120
    }
    # --help writes to stderr; capture streams as files so Windows PowerShell 5.1 does not
    # turn successful native stderr output into an ErrorActionPreference=Stop failure.
    $JavaExe = (Get-Command java -CommandType Application -ErrorAction Stop).Source
    $Verification = Start-Process -FilePath $JavaExe -ArgumentList @("-jar", "`"$StagedJar`"", "--help") `
        -NoNewWindow -Wait -PassThru -RedirectStandardOutput $VerifyOut -RedirectStandardError $VerifyErr
    if ($Verification.ExitCode -ne 0) { throw "Invalid or incompatible JAR" }
    Move-Item -Force $StagedJar $JarPath
} finally {
    foreach ($TemporaryFile in @($StagedJar, $VerifyOut, $VerifyErr)) {
        if (Test-Path -LiteralPath $TemporaryFile) { Remove-Item -LiteralPath $TemporaryFile }
    }
}

# 4. Create dubbo-mcp.cmd wrapper
$CmdContent = "@echo off`njava -jar `"$JarPath`" %*"
Set-Content -Path $CmdPath -Value $CmdContent

# 5. Add to User PATH if not present
$UserPath = [Environment]::GetEnvironmentVariable("Path", "User")
if ($UserPath -notlike "*$BinDir*") {
    [Environment]::SetEnvironmentVariable("Path", "$UserPath;$BinDir", "User")
    Write-Host "Added $BinDir to User PATH." -ForegroundColor Green
}

# 6. Auto-wire AI Agents
& "$CmdPath" install
if ($LASTEXITCODE -ne 0) { throw "MCP configuration failed" }

Write-Host "`nDubbo-MCP installed successfully!" -ForegroundColor Green
Write-Host "Restart your terminal or PowerShell and run: dubbo-mcp --help"
