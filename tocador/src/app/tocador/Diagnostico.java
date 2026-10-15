package app.tocador;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Registro do que aconteceu (respostas do YouTube/Instagram, endereços testados, erros do VLC),
 * para a pessoa copiar e enviar quando algo não funciona. Não guarda cookies nem endereços completos.
 */
public final class Diagnostico {

    private static final ArrayList<String> linhas = new ArrayList<String>();

    private Diagnostico() { }

    public static synchronized void anotar(String s) {
        linhas.add(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + s);
        if (linhas.size() > 200) linhas.remove(0);
    }

    public static synchronized String texto() {
        StringBuilder b = new StringBuilder("Diagnóstico do Tocador\n");
        for (String l : linhas) b.append(l).append('\n');
        return b.toString();
    }

    /** Endereço encurtado: só o servidor e o começo do caminho (sem parâmetros, que têm assinaturas). */
    public static String curto(String url) {
        if (url == null) return "(nenhum)";
        String s = url.replaceAll("[?#].*$", "");
        s = s.replaceFirst("^https?://", "");
        return s.length() > 70 ? s.substring(0, 70) + "…" : s;
    }
}
