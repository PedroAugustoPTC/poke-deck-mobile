// Roda antes do jogo (só no pokeidle.io). Duas coisas que o WebView do Android não tem e o WebView2 do PC tinha:
// 1) a página sempre "visível": o jogo não se pausa sozinho quando a conta está escondida ou a tela apagada;
// 2) mudo: o Android não tem "IsMuted" por WebView, então o som do jogo é cortado aqui (window.__pdMuted, lido a cada uso).
(() => {
  if (window.top !== window || window.__pdKeep || !/(^|\.)pokeidle\.io$/.test(location.hostname)) return;
  window.__pdKeep = true;

  try {
    Object.defineProperty(Document.prototype, 'hidden', { get: () => false, configurable: true });
    Object.defineProperty(Document.prototype, 'visibilityState', { get: () => 'visible', configurable: true });
    document.addEventListener('visibilitychange', (e) => e.stopImmediatePropagation(), true);
    window.addEventListener('pagehide', (e) => e.stopImmediatePropagation(), true);
  } catch { }

  const contexts = new Set();
  const AC = window.AudioContext || window.webkitAudioContext;
  if (typeof AC === 'function') {
    const resume = AC.prototype.resume;
    AC.prototype.resume = function () { return window.__pdMuted ? Promise.resolve() : resume.call(this); };
    const Wrapped = new Proxy(AC, {
      construct(target, args, newTarget) {
        const ctx = Reflect.construct(target, args, newTarget);
        contexts.add(ctx);
        if (window.__pdMuted) ctx.suspend().catch(() => { });
        return ctx;
      },
    });
    window.AudioContext = Wrapped;
    if (window.webkitAudioContext) window.webkitAudioContext = Wrapped;
    window.__pdResume = resume;
  }
  const play = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function () {
    if (window.__pdMuted) this.muted = true;
    return play.call(this);
  };
  window.__pdMute = (on) => {
    window.__pdMuted = !!on;
    for (const c of contexts) (on ? c.suspend() : (window.__pdResume || c.resume).call(c)).catch(() => { });
    for (const m of document.querySelectorAll('audio,video')) m.muted = !!on;
  };
})();
