param(
  [Parameter(Mandatory=$true)][string]$BaseDir,
  [string]$Run1 = 'run1',
  [string]$Run2 = 'run2'
)

$run1Dir = Join-Path $BaseDir $Run1
$run2Dir = Join-Path $BaseDir $Run2

if (!(Test-Path -LiteralPath $run1Dir)) { throw "Run1 dir not found: $run1Dir" }
if (!(Test-Path -LiteralPath $run2Dir)) { throw "Run2 dir not found: $run2Dir" }

$files = Get-ChildItem -LiteralPath $run1Dir -File | Where-Object { $_.Extension -in @('.csv', '.xml') }
$diffs = New-Object System.Collections.Generic.List[string]

foreach ($file in $files) {
  $other = Join-Path $run2Dir $file.Name
  if (!(Test-Path -LiteralPath $other)) {
    $diffs.Add("MISSING run2 $($file.Name)")
    continue
  }

  $hash1 = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash
  $hash2 = (Get-FileHash -Algorithm SHA256 -LiteralPath $other).Hash
  if ($hash1 -ne $hash2) {
    $diffs.Add("DIFF $($file.Name)")
  }
}

if ($diffs.Count -eq 0) {
  Write-Output 'ALL_MATCH'
} else {
  $diffs
  exit 1
}
