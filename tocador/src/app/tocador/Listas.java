package app.tocador;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Listas de reprodução importadas da internet ou do aparelho: M3U/M3U8 (inclusive as de IPTV,
 * com logotipo e grupo), PLS e listas simples de endereços. Cada lista tem um estilo (músicas, rádios,
 * TVs, câmeras ou outros), que muda o ícone e o comportamento (músicas tocam em sequência).
 * Java puro (sem Android), testado fora do celular.
 */
public final class Listas {

    public static final String[][] TIPOS = {
        { "musica", "Músicas" }, { "radio", "Rádios" }, { "tv", "TVs" }, { "camera", "Câmeras" }, { "outro", "Outros" },
    };

    public static final class Item {
        public final String nome, url, logo, grupo;
        public Item(String nome, String url, String logo, String grupo) { this.nome = nome; this.url = url; this.logo = logo; this.grupo = grupo; }
        @Override public String toString() { return grupo + " | " + nome + " | " + url; }
    }

    public static final class Lista {
        public String id, nome, origem, tipo;
        public long atualizada;
        public List<Item> itens = new ArrayList<Item>();
    }

    /** O texto é uma transmissão HLS (um vídeo/rádio só), e não uma lista de canais? */
    public static boolean ehTransmissao(String texto) {
        return texto.contains("#EXT-X-TARGETDURATION") || texto.contains("#EXT-X-STREAM-INF") || texto.contains("#EXT-X-MEDIA-SEQUENCE");
    }

    private static final Pattern ATRIB = Pattern.compile("([A-Za-z0-9_-]+)=\"([^\"]*)\"");

    /** Lê M3U/M3U8, PLS ou uma lista simples (um endereço por linha). Endereços relativos usam "base". */
    public static List<Item> ler(String texto, String base) {
        List<Item> r = new ArrayList<Item>();
        if (texto == null) return r;
        if (texto.length() > 0 && texto.charAt(0) == '﻿') texto = texto.substring(1);
        String[] linhas = texto.replace("\r", "").split("\n");
        boolean pls = false;
        for (String l : linhas) if (l.trim().equalsIgnoreCase("[playlist]")) { pls = true; break; }
        if (pls) {
            Map<String, String[]> m = new LinkedHashMap<String, String[]>();
            for (String l : linhas) {
                l = l.trim();
                int eq = l.indexOf('=');
                if (eq < 0) continue;
                String k = l.substring(0, eq).toLowerCase(Locale.ROOT), v = l.substring(eq + 1).trim();
                String n = k.replaceAll("^(file|title)", "");
                if (n.length() == 0 || !n.matches("\\d+")) continue;
                String[] par = m.get(n);
                if (par == null) { par = new String[2]; m.put(n, par); }
                if (k.startsWith("file")) par[0] = v; else if (k.startsWith("title")) par[1] = v;
            }
            for (String[] p : m.values()) if (p[0] != null) r.add(new Item(p[1] != null && p[1].length() > 0 ? p[1] : nomeDe(p[0]), resolver(p[0], base), "", ""));
            return r;
        }
        String nome = null, logo = "", grupo = "", grupoAtual = "";
        for (String l : linhas) {
            l = l.trim();
            if (l.length() == 0) continue;
            if (l.startsWith("#EXTINF")) {
                int virg = indiceVirgulaNome(l);
                nome = virg >= 0 ? l.substring(virg + 1).trim() : "";
                Matcher a = ATRIB.matcher(virg >= 0 ? l.substring(0, virg) : l);
                logo = ""; grupo = "";
                while (a.find()) {
                    String k = a.group(1).toLowerCase(Locale.ROOT);
                    if (k.equals("tvg-logo") || k.equals("logo")) logo = a.group(2);
                    else if (k.equals("group-title")) grupo = a.group(2);
                    else if (k.equals("tvg-name") && nome.length() == 0) nome = a.group(2);
                }
                continue;
            }
            if (l.startsWith("#EXTGRP:")) { grupoAtual = l.substring(8).trim(); continue; }
            if (l.startsWith("#")) continue;
            String u = resolver(l, base);
            String g = grupo.length() > 0 ? grupo : grupoAtual;
            r.add(new Item(nome != null && nome.length() > 0 ? nome : nomeDe(u), u, logo, g));
            nome = null; logo = ""; grupo = "";
        }
        return r;
    }

