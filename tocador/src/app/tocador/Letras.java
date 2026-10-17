package app.tocador;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Letras sincronizadas (linha por linha, com horário). Procura primeiro no LRCLIB, um catálogo aberto e
 * gratuito (lrclib.net), e, se não achar, no catálogo do NetEase Cloud Music, que tem muita música internacional.
 * Descobre artista e música pelo título do arquivo, escolhe a versão de duração mais próxima e
 * devolve as linhas com o horário em milissegundos. Java puro, testável fora do celular.
 */
public final class Letras {

    private Letras() { }

    static String servidor = "https://lrclib.net";
    static String netease = "https://music.163.com";
    private static final Map<String, JSONObject> cache = new HashMap<String, JSONObject>();

    // ------------------------------------------------------------ título → artista e música

    private static final Pattern ENTRE = Pattern.compile("\\s*[\\(\\[\\{【][^\\)\\]\\}】]*[\\)\\]\\}】]");
    private static final Pattern LIXO = Pattern.compile("(?i)(official|oficial|video|vídeo|videoclipe|clipe|clip|lyric|lyrics|letra|legendad|audio|áudio|visualizer|hd|hq|4k|remaster|mv|m/v|en vivo|ao vivo|live|explicit|tradução|traducao|sub |prod\\.|feat|ft\\.|mono|stereo|mix|version|versão|edit|demo|take \\d|bonus|acoustic|acústico|anthology|deluxe)");
    /** Sufixos de versão depois de um traço: "Here Comes The Sun - Remastered 2009", "Hey Jude - 2015 Mix", "Yesterday - Live". */
    private static final Pattern VERSAO = Pattern.compile("(?i)\\s+[-–—]\\s+[^-–—]*?(\\bremaster\\w*|\\bmix\\b|\\bmono\\b|\\bstereo\\b|\\bversion\\b|\\bvers[aã]o\\b|\\blive\\b|\\bao vivo\\b|\\ben vivo\\b|\\bedit\\b|\\bdemo\\b|\\btake\\s*\\d+|\\bbonus\\b|\\bacoustic\\b|\\bac[uú]stico\\b|\\binstrumental\\b|\\bsingle\\b|\\banthology\\b|\\bfrom\\s|\\bdo filme\\b|\\bsession\\w*|\\bdeluxe\\b|^\\s*\\d{4}\\s*$)[^-–—]*$");
    /** Número da faixa no começo: "01 - ", "03. ", "7) ", "02 ". */
    private static final Pattern FAIXA = Pattern.compile("^\\s*(\\d{1,3}\\s*[.\\-–_)]\\s*|0\\d\\s+)");

