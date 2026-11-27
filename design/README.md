# PokeDeck Mobile — design

Especificação de interface para quem for construir o app. Canvas vivo:
https://claude.ai/artifact/Ve7gipnUAzUDFygzD476KQ (privado do dono). As telas também estão em `screens/`
(HTML de referência: abra no canvas ou leia o markup; `{{ }}` são dados de exemplo vindos do `renderVals()` no fim
de cada arquivo). Ícone do app: `icon.svg` (pixel art 16×16, o mesmo do PC).

Base: o app desktop [poke-deck-pc](https://github.com/PedroAugustoPTC/poke-deck-pc) (local: `E:\Projetos\poke-deck-pc`).
Regras, protocolos e modelo de config vêm de lá: `src/PokeDeck.Core/Config.cs`, `Restock.cs`, `Twitch.cs`, `Kick.cs`,
`Points.cs`, `RedeemBlock.cs`, `src/PokeDeck/game-bridge.js`, `ui/src/App.svelte`.

## O app

- App normal de celular: **tudo roda no aparelho** (o jogo pokeidle.io de cada conta e as lives da Twitch/Kick).
- Até 4 contas. Ao abrir o app, as contas ligadas **entram sozinhas** (login guardado por conta).
- Uma conta por vez na tela. Não existe a grade com todas juntas do PC.
- Prioridade: **o mais leve possível**. Lives sem vídeo (só os pontos, como no PC: `minute-watched` na Twitch,
  socket de espectador na Kick). Economia ligada por padrão.
- **Fora do escopo**: voto no IdleHub, extensões, aba "Tempos e desempenho", layouts grade/colunas, tela cheia de live.

## Telas (`screens/`)

| Arquivo | Tela | Conteúdo |
|---|---|---|
| `Main.dc.html` | Menu | Barra de madeira (logo, Tour, Config). **4 barras de conta**: estado (quadrado verde jogando / laranja entrando), nome, ouro, bônus T/K com tempo restante, lives abertas T/K, indicadores de resgate T, resgate K e recompra. Conta em uso com moldura dourada. Bloco **Lives** (no ar por site, o que cada conta assiste, botão Conferir). **Ajustes rápidos** para todas: Economia, Mudo, ULTRA, horário de bloqueio. Botões Desligar todas e Registro. |
| `Conta.dc.html` | Conta aberta | Toque numa barra **abre a conta por cima do menu** (navegação, não expansão). Topo: **‹ Menu** (volta), nome, ouro, bônus. Ações: T n / K n (login/lives do site), resgate T, resgate K, recompra, recarregar, desligar. Resto da tela: o jogo. |
| `Config.dc.html` | Config › Contas | Seletor das 4 contas. Nome, Ligada, Economia, ULTRA, Mudo. Recompra: liga/desliga, ouro mínimo, regras Pokébola/Poção/Revive (abaixo de / compra), Aplicar a todas. Rever tour. Cancelar / Salvar (nada vale até Salvar). |
| `ConfigLives.dc.html` | Config › Lives | Por site: canais (um por linha, ordem de prioridade), lives por conta (Twitch até 5, Kick conta só 2), baú da Twitch. Por conta: assiste, resgate Twitch, resgate Kick (xp, captura, shiny ou nada), guarda. Horário de bloqueio do resgate. |
| `Tour.dc.html` | Tour | Primeiro acesso, por cima do menu: 7 passos com destaque e balão (Pular / Anterior / Próximo). Textos no `renderVals()`. Revisto por Config › Rever tour ou o botão de ajuda no topo. |
| `Registro.dc.html` | Registro | Lista de eventos (baú, resgate, guarda, baú recusado, bloqueio) com filtro de conta e site. |

`canvas.json` é só o índice do canvas (posições e títulos).

## Visual — "Madeira & Noite"

Cores (do canvas do PC):

| Uso | Hex |
|---|---|
| fundo | `#140a0f` |
| painel / painel alto / poço | `#1e1219` / `#2a141d` / `#33202b` |
| fio (bordas) | `#5c3948` |
| madeira escura / madeira / madeira média / borda clara | `#480e1e` / `#914850` / `#722c3f` / `#bd6951` |
| luz / mel | `#f1d3b7` / `#e7a86d` |
| ouro (ligado) / ouro claro / texto sobre ouro | `#e2a93f` / `#ffe9a3` / `#3a0a16` |
| texto / texto fraco | `#fff6ef` / `#c9a9b4` |
| Twitch / Kick | `#bf94ff` / `#53fc18` |
| jogando / carregando / erro | `#6fd08a` / `#f0a03c` / `#ffc9bd` |

- Fontes (Google Fonts): **Pixelify Sans** (marca e títulos de seção), **Barlow** (texto, 14 px),
  **Barlow Semi Condensed** (barra e botões).
- Cantos de 4 px, borda escura de 1 px, fio de luz em cima (`inset 0 1px 0`). Sem degradê, sem emoji: ícones de traço.
- Ligado = placa dourada. Switches: dourado ligado, `#33202b` desligado.
- Toques de no mínimo 44 px. Telas pensadas em 390 px de largura.
