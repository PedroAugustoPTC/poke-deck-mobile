# Gera, UMA vez, a chave que assina todos os APKs do PokeDeck e mostra o que colar nos secrets do GitHub.
# Guarde o .jks e as senhas fora do repositório (gerenciador de senhas/backup). Perder a chave = os celulares não
# aceitam mais atualização (precisa desinstalar e perder os logins).
param([string]$Out = "$env:USERPROFILE\pokedeck-release.jks")

if (Test-Path $Out) { Write-Error "Já existe $Out. Não gere outra: use a mesma chave sempre."; exit 1 }
$bytes = New-Object byte[] 24
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$pass = [Convert]::ToBase64String($bytes) -replace '[+/=]', 'x'
$alias = 'pokedeck'

keytool -genkeypair -v -keystore $Out -storetype PKCS12 -alias $alias -keyalg RSA -keysize 4096 -validity 36500 `
  -storepass $pass -keypass $pass -dname "CN=PokeDeck"
if ($LASTEXITCODE -ne 0) { exit 1 }

$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($Out))
Write-Host ""
Write-Host "Chave criada em $Out. Agora, no GitHub (Settings > Secrets and variables > Actions), crie:"
Write-Host "  KEYSTORE_B64      = (copiado para a area de transferencia)"
Write-Host "  KEYSTORE_PASSWORD = $pass"
Write-Host "  KEY_ALIAS         = $alias"
Write-Host "  KEY_PASSWORD      = $pass"
Set-Clipboard -Value $b64
