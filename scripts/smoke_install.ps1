# Offline tests for the Windows artifact-selection/validation stage. No real HOME/PATH changes.
$ErrorActionPreference = 'Stop'
$TestRoot = Join-Path ([IO.Path]::GetTempPath()) ('dubbo-install-test-' + [guid]::NewGuid())
$InstallerPath = Join-Path $PSScriptRoot '../install.ps1'
$Tokens = $null
$ParseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile($InstallerPath, [ref]$Tokens, [ref]$ParseErrors) | Out-Null
if ($ParseErrors.Count -gt 0) { throw "Installer syntax errors: $ParseErrors" }
$Source = Get-Content -Raw -Encoding UTF8 $InstallerPath
$Stage = ($Source -split '# 4\. Create', 2)[0]
$Stage = $Stage.Replace('$HOME', '$DubboTestHome')
$Script:Downloaded = 0
$Script:VerifyExit = 0
function Get-Command { [CmdletBinding()] param($Name, $CommandType) return @{ Source = 'fake-java' } }
function Invoke-WebRequest {
    param($Uri, $OutFile, $TimeoutSec)
    $Script:Downloaded++
    [IO.File]::WriteAllText($OutFile, 'downloaded-fat')
}
function Start-Process {
    param($FilePath, $ArgumentList, [switch]$NoNewWindow, [switch]$Wait, [switch]$PassThru,
          $RedirectStandardOutput, $RedirectStandardError)
    # Native help output is stderr in the real CLI; it must not be treated as a command failure.
    [IO.File]::WriteAllText($RedirectStandardOutput, '')
    [IO.File]::WriteAllText($RedirectStandardError, 'help text')
    return @{ ExitCode = $Script:VerifyExit }
}
function Run-Stage([string]$CaseName, [hashtable]$Artifacts, [string]$Expected, [bool]$Fails = $false) {
    $CaseRoot = Join-Path $TestRoot $CaseName
    New-Item -ItemType Directory -Force (Join-Path $CaseRoot 'target') | Out-Null
    $DubboTestHome = Join-Path $CaseRoot 'fresh user'
    New-Item -ItemType Directory -Force (Join-Path $DubboTestHome '.dubbo-mcp') | Out-Null
    $Installed = Join-Path $DubboTestHome '.dubbo-mcp/dubbo-mcp.jar'
    [IO.File]::WriteAllText($Installed, 'old-fat')
    foreach ($Name in $Artifacts.Keys) {
        [IO.File]::WriteAllText((Join-Path $CaseRoot "target/$Name"), $Artifacts[$Name])
    }
    $Before = $Script:Downloaded
    Push-Location $CaseRoot
    try {
        $DidFail = $false
        try { & ([scriptblock]::Create($Stage)) } catch { $DidFail = $true }
        if ($DidFail -ne $Fails) { throw "Unexpected stage result: $CaseName" }
        if ([IO.File]::ReadAllText($Installed) -ne $Expected) { throw "Wrong artifact: $CaseName" }
        $ShouldDownload = $CaseName -eq 'thin-only-download'
        if (($Script:Downloaded -gt $Before) -ne $ShouldDownload) { throw "Unexpected download: $CaseName" }
        $Leftovers = @(Get-ChildItem (Split-Path $Installed) | Where-Object { $_.Name -ne 'dubbo-mcp.jar' })
        if ($Leftovers.Count -ne 0) { throw "Staged files leaked: $CaseName" }
        Write-Host "$CaseName : PASS"
    } finally { Pop-Location }
}
try {
    New-Item -ItemType Directory -Force $TestRoot | Out-Null
    Run-Stage 'current-first' @{'dubbo-mcp.jar'='new-fat'; 'dubbo-mcp-1.0.0-SNAPSHOT.jar'='legacy-fat'} 'new-fat'
    Run-Stage 'legacy' @{'dubbo-mcp-1.0.0-SNAPSHOT.jar'='legacy-fat'} 'legacy-fat'
    Run-Stage 'thin-only-download' @{'dubbo-mcp-1.0.2.jar'='thin'} 'downloaded-fat'
    $Script:VerifyExit = 1
    Run-Stage 'validation-failure' @{'dubbo-mcp.jar'='bad'} 'old-fat' $true
} finally {
    if (Test-Path -LiteralPath $TestRoot) { Remove-Item -LiteralPath $TestRoot -Recurse -Force }
}
