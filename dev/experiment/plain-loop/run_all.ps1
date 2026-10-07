# Runs the three plain-loop runs one after another.
#
# Sequential on purpose: the GPU is shared with the owner's own work, so only one
# model conversation runs at a time.
#
# Launch it detached so it survives whatever started it:
#   Start-Process powershell -ArgumentList '-NoProfile','-File','run_all.ps1' -WindowStyle Hidden

$ErrorActionPreference = 'Continue'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$scratch = "$HOME/.swarmcoder/experiment"

"START $(Get-Date -Format o)" | Out-File "$scratch/run_all.status" -Encoding utf8

# run 1 and 2: thinking ON (the server's default). run 3: thinking OFF, as
# SwarmCoder's workers run.
$plan = @(
    @{ n = 1; args = @() },
    @{ n = 2; args = @() },
    @{ n = 3; args = @('--no-thinking') }
)

foreach ($step in $plan) {
    $n = $step.n
    "RUN $n START $(Get-Date -Format o)" | Out-File "$scratch/run_all.status" -Append -Encoding utf8
    $a = @("$here/plain_loop.py", '--run', "$n") + $step.args
    & python @a *> "$scratch/run$n.console.txt"
    "RUN $n LOOP EXIT $LASTEXITCODE $(Get-Date -Format o)" | Out-File "$scratch/run_all.status" -Append -Encoding utf8

    # Measure immediately, while nothing else can touch the repository.
    & python "$here/measure.py" --run $n *> "$scratch/run$n.measure.txt"
    "RUN $n MEASURED exit $LASTEXITCODE $(Get-Date -Format o)" | Out-File "$scratch/run_all.status" -Append -Encoding utf8
}

"ALL DONE $(Get-Date -Format o)" | Out-File "$scratch/run_all.status" -Append -Encoding utf8
