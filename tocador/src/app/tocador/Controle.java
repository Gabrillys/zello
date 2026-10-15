package app.tocador;

import android.content.Context;

/**
 * Ponto único de comando. Sabe qual motor está em uso:
 *  - "vlc": link direto de mídia, tocado pelo libVLC (com equalizador de 10 bandas);
 *  - "web": página de rede social, tocada pelo player do próprio site dentro do navegador do app.
 */
public final class Controle {

    public static volatile String modo = "nada"; // nada, vlc, web

    private Controle() { }

    public static boolean tocando(Context c) {
        if ("vlc".equals(modo)) { Motor m = Motor.seExistir(); return m != null && m.tocando(); }
        if ("web".equals(modo)) return Navegador.tocandoAgora;
        return false;
    }

    public static String titulo(Context c) {
        if ("vlc".equals(modo)) {
            Motor m = Motor.seExistir();
            if (m == null) return "";
            String t = m.titulo();
            return t.length() > 0 ? t : m.url();
        }
        if ("web".equals(modo)) return Navegador.tituloAgora;
        return "";
    }

    public static void tocarPausar(Context c) {
        if ("vlc".equals(modo)) { Motor m = Motor.seExistir(); if (m != null) m.tocarPausar(); }
        else if ("web".equals(modo)) Navegador.comandar("alternar");
        Servico.atualizar(c);
    }

    public static void tocar(Context c) {
        if ("vlc".equals(modo)) { Motor m = Motor.seExistir(); if (m != null) m.continuar(); }
        else if ("web".equals(modo)) Navegador.comandar("tocar");
        Servico.atualizar(c);
    }

    public static void pausar(Context c) {
        if ("vlc".equals(modo)) { Motor m = Motor.seExistir(); if (m != null) m.pausar(); }
        else if ("web".equals(modo)) Navegador.comandar("pausar");
        Servico.atualizar(c);
    }

    /** Para tudo e tira a notificação. */
    public static void parar(Context c) {
        Motor m = Motor.seExistir();
        if (m != null) m.parar();
        if ("web".equals(modo)) Navegador.comandar("pausar");
        Servico.encerrar(c);
    }
}
