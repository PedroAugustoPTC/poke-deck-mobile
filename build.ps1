# Compila no Docker (JDK, Android SDK e Gradle ficam no container) e deixa o APK em dist\PokeDeck-debug.apk.
# Sem ErrorActionPreference=Stop: o docker escreve o progresso no stderr, que o PowerShell 5.1 trataria como erro.
Set-Location $PSScriptRoot

docker build --target export --output dist .
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "Pronto: $PSScriptRoot\dist\PokeDeck-debug.apk (relatório dos testes em dist\tests)" -ForegroundColor Green
