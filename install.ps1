# ==============================================================================
# Dubbo-MCP Standalone Installer (Windows PowerShell)
# ==============================================================================

$ErrorActionPreference = "Stop"

Write-Host "🧭 Starting Dubbo-MCP installation for Windows..." -ForegroundColor Cyan

# 1. Check Java
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Host "⚠️ Java 8 or above is required but was not found in PATH." -ForegroundColor Yellow
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
try {
    if (Test-Path "./target/dubbo-mcp-1.0.0-SNAPSHOT.jar") {
        Copy-Item "./target/dubbo-mcp-1.0.0-SNAPSHOT.jar" $StagedJar
    } else {
        Invoke-WebRequest -Uri $ReleaseUrl -OutFile $StagedJar -TimeoutSec 120
    }
    & java -jar $StagedJar --help 2>$null
    if ($LASTEXITCODE -ne 0) { throw "Invalid or incompatible JAR" }
    Move-Item -Force $StagedJar $JarPath
} finally {
    if (Test-Path $StagedJar) { Remove-Item $StagedJar }
}

# 4. Create dubbo-mcp.cmd wrapper
$CmdContent = "@echo off`njava -jar `"$JarPath`" %*"
Set-Content -Path $CmdPath -Value $CmdContent

# 5. Add to User PATH if not present
$UserPath = [Environment]::GetEnvironmentVariable("Path", "User")
if ($UserPath -notlike "*$BinDir*") {
    [Environment]::SetEnvironmentVariable("Path", "$UserPath;$BinDir", "User")
    Write-Host "✓ Added $BinDir to User PATH." -ForegroundColor Green
}

# 6. Auto-wire AI Agents
& "$CmdPath" install
if ($LASTEXITCODE -ne 0) { throw "MCP configuration failed" }

Write-Host "`n🎉 Dubbo-MCP installed successfully!" -ForegroundColor Green
Write-Host "Restart your terminal or PowerShell and run: dubbo-mcp --help"
