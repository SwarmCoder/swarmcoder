# The whole journey, in one command.
#
# Walks a document all the way to a merged commit against a pristine copy of dev/bookshelf-demo
# and a FREE LOCAL model, and prints one line saying which link broke and what it observed.
#
#   .\dev\e2e-loop.ps1
#   .\dev\e2e-loop.ps1 -Workers 1 -Minutes 15
#
# Nothing here contacts a paid endpoint. Every role — analyst, architect, design reviewer, test
# author, judge and the workers — is built inside the test against the one endpoint below. The
# operator's ~/.swarmcoder/config.yaml, which points seven roles at a billed account, is never read,
# and the test refuses to start unless the endpoint is plain HTTP on a private address.

param(
    # The free local model server. Must be plain http:// on a private address; the test enforces it.
    [string] $BaseUrl = "http://192.168.0.10:8002/v1",
    [string] $Model = "qwen3.8-27b",
    # Workers per task. Two exercises clustering, judging and selection; one short-circuits them.
    [int]    $Workers = 2,
    # Tool turns per worker. The product default is 30 — right for an overnight build, too slow here.
    [int]    $Turns = 12,
    # Whole-journey wall clock. Overrun is reported as a finding naming the last link reached.
    [int]    $Minutes = 25,
    # Build one particular requirement instead of the smallest, e.g. -Requirement "rating".
    [string] $Requirement = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$log = Join-Path $root "dev/e2e-loop-last-run.log"
$started = Get-Date

Write-Host "Walking the whole chain against $BaseUrl ($Model), $Workers worker(s), $Minutes min budget."
Write-Host "Full output: $log"

$mvnArgs = @(
    "-o", "-B", "test",
    "-pl", "sc-app", "-am",
    "-Dtest=EndToEndLoopTest",
    "-Dsurefire.failIfNoSpecifiedTests=false",
    "-Dswarmcoder.jdtLs=on",
    "-Dswarmcoder.live.baseUrl=$BaseUrl",
    "-Dswarmcoder.live.model=$Model",
    "-Dswarmcoder.e2e.workers=$Workers",
    "-Dswarmcoder.e2e.turns=$Turns",
    "-Dswarmcoder.e2e.minutes=$Minutes"
)
if ($Requirement -ne "") { $mvnArgs += "-Dswarmcoder.e2e.requirement=$Requirement" }

& mvn @mvnArgs 2>&1 | Tee-Object -FilePath $log
$exit = $LASTEXITCODE
$elapsed = [int]((Get-Date) - $started).TotalMinutes

# The whole point: one line, without anyone opening the log.
$verdict = Select-String -Path $log -Pattern "^(CHAIN (BROKE AT|WHOLE|INCOMPLETE))" |
    Select-Object -Last 1
Write-Host ""
Write-Host "---------------------------------------------------------------"
if ($null -ne $verdict) {
    Write-Host $verdict.Line
} elseif (Select-String -Path $log -Pattern "NOT RUN:.*EndToEndLoopTest" -Quiet) {
    Write-Host "NOT RUN — see the reason banner in $log (the model server, or Maven, was missing)."
} else {
    Write-Host "NO VERDICT — the harness did not reach its own reporting. See $log."
}
Write-Host "($elapsed min, maven exit $exit)"
Write-Host "---------------------------------------------------------------"
exit $exit