    /** Tira do título o que atrapalha a busca: número da faixa, "(Official Video)", "- Remastered 2009"… */
    public static String limpar(String titulo) {
        String t = titulo == null ? "" : titulo.trim();
        Matcher m = ENTRE.matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, LIXO.matcher(m.group()).find() ? "" : Matcher.quoteReplacement(m.group()));
        m.appendTail(sb);
        t = sb.toString().replaceAll("(?i)\\s*[|•]\\s*(official|oficial|video|clipe|lyric).*$", "").replaceAll("\\s+", " ").trim();
        for (int i = 0; i < 3; i++) { String u = VERSAO.matcher(t).replaceAll("").trim(); if (u.equals(t) || u.length() == 0) break; t = u; }
        t = t.replaceAll("\\s+[-–—]\\s+\\d{4}$", "").trim();
        String sem = FAIXA.matcher(t).replaceFirst("").trim();
        if (sem.length() > 0) t = sem;
        return t;
    }

    /** {artista, música} a partir do título (e do canal, quando o título não traz o artista). */
    public static String[] separar(String titulo, String canal) {
        String t = limpar(titulo);
        String artista = "", musica = t;
        String[] seps = { " - ", " – ", " — ", " | ", " ~ " };
        for (String s : seps) {
            int i = t.indexOf(s);
            if (i > 0) { artista = t.substring(0, i).trim(); musica = limpar(t.substring(i + s.length())); break; }
        }
        if (artista.length() == 0 && canal != null) {
            artista = canal.replaceAll("(?i)\\s*-\\s*topic$", "").replaceAll("(?i)vevo$", "")
                    .replaceAll("(?i)\\s*(official|oficial|tv|music|channel|canal)$", "").trim();
        }
        if (artista.length() > 0 && canal != null && canal.trim().length() > 0 && !mesmoArtista(canal, artista)
                && mesmoArtista(canal, musica)) { String x = artista; artista = musica; musica = x; } // "Música - Artista"
        musica = musica.replaceAll("(?i)\\s+(ft\\.?|feat\\.?|featuring|part\\.?|participação)\\s+.*$", "").replaceAll("^[\"'“”]+|[\"'“”]+$", "").trim();
        artista = artista.replaceAll("(?i)\\s+(ft\\.?|feat\\.?|featuring|part\\.?)\\s+.*$", "").trim();
        return new String[] { artista, musica };
    }

    // ------------------------------------------------------------ LRC → linhas

    private static final Pattern MARCA = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

    /** Converte o formato LRC ("[01:23.45] texto") em linhas [{t: ms, x: texto}] em ordem. */
    public static JSONArray lrc(String texto) {
        List<long[]> ordem = new ArrayList<long[]>();
        List<String> frases = new ArrayList<String>();
        long ajuste = 0;
        if (texto == null) return new JSONArray();
        for (String l : texto.replace("\r", "").split("\n")) {
            Matcher off = Pattern.compile("(?i)^\\[offset:\\s*([+-]?\\d+)\\]").matcher(l.trim());
            if (off.find()) { try { ajuste = Long.parseLong(off.group(1)); } catch (Exception e) { } continue; }
            Matcher m = MARCA.matcher(l);
            List<Long> tempos = new ArrayList<Long>();
            int fim = 0;
            while (m.find() && m.start() == fim) {
                long ms = Long.parseLong(m.group(1)) * 60000 + Long.parseLong(m.group(2)) * 1000;
                if (m.group(3) != null) {
                    String f = m.group(3);
                    ms += f.length() == 1 ? Integer.parseInt(f) * 100 : f.length() == 2 ? Integer.parseInt(f) * 10 : Integer.parseInt(f);
                }
                tempos.add(ms);
                fim = m.end();
            }
            if (tempos.isEmpty()) continue;
            String frase = l.substring(fim).trim();
            for (long t : tempos) { ordem.add(new long[] { t, frases.size() }); frases.add(frase); }
        }
        java.util.Collections.sort(ordem, new java.util.Comparator<long[]>() { public int compare(long[] a, long[] b) { return Long.compare(a[0], b[0]); } });
        JSONArray r = new JSONArray();
        try {
            for (long[] o : ordem) r.put(new JSONObject().put("t", Math.max(0, o[0] - ajuste)).put("x", frases.get((int) o[1])));
        } catch (Exception e) { }
        return r;
    }

    // ------------------------------------------------------------ busca

    /**
     * Busca a letra. duracaoSeg = duração do vídeo/arquivo (0 se não souber).
     * Devolve {sincronizada: bool, linhas: [...], artista, musica, duracao, diferenca} ou {erro}.
     */
    public static JSONObject buscar(String artista, String musica, double duracaoSeg) {
        String chave = (artista + "|" + musica + "|" + Math.round(duracaoSeg)).toLowerCase(Locale.ROOT);
        synchronized (cache) { if (cache.containsKey(chave)) return cache.get(chave); }
        JSONObject r = new JSONObject();
        Exception falha = null;
        try {
            // LRCLIB: artista + música; depois tudo junto; depois só a música (o artista confere na nota)
            List<JSONObject> candidatos = new ArrayList<JSONObject>();
            String[] buscas = artista.length() > 0
                    ? new String[] { "/api/search?track_name=" + enc(musica) + "&artist_name=" + enc(artista), "/api/search?q=" + enc(artista + " " + musica), "/api/search?track_name=" + enc(musica) }
                    : new String[] { "/api/search?q=" + enc(musica) };
            JSONObject melhor = null;
            for (String b : buscas) {
                try { juntar(candidatos, new JSONArray(baixar(servidor + b, null))); } catch (Exception e) { falha = e; }
                melhor = escolher(candidatos, artista, musica, duracaoSeg);
                if (melhor != null && nota(melhor, artista, musica, duracaoSeg) <= 8) break;
            }
            if (melhor != null) {
                r.put("sincronizada", true).put("linhas", lrc(melhor.optString("syncedLyrics")))
                 .put("artista", melhor.optString("artistName")).put("musica", limpar(melhor.optString("trackName")))
                 .put("duracao", melhor.optDouble("duration", 0)).put("fonte", "LRCLIB")
                 .put("diferenca", duracaoSeg > 0 ? Math.round(melhor.optDouble("duration", 0) - duracaoSeg) : 0);
            } else {
                JSONObject n = null;
                try { n = doNetease(artista, musica, duracaoSeg); } catch (Exception e) { falha = e; }
                if (n != null) r = n;
                else {
                    for (JSONObject c : candidatos) if (!c.isNull("plainLyrics") && c.optString("plainLyrics").length() > 0) {
                        r.put("sincronizada", false).put("texto", c.optString("plainLyrics"))
                         .put("artista", c.optString("artistName")).put("musica", c.optString("trackName"));
                        break;
                    }
                    if (!r.has("sincronizada")) {
                        // nada achado: se foi falta de rede, avisa e não guarda (tenta de novo depois)
                        if (falha != null && candidatos.isEmpty()) { r.put("erro", falha.getMessage() == null ? falha.toString() : falha.getMessage()); return r; }
                        r.put("erro", "sem letra");
                    }
                }
            }
        } catch (Exception e) {
            try { r.put("erro", e.getMessage() == null ? e.toString() : e.getMessage()); } catch (Exception x) { }
            return r;
        }
        synchronized (cache) { cache.put(chave, r); }
        return r;
    }

    /** Nota de um candidato do LRCLIB (menor é melhor): diferença de duração, mais multas se artista ou nome não batem. */
    private static double nota(JSONObject c, String artista, String musica, double duracaoSeg) {
        return nota(c.optString("artistName"), c.optString("trackName"), c.optDouble("duration", 0), artista, musica, duracaoSeg);
    }

    private static double nota(String artC, String musC, double durC, String artista, String musica, double duracaoSeg) {
        double n = duracaoSeg > 0 && durC > 0 ? Math.abs(durC - duracaoSeg) : 3;
        if (artista.length() > 0 && !mesmoArtista(artC, artista)) n += 30;
        if (!parecido(musC, musica)) n += 20;
        return n;
    }

    private static JSONObject escolher(List<JSONObject> l, String artista, String musica, double duracaoSeg) {
        JSONObject melhor = null;
        double melhorNota = 45; // acima disso é outra música ou outra gravação
        for (JSONObject c : l) {
            if (c.isNull("syncedLyrics") || c.optString("syncedLyrics", "").length() == 0) continue;
            double n = nota(c, artista, musica, duracaoSeg);
            if (n < melhorNota) { melhorNota = n; melhor = c; }
        }
        return melhor;
    }

    /** Segundo catálogo: NetEase Cloud Music (busca pública e letra em LRC). */
    private static JSONObject doNetease(String artista, String musica, double duracaoSeg) throws Exception {
        String ref = netease + "/";
        JSONObject j = new JSONObject(baixar(netease + "/api/search/get?type=1&limit=15&s=" + enc((artista + " " + musica).trim()), ref));
        JSONArray songs = j.optJSONObject("result") == null ? null : j.optJSONObject("result").optJSONArray("songs");
        if (songs == null) return null;
        JSONObject melhor = null;
        String melhorArt = "";
        double melhorNota = 25;
        for (int i = 0; i < songs.length(); i++) {
            JSONObject s = songs.optJSONObject(i);
            if (s == null) continue;
            JSONArray as = s.optJSONArray("artists");
            String art = as != null && as.length() > 0 ? as.optJSONObject(0).optString("name") : "";
            double n = nota(art, s.optString("name"), s.optLong("duration") / 1000.0, artista, musica, duracaoSeg);
            if (n < melhorNota) { melhorNota = n; melhor = s; melhorArt = art; }
        }
        if (melhor == null) return null;
        JSONObject l = new JSONObject(baixar(netease + "/api/song/lyric?lv=1&kv=1&tv=-1&id=" + melhor.optLong("id"), ref));
        String texto = l.optJSONObject("lrc") == null ? "" : l.optJSONObject("lrc").optString("lyric");
        JSONArray linhas = lrc(texto), boas = new JSONArray();
        for (int i = 0; i < linhas.length(); i++) {
            JSONObject o = linhas.getJSONObject(i);
            // tira os créditos que o NetEase põe como linhas ("作词 : …", "Composed by : …")
            if (o.optString("x").matches("(?i)^\\s*(作词|作曲|编曲|制作人?|词|曲|lyrics? by|composed by|written by|producer)\\s*[:：].*")) continue;
            boas.put(o);
        }
        if (boas.length() < 4) return null; // "纯音乐" (instrumental) ou letra sem horários
        double dur = melhor.optLong("duration") / 1000.0;
        return new JSONObject().put("sincronizada", true).put("linhas", boas).put("artista", melhorArt)
                .put("musica", limpar(melhor.optString("name"))).put("duracao", dur).put("fonte", "NetEase")
                .put("diferenca", duracaoSeg > 0 ? Math.round(dur - duracaoSeg) : 0);
    }

    static boolean mesmoArtista(String a, String b) {
        String x = semAcento(a).replaceAll("^the\\s+", "").replaceAll("[^\\p{L}\\p{N}]", ""), y = semAcento(b).replaceAll("^the\\s+", "").replaceAll("[^\\p{L}\\p{N}]", "");
        return x.length() > 0 && y.length() > 0 && (x.contains(y) || y.contains(x));
    }

    private static boolean parecido(String a, String b) {
        String x = semAcento(limpar(a)).replaceAll("[^\\p{L}\\p{N}]", ""), y = semAcento(limpar(b)).replaceAll("[^\\p{L}\\p{N}]", "");
        return x.length() > 0 && y.length() > 0 && (x.contains(y) || y.contains(x));
    }

    private static void juntar(List<JSONObject> l, JSONArray a) {
        if (a == null) return;
        for (int i = 0; i < a.length() && i < 25; i++) { JSONObject o = a.optJSONObject(i); if (o != null) l.add(o); }
    }

    private static String baixar(String endereco, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(endereco).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", referer == null ? "Tocador/4.1 (app Android de código aberto)" : "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36");
        if (referer != null) c.setRequestProperty("Referer", referer);
        try {
            if (c.getResponseCode() == 404) return "[]";
            if (c.getResponseCode() != 200) throw new Exception(new URL(endereco).getHost() + ": HTTP " + c.getResponseCode());
            return Listas.ler(c.getInputStream(), 4 << 20);
        } finally { c.disconnect(); }
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }

    static String semAcento(String s) {
        return java.text.Normalizer.normalize(s == null ? "" : s.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
