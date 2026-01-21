@echo off
setlocal

REM Usage:
REM   run_kelheim_extraction.cmd <archiveLabel> <archiveDir> <scenarioPath>
REM Example:
REM   run_kelheim_extraction.cmd run1 ..\..\..\..\matsim_scenarios\matsim-kelheim\output\determinism_archives ..\..\..\..\matsim_scenarios\matsim-kelheim

set "ARCHIVE_LABEL=%~1"
set "ARCHIVE_DIR=%~2"
set "SCENARIO_PATH=%~3"

if "%ARCHIVE_LABEL%"=="" (
  echo ERROR: Missing archiveLabel argument.
  exit /b 2
)
if "%ARCHIVE_DIR%"=="" (
  echo ERROR: Missing archiveDir argument.
  exit /b 2
)
if "%SCENARIO_PATH%"=="" (
  echo ERROR: Missing scenarioPath argument.
  exit /b 2
)

cd /d "%~dp0.." || exit /b 3

mvn -q exec:java -Dexec.mainClass=org.matsim.contrib.demand_extraction.run.RunKelheimDemandExtraction "-Dexec.args=--scenario-path %SCENARIO_PATH% --sample 1 --deterministic --algorithm-process-count -1 --heuristics-process-count -1 --no-cleanup --archive-dir %ARCHIVE_DIR% --archive-label %ARCHIVE_LABEL%"

exit /b %ERRORLEVEL%