    /** Vírgula que separa os atributos do nome (fora das aspas). */
    private static int indiceVirgulaNome(String l) {
        boolean aspas = false;
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (c == '"') aspas = !aspas;
            else if (c == ',' && !aspas) return i;
        }
        return -1;
    }

    static String resolver(String u, String base) {
        if (u.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*") || base == null || base.length() == 0) return u;
        try { return new URL(new URL(base), u).toString(); } catch (Exception e) { return u; }
    }

    static String nomeDe(String u) {
        String s = u.replaceAll("[?#].*$", "");
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int b = s.lastIndexOf('/');
        s = b >= 0 ? s.substring(b + 1) : s;
        try { s = java.net.URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { }
        return s.length() > 0 ? s : u;
    }

    /** Palpite do estilo da lista pelo conteúdo (o usuário pode trocar). */
    public static String adivinharTipo(List<Item> itens) {
        int musica = 0, radio = 0, tv = 0, cam = 0;
        for (Item i : itens) {
            String t = (i.nome + " " + i.grupo + " " + i.url).toLowerCase(Locale.ROOT);
            if (t.matches(".*\\.(mp3|flac|ogg|m4a|opus|wav|aac)(\\?.*)?$")) musica++;
            if (t.contains("radio") || t.contains("rádio") || t.contains(" fm") || t.contains("icecast") || t.contains("shoutcast") || t.contains("/stream")) radio++;
            if (t.contains("tv") || t.contains("canal") || t.contains("channel") || t.contains("news") || t.contains("notícias")) tv++;
            if (t.contains("cam") || t.contains("rtsp://") || t.contains("câmera") || t.contains("camera") || t.contains("webcam")) cam++;
        }
        int max = Math.max(Math.max(musica, radio), Math.max(tv, cam));
        if (max == 0) return "outro";
        if (max == cam) return "camera";
        if (max == musica) return "musica";
        if (max == radio) return "radio";
        return "tv";
    }

    // ------------------------------------------------------------ baixar

    /** Baixa o texto de uma lista (aceita gzip; segue redirecionamentos). */
    public static String baixar(String url) throws Exception {
        String atual = url;
        for (int r = 0; r < 6; r++) {
            HttpURLConnection c = (HttpURLConnection) new URL(atual).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "VLC/3.0.20 LibVLC/3.0.20");
            c.setRequestProperty("Accept-Encoding", "gzip");
            int cod = c.getResponseCode();
            if (cod >= 300 && cod < 400 && c.getHeaderField("Location") != null) {
                atual = new URL(new URL(atual), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (cod != 200) throw new Exception("HTTP " + cod);
            InputStream in = c.getInputStream();
            if ("gzip".equalsIgnoreCase(c.getContentEncoding()) || url.endsWith(".gz")) in = new GZIPInputStream(in);
            try { return ler(in, 30 << 20); } finally { c.disconnect(); }
        }
        throw new Exception("redirecionamentos demais");
    }

    static String ler(InputStream in, int limite) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            b.write(buf, 0, n);
            if (b.size() > limite) throw new Exception("lista grande demais");
        }
        in.close();
        return new String(b.toByteArray(), "UTF-8");
    }

    // ------------------------------------------------------------ guardar

    private final File arquivo;
    private final List<Lista> listas = new ArrayList<Lista>();

    public Listas(File pasta) {
        arquivo = new File(pasta, "listas.json");
        carregar();
    }

    public synchronized List<Lista> todas() { return new ArrayList<Lista>(listas); }

    public synchronized Lista porId(String id) {
        for (Lista l : listas) if (l.id.equals(id)) return l;
        return null;
    }

    public synchronized Lista adicionar(String nome, String origem, String tipo, List<Item> itens) {
        Lista l = null;
        for (Lista x : listas) if (origem != null && origem.length() > 0 && origem.equals(x.origem)) l = x;
        if (l == null) { l = new Lista(); l.id = Long.toString(System.currentTimeMillis(), 36); listas.add(0, l); }
        l.nome = nome; l.origem = origem; l.tipo = tipo; l.itens = itens; l.atualizada = System.currentTimeMillis();
        salvar();
        return l;
    }

    public synchronized void renomear(String id, String nome, String tipo) {
        Lista l = porId(id);
        if (l == null) return;
        if (nome != null && nome.trim().length() > 0) l.nome = nome.trim();
        if (tipo != null && tipo.length() > 0) l.tipo = tipo;
        salvar();
    }

    public synchronized void apagar(String id) {
        Lista l = porId(id);
        if (l != null) { listas.remove(l); salvar(); }
    }

    /** Itens filtrados (texto e grupo), em páginas, para listas de milhares de canais. */
    public synchronized JSONObject itens(String id, String filtro, String grupo, int inicio, int qtd) throws Exception {
        Lista l = porId(id);
        JSONObject o = new JSONObject();
        if (l == null) return o;
        String f = filtro == null ? "" : semAcento(filtro.trim().toLowerCase(Locale.ROOT));
        JSONArray a = new JSONArray();
        int total = 0;
        LinkedHashMap<String, Integer> grupos = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < l.itens.size(); i++) {
            Item it = l.itens.get(i);
            String g = it.grupo.length() > 0 ? it.grupo : "Sem grupo";
            grupos.put(g, (grupos.containsKey(g) ? grupos.get(g) : 0) + 1);
            if (grupo != null && grupo.length() > 0 && !grupo.equals(g)) continue;
            if (f.length() > 0 && !semAcento((it.nome + " " + it.grupo).toLowerCase(Locale.ROOT)).contains(f)) continue;
            if (total >= inicio && a.length() < qtd)
                a.put(new JSONObject().put("i", i).put("nome", it.nome).put("logo", it.logo).put("grupo", it.grupo).put("url", it.url));
            total++;
        }
        JSONArray gs = new JSONArray();
        if (grupos.size() > 1) for (Map.Entry<String, Integer> e : grupos.entrySet()) gs.put(new JSONObject().put("nome", e.getKey()).put("n", e.getValue()));
        return o.put("id", l.id).put("nome", l.nome).put("tipo", l.tipo).put("total", total).put("itens", a).put("grupos", gs).put("todos", l.itens.size());
    }

    static String semAcento(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private void carregar() {
        try {
            if (!arquivo.exists()) return;
            FileInputStream in = new FileInputStream(arquivo);
            JSONArray a = new JSONArray(ler(in, 64 << 20));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Lista l = new Lista();
                l.id = o.getString("id"); l.nome = o.optString("nome"); l.origem = o.optString("origem");
                l.tipo = o.optString("tipo", "outro"); l.atualizada = o.optLong("atualizada");
                JSONArray it = o.optJSONArray("itens");
                if (it != null) for (int k = 0; k < it.length(); k++) {
                    JSONArray x = it.getJSONArray(k);
                    l.itens.add(new Item(x.optString(0), x.optString(1), x.optString(2), x.optString(3)));
                }
                listas.add(l);
            }
        } catch (Exception e) { }
    }

    private void salvar() {
        try {
            JSONArray a = new JSONArray();
            for (Lista l : listas) {
                JSONArray it = new JSONArray();
                for (Item x : l.itens) it.put(new JSONArray().put(x.nome).put(x.url).put(x.logo).put(x.grupo));
                a.put(new JSONObject().put("id", l.id).put("nome", l.nome).put("origem", l.origem).put("tipo", l.tipo)
                        .put("atualizada", l.atualizada).put("itens", it));
            }
            File tmp = new File(arquivo.getPath() + ".tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            o.write(a.toString().getBytes("UTF-8"));
            o.close();
            tmp.renameTo(arquivo);
        } catch (Exception e) { }
    }
}
