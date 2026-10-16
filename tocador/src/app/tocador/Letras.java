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
 * Letras sincronizadas (linha por linha, com horário) do LRCLIB, um catálogo aberto e gratuito (lrclib.net).
 * Descobre artista e música pelo título do vídeo/arquivo, escolhe a versão de duração mais próxima e
 * devolve as linhas com o horário em milissegundos. Java puro, testável fora do celular.
 */
public final class Letras {

    private Letras() { }

    static String servidor = "https://lrclib.net";
    private static final Map<String, JSONObject> cache = new HashMap<String, JSONObject>();

    // ------------------------------------------------------------ título → artista e música

    private static final Pattern ENTRE = Pattern.compile("\\s*[\\(\\[\\{【][^\\)\\]\\}】]*[\\)\\]\\}】]");
    private static final Pattern LIXO = Pattern.compile("(?i)(official|oficial|video|vídeo|videoclipe|clipe|clip|lyric|lyrics|letra|legendad|audio|áudio|visualizer|hd|hq|4k|remaster|mv|m/v|en vivo|ao vivo|live|explicit|tradução|traducao|sub |prod\\.)");

    /** {artista, música} a partir do título (e do canal, quando o título não traz o artista). */
    public static String[] separar(String titulo, String canal) {
        String t = titulo == null ? "" : titulo.trim();
        // tira só os parênteses/colchetes de "lixo" (Official Video, Clipe Oficial, Lyrics…)
        Matcher m = ENTRE.matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) m.appendReplacement(sb, LIXO.matcher(m.group()).find() ? "" : Matcher.quoteReplacement(m.group()));
        m.appendTail(sb);
        t = sb.toString().replaceAll("(?i)\\s*[|•]\\s*(official|oficial|video|clipe|lyric).*$", "").replaceAll("\\s+", " ").trim();
        String artista = "", musica = t;
        String[] seps = { " - ", " – ", " — ", " | ", " ~ " };
        for (String s : seps) {
            int i = t.indexOf(s);
            if (i > 0) { artista = t.substring(0, i).trim(); musica = t.substring(i + s.length()).trim(); break; }
        }
        if (artista.length() == 0 && canal != null) {
            artista = canal.replaceAll("(?i)\\s*-\\s*topic$", "").replaceAll("(?i)vevo$", "")
                    .replaceAll("(?i)\\s*(official|oficial|tv|music|channel|canal)$", "").trim();
        }
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
        try {
            List<JSONObject> candidatos = new ArrayList<JSONObject>();
            if (artista.length() > 0) juntar(candidatos, pedir("/api/search?track_name=" + enc(musica) + "&artist_name=" + enc(artista)));
            if (candidatos.isEmpty()) juntar(candidatos, pedir("/api/search?q=" + enc((artista + " " + musica).trim())));
            JSONObject melhor = null;
            double melhorNota = Double.MAX_VALUE;
            for (JSONObject c : candidatos) {
                String sinc = c.optString("syncedLyrics", "");
                if (c.isNull("syncedLyrics") || sinc.length() == 0) continue;
                double dif = duracaoSeg > 0 ? Math.abs(c.optDouble("duration", 0) - duracaoSeg) : 0;
                double nota = dif + (semAcento(c.optString("artistName")).contains(semAcento(artista)) ? 0 : 30);
                if (nota < melhorNota) { melhorNota = nota; melhor = c; }
            }
            if (melhor != null) {
                r.put("sincronizada", true).put("linhas", lrc(melhor.optString("syncedLyrics")))
                 .put("artista", melhor.optString("artistName")).put("musica", melhor.optString("trackName"))
                 .put("duracao", melhor.optDouble("duration", 0))
                 .put("diferenca", duracaoSeg > 0 ? Math.round(melhor.optDouble("duration", 0) - duracaoSeg) : 0);
            } else {
                for (JSONObject c : candidatos) if (!c.isNull("plainLyrics") && c.optString("plainLyrics").length() > 0) {
                    r.put("sincronizada", false).put("texto", c.optString("plainLyrics"))
                     .put("artista", c.optString("artistName")).put("musica", c.optString("trackName"));
                    break;
                }
                if (!r.has("sincronizada")) r.put("erro", "sem letra");
            }
        } catch (Exception e) {
            try { r.put("erro", e.getMessage() == null ? e.toString() : e.getMessage()); } catch (Exception x) { }
            return r; // falha de rede: não guarda, tenta de novo depois
        }
        synchronized (cache) { cache.put(chave, r); }
        return r;
    }

    private static void juntar(List<JSONObject> l, JSONArray a) {
        if (a == null) return;
        for (int i = 0; i < a.length() && i < 25; i++) { JSONObject o = a.optJSONObject(i); if (o != null) l.add(o); }
    }

    private static JSONArray pedir(String caminho) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(servidor + caminho).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "Tocador/4.0 (app Android de código aberto)");
        try {
            if (c.getResponseCode() == 404) return new JSONArray();
            if (c.getResponseCode() != 200) throw new Exception("LRCLIB: HTTP " + c.getResponseCode());
            return new JSONArray(Listas.ler(c.getInputStream(), 4 << 20));
        } finally { c.disconnect(); }
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }

    static String semAcento(String s) {
        return java.text.Normalizer.normalize(s == null ? "" : s.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
