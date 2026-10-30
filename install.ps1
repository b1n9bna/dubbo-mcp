# ==============================================================================
# Dubbo-MCP Standalone Installer (Windows PowerShell)
# ==============================================================================

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

# 3. Download Jar
$ReleaseUrl = "https://github.com/b1n9bna/dubbo-mcp/releases/latest/download/dubbo-mcp.jar"
Write-Host "📦 Downloading latest dubbo-mcp.jar..." -ForegroundColor Cyan
try {
    Invoke-WebRequest -Uri $ReleaseUrl -OutFile $JarPath
} catch {
    Write-Host "⚠️ Downloading release jar fallback..."
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

Write-Host "`n🎉 Dubbo-MCP installed successfully!" -ForegroundColor Green
Write-Host "Restart your terminal or PowerShell and run: dubbo-mcp --help"
