param(
  [Parameter(Mandatory=$true)][string]$File1,
  [Parameter(Mandatory=$true)][string]$File2,
  [int]$MaxLines = 2000000
)

$lines1 = Get-Content -LiteralPath $File1
$lines2 = Get-Content -LiteralPath $File2

$len = [Math]::Min($lines1.Count, $lines2.Count)
for ($i = 0; $i -lt $len -and $i -lt $MaxLines; $i++) {
  if ($lines1[$i] -ne $lines2[$i]) {
    $lineNo = $i + 1
    Write-Output "FIRST_DIFF_LINE=$lineNo"
    Write-Output "FILE1=$($lines1[$i])"
    Write-Output "FILE2=$($lines2[$i])"
    exit 1
  }
}

if ($lines1.Count -ne $lines2.Count) {
  Write-Output "DIFFERENT_LENGTH file1=$($lines1.Count) file2=$($lines2.Count)"
  exit 1
}

Write-Output 'NO_DIFF'
