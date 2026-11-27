# PokeDeck Mobile

Versão Android do [PokeDeck](https://github.com/PedroAugustoPTC/poke-deck-pc): até 4 contas do pokeidle.io rodando juntas,
lives da Twitch e da Kick sem vídeo (só os pontos), baú, resgate automático, guarda do bônus, horário de bloqueio e
recompra. Fica rodando com o app fechado e a tela apagada.

Fora do mobile (de propósito, para ficar leve): voto no IdleHub, extensões, aba "Tempos e desempenho" (usa os tempos
padrão do PC), grade com todas as contas juntas e tela cheia de live.

## Instalar e atualizar

Baixe o `PokeDeck.apk` da [última release](https://github.com/PedroAugustoPTC/poke-deck-mobile/releases/latest) e
instale (o Android pede para permitir apps desta fonte). Android 8.0 ou mais novo.

Ao abrir, o app confere se há release nova e oferece atualizar: baixa, confere o SHA-256 que o GitHub publica e entrega
ao instalador do Android. Logins e configurações ficam.

## Primeiro uso

1. **Deixar rodando**: dê as permissões que a tela pede (notificações, sobrepor a outros apps, bateria sem restrição e o
   passo do fabricante: Xiaomi, Samsung, Motorola). Sem elas o Android pausa o jogo com a tela apagada e a sessão cai.
2. Abra cada conta e entre no jogo. Para as lives, toque em **T** / **K** na conta e entre na Twitch/Kick.
3. Em **Config › Lives**, ligue "Assiste" e escolha o bônus de cada conta.

## Como funciona (e o que resolve dos MVPs antigos)

- **Segundo plano**: os navegadores das contas moram no processo, não na tela. Um serviço em primeiro plano
  (`specialUse`, sem o limite de 6 h do `dataSync` do Android 15) segura CPU e Wi-Fi; os navegadores ficam numa janela
  invisível de 1 px, então o Chromium não os pausa. A sessão vai para o disco (cookies) ao entrar no jogo e a cada
  minuto; se o Android matar o app, ele volta sozinho (`START_STICKY`) e as contas entram sem novo login.
- **Contas separadas**: cada conta tem um perfil próprio do WebView (`androidx.webkit` Profile), como os perfis do
  WebView2 no PC. Precisa do "Android System WebView" atualizado; se faltar, o app avisa e leva à Play Store.
- **Tela igual em todas as versões**: ponta a ponta do Android 8 ao 16 (o 15 força isso), com barras, recorte da câmera e
  teclado tratados numa raiz só; sempre retrato; o jogo ignora a fonte grande do sistema.
- **Leve**: Kotlin e Views nativas (sem Compose/AppCompat), jogo a 5 FPS escondido e 20 na tela, uma página de pontos
  por conta só quando precisa (some após 10 min parada), APK sem bibliotecas nativas.

A lógica (recompra, pontos, guarda, bloqueio, watchers, atualização) é porte direto de `src/PokeDeck.Core` do PC, com os
mesmos testes (`app/src/test`). `game-bridge.js` é o mesmo do PC.

## Build

Precisa do JDK 17+ e do Android SDK (API 36).

```powershell
gradle test assembleDebug
```

Release: publique uma release no GitHub com a tag `vX.Y.Z`; o workflow `release.yml` testa, assina e anexa o
`PokeDeck.apk`. A chave de assinatura é criada uma vez com `scripts/make-keystore.ps1` e vai para os secrets
`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. **Use sempre a mesma chave**: com outra, o Android
recusa a atualização.
