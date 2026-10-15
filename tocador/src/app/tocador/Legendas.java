package app.tocador;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converte arquivos de legenda em texto puro: uma fala por linha, sem horários nem marcações.
 * Formatos: json3 e srv3/XML (YouTube), WebVTT e SRT (demais sites).
 * Java puro (sem Android) para poder ser testado fora do celular.
 */
public final class Legendas {

    private Legendas() { }

    /** Detecta o formato pelo conteúdo e devolve o texto puro (ou "" se não houver falas). */
    public static String paraTexto(String corpo) {
        if (corpo == null) return "";
        String c = corpo.trim();
        if (c.startsWith("﻿")) c = c.substring(1);
        if (c.startsWith("{")) return deJson3(c);
        if (c.startsWith("WEBVTT")) return deVtt(c);
        if (c.startsWith("<")) return deXml(c);
        if (Pattern.compile("(?m)^\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->").matcher(c).find()) return deVtt(c);
        return limpar(c.split("\r?\n"));
    }

    /** Formato json3 do YouTube: events[].segs[].utf8. */
    public static String deJson3(String json) {
        try {
            JSONArray ev = new JSONObject(json).optJSONArray("events");
            if (ev == null) return "";
            ArrayList<String> linhas = new ArrayList<String>();
            StringBuilder atual = new StringBuilder();
            for (int i = 0; i < ev.length(); i++) {
                JSONObject e = ev.optJSONObject(i);
                if (e == null) continue;
                JSONArray segs = e.optJSONArray("segs");
                if (segs == null) continue;
                StringBuilder t = new StringBuilder();
                for (int k = 0; k < segs.length(); k++) t.append(segs.optJSONObject(k) == null ? "" : segs.optJSONObject(k).optString("utf8", ""));
                String s = t.toString();
                if (e.optInt("aAppend", 0) == 1) {
                    // Legenda automática: palavras chegam aos poucos; "\n" fecha a linha.
                    if (s.contains("\n")) { linhas.add(atual.toString()); atual.setLength(0); }
                    else atual.append(s);
                    continue;
                }
                if (atual.length() > 0) { linhas.add(atual.toString()); atual.setLength(0); }
                for (String l : s.split("\n")) linhas.add(l);
            }
            if (atual.length() > 0) linhas.add(atual.toString());
            return limpar(linhas.toArray(new String[0]));
        } catch (Exception e) {
            return "";
        }
    }

    /** srv3 (&lt;p&gt;…&lt;/p&gt;) e o XML antigo (&lt;text&gt;…&lt;/text&gt;) do YouTube. */
    public static String deXml(String xml) {
        ArrayList<String> linhas = new ArrayList<String>();
        Matcher m = Pattern.compile("(?s)<(p|text)\\b[^>]*>(.*?)</\\1>").matcher(xml);
        while (m.find()) {
            String s = m.group(2).replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", "");
            for (String l : entidades(entidades(s)).split("\n")) linhas.add(l); // o XML antigo escapa duas vezes
        }
        return limpar(linhas.toArray(new String[0]));
    }

    /** WebVTT e SRT: tira cabeçalho, números de bloco, horários e marcações. */
    public static String deVtt(String vtt) {
        ArrayList<String> linhas = new ArrayList<String>();
        String[] ls = vtt.split("\r?\n");
        boolean nota = false;
        for (int i = 0; i < ls.length; i++) {
            String l = ls[i].trim();
            if (l.length() == 0) { nota = false; continue; }
            if (nota) continue;
            // Cabeçalho (WEBVTT + linhas como "Kind:" e "Language:") e blocos STYLE/REGION vão até a próxima linha vazia.
            if (l.startsWith("WEBVTT") || l.startsWith("STYLE") || l.startsWith("REGION")) { nota = true; continue; }
            if (l.startsWith("NOTE")) { nota = true; continue; }
            if (l.contains("-->")) continue;
            if (l.matches("\\d+") && i + 1 < ls.length && ls[i + 1].contains("-->")) continue; // número do bloco (SRT)
            if (i + 1 < ls.length && ls[i + 1].contains("-->") && !l.contains(" ")) continue;   // id do bloco (VTT)
            linhas.add(entidades(l.replaceAll("<[^>]+>", "")));
        }
        return limpar(linhas.toArray(new String[0]));
    }

    /** Espaços normalizados, sem linhas vazias e sem repetições seguidas (comuns em legendas que "rolam"). */
    static String limpar(String[] linhas) {
        StringBuilder out = new StringBuilder();
        String anterior = null;
        for (String l : linhas) {
            String s = l.replace(' ', ' ').replaceAll("\\s+", " ").trim();
            if (s.length() == 0 || s.equals(anterior)) continue;
            if (anterior != null && anterior.endsWith(s)) continue;
            out.append(s).append('\n');
            anterior = s;
        }
        return out.toString().trim();
    }

    static String entidades(String s) {
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ");
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int cp;
            try { cp = Integer.parseInt(m.group(2), m.group(1).length() > 0 ? 16 : 10); } catch (Exception e) { cp = '?'; }
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString().replace("&amp;", "&");
    }

    /** Ajusta o endereço de legenda do YouTube: formato json3 e, se pedido, tradução automática. */
    public static String urlYoutube(String url, String traduzirPara) {
        int q = url.indexOf('?');
        StringBuilder u = new StringBuilder(q < 0 ? url : url.substring(0, q));
        char sep = '?';
        if (q >= 0) for (String par : url.substring(q + 1).split("&")) {
            if (par.length() == 0 || par.startsWith("fmt=") || par.startsWith("tlang=")) continue;
            u.append(sep).append(par); sep = '&';
        }
        u.append(sep).append("fmt=json3");
        if (traduzirPara != null && traduzirPara.length() > 0) u.append("&tlang=").append(traduzirPara);
        return u.toString();
    }

    /** Baixa o arquivo de legenda (texto) com o mesmo navegador/cookies do site. */
    public static String baixar(String url, String ua, String cookie, String referer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            if (ua != null) c.setRequestProperty("User-Agent", ua);
            if (cookie != null && cookie.length() > 0) c.setRequestProperty("Cookie", cookie);
            if (referer != null) c.setRequestProperty("Referer", referer);
            int cod = c.getResponseCode();
            if (cod >= 400) throw new IOException("o servidor recusou (erro " + cod + ")");
            InputStream in = c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            in.close();
            return new String(b.toByteArray(), "UTF-8");
        } finally { c.disconnect(); }
    }

    /** Lê um parâmetro de um endereço (sem decodificar). */
    public static String parametro(String url, String nome) {
        Matcher m = Pattern.compile("[?&]" + Pattern.quote(nome) + "=([^&#]*)").matcher(url);
        return m.find() ? m.group(1) : null;
    }
}
