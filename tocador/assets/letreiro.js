/* Letreiro: letra sincronizada das músicas tocadas sem vídeo, estilo karaokê elegante.
   Cada linha aparece meio segundo antes de ser cantada; um degradê dourado percorre a linha atual no ritmo da música.
   Duas vistas: "lado" (painel à direita) e "cheio" (só a letra, rolando, como a letra do YouTube). */
(function () {
  if (window.Letreiro) return;
  var CSS = '' +
    '.tl-caixa{position:absolute;inset:0;pointer-events:none;z-index:2147483647;overflow:hidden;visibility:visible!important;font-family:system-ui,-apple-system,"Segoe UI",Roboto,Arial,sans-serif;-webkit-font-smoothing:antialiased}' +
    '.tl-caixa *{visibility:visible!important}' +
    '.tl-caixa.tl-fixa{position:fixed}' +
    '.tl-painel{position:absolute;top:0;right:0;bottom:0;width:44%;min-width:170px;display:flex;flex-direction:column;justify-content:center;' +
    ' padding:4% 4% 4% 7%;box-sizing:border-box;background:linear-gradient(90deg,rgba(0,0,0,0) 0%,rgba(0,0,0,.42) 26%,rgba(0,0,0,.62) 100%);transition:opacity .4s}' +
    '.tl-cab{font-size:.62em;font-weight:700;letter-spacing:.14em;text-transform:uppercase;color:rgba(255,203,107,.85);margin-bottom:.9em;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}' +
    '.tl-l{color:rgba(255,255,255,.42);font-weight:600;line-height:1.28;margin:.32em 0;transition:all .35s ease;text-shadow:0 2px 10px rgba(0,0,0,.65);word-break:break-word;' +
    ' display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}' +
    '.tl-l.tl-ja{opacity:.55;font-size:.86em}' +
    '.tl-l.tl-prox{color:rgba(255,255,255,.62)}' +
    '.tl-l.tl-agora{font-size:1.32em;font-weight:800;letter-spacing:-.01em;color:transparent;-webkit-line-clamp:3;' +
    ' background:linear-gradient(90deg,#FFD27A 0%,#FFB13B calc(var(--p,0)*100%),#fff calc(var(--p,0)*100% + .5%),#fff 100%);' +
    ' -webkit-background-clip:text;background-clip:text;filter:drop-shadow(0 2px 8px rgba(0,0,0,.7))}' +
    '.tl-l.tl-pausa{letter-spacing:.4em}' +
    '.tl-entra{animation:tlEntra .45s ease}' +
    '@keyframes tlEntra{from{opacity:0;transform:translateY(.6em)}to{opacity:1;transform:none}}' +
    '.tl-oculto{opacity:0}' +
    /* vista "só a letra" (como a letra do YouTube): lista inteira que rola, linha atual em destaque */
    '.tl-caixa.tl-cheio .tl-painel{left:0;width:auto;min-width:0;background:none;justify-content:flex-start;padding:0 8%;' +
    ' -webkit-mask-image:linear-gradient(transparent 0,#000 18%,#000 76%,transparent 100%);mask-image:linear-gradient(transparent 0,#000 18%,#000 76%,transparent 100%)}' +
    '.tl-cheio .tl-cab{display:none}' +
    '.tl-lista{transition:transform .65s cubic-bezier(.22,.8,.24,1);will-change:transform}' +
    '.tl-cheio .tl-l{display:block;-webkit-line-clamp:unset;overflow:visible;font-size:1em;font-weight:800;letter-spacing:-.01em;line-height:1.25;margin:0;padding:.34em 0;' +
    ' color:#fff;transform-origin:left center;transition:opacity .45s,transform .45s,filter .45s}' +
    '.tl-cheio .tl-l.tl-ja,.tl-cheio .tl-l.tl-prox{font-size:1em;color:#fff}' +
    '.tl-cheio .tl-l.tl-agora{font-size:1em;color:transparent;transform:scale(1.07);-webkit-line-clamp:unset}';

  function estilo(doc) {
    if (doc.getElementById('tl-css')) return;
    var s = doc.createElement('style');
    s.id = 'tl-css';
    s.textContent = CSS;
    (doc.head || doc.documentElement).appendChild(s);
  }

  /**
   * pai: elemento onde a letra é desenhada.
   * area(): retângulo a seguir (ex.: o vídeo da página) ou null para ocupar o pai inteiro.
   * relogio(): tempo da música em ms (ou null se não houver).
   */
  function criar(pai, area, relogio) {
    estilo(document);
    var caixa = document.createElement('div');
    caixa.className = 'tl-caixa tl-oculto' + (area ? ' tl-fixa' : '');
    var painel = document.createElement('div');
    painel.className = 'tl-painel';
    caixa.appendChild(painel);
    pai.appendChild(caixa);
    var dados = null, idxAnt = -2, ligado = true, raf = 0, cabTxt = '', cheio = false, lista = null;

    function indice(ms) {
      var L = dados.linhas, i = -1, lo = 0, hi = L.length - 1;
      while (lo <= hi) { var m = (lo + hi) >> 1; if (L[m].t <= ms) { i = m; lo = m + 1; } else hi = m - 1; }
      return i;
    }

    function linha(txt, cls) {
      var d = document.createElement('div');
      var vazio = !txt || !txt.trim();
      d.className = 'tl-l ' + cls + (vazio ? ' tl-pausa' : '');
      d.textContent = vazio ? '♪ ♪ ♪' : txt;
      return d;
    }

    function montarLista() {
      painel.innerHTML = '';
      lista = document.createElement('div');
      lista.className = 'tl-lista';
      var L = dados.linhas;
      for (var k = 0; k < L.length; k++) lista.appendChild(linha(L[k].x, ''));
      painel.appendChild(lista);
    }

    /** Vista cheia: marca a linha atual, esmaece as outras pela distância e rola a lista até ela. */
    function rolar(i) {
      if (!lista) montarLista();
      var els = lista.children, c = Math.max(0, i);
      for (var k = 0; k < els.length; k++) {
        var e = els[k], vazio = e.classList.contains('tl-pausa');
        e.className = 'tl-l' + (vazio ? ' tl-pausa' : '') + (k === i ? ' tl-agora' : k < i ? ' tl-ja' : ' tl-prox');
        e.style.opacity = k === i ? '1' : String(Math.max(0.16, (k < c ? 0.42 : 0.62) - Math.abs(k - c) * 0.1));
        if (k !== i) e.style.removeProperty('--p');
      }
      var alvo = els[c];
      if (alvo) lista.style.transform = 'translateY(' + Math.round(painel.clientHeight * 0.4 - alvo.offsetTop - alvo.offsetHeight / 2) + 'px)';
    }

    function desenhar(i) {
      if (cheio) { rolar(i); return; }
      lista = null;
      painel.innerHTML = '';
      var cab = document.createElement('div');
      cab.className = 'tl-cab';
      cab.textContent = cabTxt;
      painel.appendChild(cab);
      var L = dados.linhas;
      if (i < 0) {
        // antes da primeira linha: mostra o que vem, esmaecido
        for (var k = 0; k < 3 && k < L.length; k++) painel.appendChild(linha(L[k].x, k === 0 ? 'tl-prox' : ''));
        return;
      }
      if (i - 1 >= 0) painel.appendChild(linha(L[i - 1].x, 'tl-ja'));
      var a = linha(L[i].x, 'tl-agora tl-entra');
      painel.appendChild(a);
      for (var j = i + 1; j <= i + 2 && j < L.length; j++) painel.appendChild(linha(L[j].x, j === i + 1 ? 'tl-prox' : ''));
    }

    function quadro() {
      raf = 0;
      if (!dados || !ligado) return;
      if (area) {
        var r = area();
        if (!r || r.width < 40 || r.height < 40) { caixa.classList.add('tl-oculto'); raf = requestAnimationFrame(quadro); return; }
        caixa.style.left = r.left + 'px'; caixa.style.top = r.top + 'px';
        caixa.style.width = r.width + 'px'; caixa.style.height = r.height + 'px';
        caixa.style.right = 'auto'; caixa.style.bottom = 'auto';
      }
      var w = caixa.clientWidth || 300, h = caixa.clientHeight || 300;
      caixa.style.fontSize = (cheio ? Math.max(17, Math.min(40, w * 0.055, h * 0.085)) : Math.max(12, Math.min(30, w * 0.024))) + 'px';
      if (cheio && lista && painel.clientHeight !== quadro.alt) { quadro.alt = painel.clientHeight; idxAnt = -2; } // girou a tela: recentra
      var t = relogio();
      if (t == null) { raf = requestAnimationFrame(quadro); return; }
      caixa.classList.remove('tl-oculto');
      var ms = t + (dados.adiant || 500) + (dados.ajuste || 0);
      var i = indice(ms);
      if (i !== idxAnt) { idxAnt = i; desenhar(i); }
      if (i >= 0) {
        var ini = dados.linhas[i].t, fim = i + 1 < dados.linhas.length ? dados.linhas[i + 1].t : ini + 4000;
        var p = Math.max(0, Math.min(1, (ms - ini) / Math.max(300, fim - ini)));
        var ag = painel.querySelector('.tl-agora');
        if (ag) ag.style.setProperty('--p', p.toFixed(3));
      }
      raf = requestAnimationFrame(quadro);
    }

    return {
      definir: function (d) {
        var k = d ? JSON.stringify(d) : '';
        if (k === this._k) return; // mesmos dados: não redesenha
        this._k = k;
        dados = d && d.linhas && d.linhas.length ? d : null;
        cabTxt = d && d.titulo ? '♪ ' + d.titulo : '♪ Letra';
        idxAnt = -2; lista = null;
        if (!dados) { caixa.classList.add('tl-oculto'); return; }
        if (!raf) raf = requestAnimationFrame(quadro);
      },
      ligar: function (sim) {
        ligado = !!sim;
        if (!ligado) caixa.classList.add('tl-oculto'); else if (dados && !raf) raf = requestAnimationFrame(quadro);
      },
      /** 'lado' (painel à direita, sobre a animação) ou 'cheio' (só a letra, ocupando tudo). */
      vista: function (v) {
        var c = v === 'cheio';
        if (c === cheio) return;
        cheio = c; lista = null; idxAnt = -2;
        caixa.classList.toggle('tl-cheio', cheio);
      },
      remover: function () { ligado = false; if (caixa.parentNode) caixa.parentNode.removeChild(caixa); }
    };
  }

  /** Relógio a partir de leituras espaçadas (tempo + se está tocando + velocidade): anda suave entre elas. */
  function relogioInterpolado() {
    var base = null;
    return {
      ler: function () {
        if (!base) return null;
        return base.tocando ? base.t + (performance.now() - base.em) * (base.r || 1) : base.t;
      },
      atualizar: function (t, tocando, r) {
        var agora = performance.now();
        if (base && base.tocando && tocando) {
          var previsto = base.t + (agora - base.em) * (base.r || 1);
          // pequenas diferenças são corrigidas aos poucos (sem trancos); grandes (avanço/volta) na hora
          if (Math.abs(previsto - t) < 400) { base = { t: previsto + (t - previsto) * 0.3, em: agora, tocando: true, r: r }; return; }
        }
        base = { t: t, em: agora, tocando: tocando, r: r };
      }
    };
  }

  window.Letreiro = { criar: criar, relogio: relogioInterpolado };
})();
