# Ticarvo backend testleri: ayri, gecici bir MySQL (127.0.0.1:56128) acar, testleri calistirir, sonra kapatir.
# Mevcut MySQL80 servisinize ve veritabanlariniza dokunmaz.
# Calistirma (TicarvoBackend klasorunde):  powershell -ExecutionPolicy Bypass -File scripts\run-tests.ps1
# Yalniz bazi siniflar:                    ... run-tests.ps1 -Test OrderIntegrationTest,ApiErrorsTest
param([string]$Test = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$port = 56128
$dataDir = Join-Path $env:LOCALAPPDATA 'TicarvoTestMySQL\data'

$bin = Get-ChildItem 'C:\Program Files\MySQL\MySQL Server*\bin\mysqld.exe' -ErrorAction SilentlyContinue |
       Sort-Object FullName -Descending | Select-Object -First 1
if (-not $bin) { throw 'mysqld.exe bulunamadi (C:\Program Files\MySQL\MySQL Server*\bin).' }
$binDir = $bin.DirectoryName
$mysql = Join-Path $binDir 'mysql.exe'
$mysqladmin = Join-Path $binDir 'mysqladmin.exe'

if (-not (Test-Path $dataDir)) {
    Write-Host "Test veri klasoru olusturuluyor: $dataDir"
    New-Item -ItemType Directory -Force -Path (Split-Path $dataDir) | Out-Null
    & $bin.FullName --initialize-insecure "--datadir=$dataDir" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'MySQL test veri klasoru olusturulamadi.' }
}

Write-Host "Test MySQL baslatiliyor (port $port)..."
$server = Start-Process -FilePath $bin.FullName -WindowStyle Hidden -PassThru -ArgumentList @(
    "--datadir=`"$dataDir`"", "--port=$port", '--bind-address=127.0.0.1', '--mysqlx=OFF',
    '--enable-named-pipe=OFF', '--shared-memory=OFF')

$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    # A not-yet-ready server writes to stderr; under Stop that must not end the script and orphan mysqld.
    & { $ErrorActionPreference = 'Continue'; & $mysqladmin -uroot --host=127.0.0.1 "--port=$port" --default-character-set=utf8mb4 ping 2>$null | Out-Null }
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $ready) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue; throw 'Test MySQL baslamadi.' }

$exit = 1
try {
    $sql = "CREATE DATABASE IF NOT EXISTS ticarvo_m2_test; CREATE DATABASE IF NOT EXISTS ticarvo_m2_upgrade_test; " +
           "CREATE USER IF NOT EXISTS 'ticarvo_test'@'127.0.0.1' IDENTIFIED BY 'ticarvo_test'; " +
           "GRANT ALL ON ticarvo_m2_test.* TO 'ticarvo_test'@'127.0.0.1'; " +
           "GRANT ALL ON ticarvo_m2_upgrade_test.* TO 'ticarvo_test'@'127.0.0.1';"
    & $mysql -uroot --host=127.0.0.1 "--port=$port" --default-character-set=utf8mb4 -e $sql
    if ($LASTEXITCODE -ne 0) { throw 'Test semalari olusturulamadi.' }

    $env:TEST_DB_URL = "jdbc:mysql://127.0.0.1:$port/ticarvo_m2_test?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC"
    $env:TEST_DB_USERNAME = 'ticarvo_test'
    $env:TEST_DB_PASSWORD = 'ticarvo_test'

    Push-Location $root
    # Old reports would otherwise appear in the summary as if they belonged to this run.
    Remove-Item (Join-Path $root 'target\surefire-reports') -Recurse -Force -ErrorAction SilentlyContinue
    $mavenArgs = @('-q', 'test')
    if ($Test) { $mavenArgs += @("-Dtest=$Test", '-Dsurefire.failIfNoSpecifiedTests=false') }
    try { & .\mvnw.cmd @mavenArgs; $exit = $LASTEXITCODE } finally { Pop-Location }
}
finally {
    Write-Host 'Test MySQL kapatiliyor...'
    & { $ErrorActionPreference = 'Continue'; & $mysqladmin -uroot --host=127.0.0.1 "--port=$port" --default-character-set=utf8mb4 shutdown 2>$null | Out-Null }
    Start-Sleep -Seconds 2
    if (-not $server.HasExited) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
}

$reports = Join-Path $root 'target\surefire-reports'
if (Test-Path $reports) {
    Write-Host "`n===== SONUC ====="
    Get-ChildItem $reports -Filter '*.txt' | ForEach-Object { Get-Content $_.FullName | Select-String 'Tests run' }
}
if ($exit -eq 0) { Write-Host "`nTUM TESTLER GECTI" -ForegroundColor Green } else { Write-Host "`nTESTLERDE HATA VAR (cikis kodu $exit)" -ForegroundColor Red }
exit $exit
