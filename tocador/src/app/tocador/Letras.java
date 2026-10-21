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
        List<String> falhas = new ArrayList<String>();
        boolean algumRespondeu = false;
        String simples = "";
        String[] nomes = { "LRCLIB", "Musixmatch", "NetEase", "Kugou" };
        StringBuilder relato = new StringBuilder();
        try {
            // os catálogos em ordem; o primeiro com letra sincronizada da mesma gravação vence
            for (int f = 0; f < 4 && !r.has("linhas"); f++) {
                detalhe.set("");
                String res;
                try {
                    JSONObject x = f == 0 ? doLrclib(artista, musica, duracaoSeg) : f == 1 ? doMusixmatch(artista, musica, duracaoSeg)
                            : f == 2 ? doNetease(artista, musica, duracaoSeg) : doKugou(artista, musica, duracaoSeg);
                    algumRespondeu = true;
                    if (x != null && x.has("linhas")) { r = x; res = "achou"; }
                    else if (x != null && x.optString("texto").length() > 0) { res = "só letra sem horários"; if (simples.length() == 0) simples = x.optString("texto"); }
                    else res = "nada" + (detalhe.get().length() > 0 ? " (" + detalhe.get() + ")" : "");
                } catch (Exception e) {
                    String m = e.getMessage() == null ? e.toString() : e.getMessage();
                    falhas.add(m);
                    res = "falhou: " + m;
                }
                relato.append(relato.length() > 0 ? " · " : "").append(nomes[f]).append(": ").append(res);
            }
            Diagnostico.anotar("Letras \"" + artista + " — " + musica + "\" (" + Math.round(duracaoSeg) + " s) → " + relato);
            if (!r.has("linhas")) {
                if (!algumRespondeu) { r.put("erro", falhas.isEmpty() ? "sem rede" : falhas.get(0)); return r; } // sem rede: não guarda
                r.put("erro", "sem letra");
                if (simples.length() > 0) r.put("sincronizada", false).put("texto", simples);
            }
        } catch (Exception e) {
            try { r.put("erro", e.getMessage() == null ? e.toString() : e.getMessage()); } catch (Exception x) { }
            return r;
        }
        synchronized (cache) { cache.put(chave, r); }
        return r;
    }

    /** Resultado padrão de uma fonte. */
    private static JSONObject achado(String fonte, JSONArray linhas, String art, String mus, double dur, double duracaoSeg) throws Exception {
        return new JSONObject().put("sincronizada", true).put("linhas", linhas).put("artista", art).put("musica", limpar(mus))
                .put("duracao", dur).put("fonte", fonte).put("diferenca", duracaoSeg > 0 && dur > 0 ? Math.round(dur - duracaoSeg) : 0);
    }

    /** Por que a última busca não deu em nada (só para o diagnóstico); um valor por linha de execução. */
    private static final ThreadLocal<String> detalhe = new ThreadLocal<String>();

    /**
     * Nota de um candidato (menor é melhor): diferença de duração, mais multas se artista ou nome não batem.
     * Aceita também o título escrito ao contrário ("Música - Artista"), com uma multa pequena para a ordem certa ganhar o empate.
     */
    private static double nota(String artC, String musC, double durC, String artista, String musica, double duracaoSeg) {
        double base = duracaoSeg > 0 && durC > 0 ? Math.abs(durC - duracaoSeg) : 3;
        double n = base;
        if (artista.length() > 0 && !mesmoArtista(artC, artista)) n += 30;
        if (!parecido(musC, musica)) n += 40;
        if (n >= LIMITE && artista.length() > 0) {
            double t = base + 1;
            if (!mesmoArtista(artC, musica)) t += 30;
            if (!parecido(musC, artista)) t += 40;
            n = Math.min(n, t);
        }
        return n;
    }

    /** Acima disso é outra música, outro artista ou outra gravação (artista diferente só passa com duração quase igual). */
    private static final double LIMITE = 33;

    /** Linhas boas: sem créditos ("作词 : …", "Composed by : …") e com conteúdo suficiente. */
    private static JSONArray linhasBoas(String lrcTexto) throws Exception {
        JSONArray linhas = lrc(lrcTexto), boas = new JSONArray();
        for (int i = 0; i < linhas.length(); i++) {
            JSONObject o = linhas.getJSONObject(i);
            if (o.optString("x").matches("(?i)^\\s*(作词|作曲|编曲|制作人?|词|曲|监制|混音|lyrics? by|composed by|written by|producer|arranged by)\\s*[:：].*")) continue;
            boas.put(o);
        }
        int comTexto = 0;
        for (int i = 0; i < boas.length(); i++) if (boas.getJSONObject(i).optString("x").trim().length() > 0) comTexto++;
        return comTexto >= 4 ? boas : null; // instrumental ("纯音乐") ou letra sem horários
    }

    // ---- 1) LRCLIB: artista + música; depois tudo junto; depois só a música
    private static JSONObject doLrclib(String artista, String musica, double duracaoSeg) throws Exception {
        String[] buscas = artista.length() > 0
                ? new String[] { "/api/search?track_name=" + enc(musica) + "&artist_name=" + enc(artista), "/api/search?q=" + enc(artista + " " + musica), "/api/search?track_name=" + enc(musica) }
                : new String[] { "/api/search?q=" + enc(musica) };
        List<JSONObject> l = new ArrayList<JSONObject>();
        Exception falha = null;
        boolean respondeu = false;
        JSONObject melhor = null;
        double melhorNota = LIMITE, menorNota = 1000;
        int comHorario = 0;
        for (String b : buscas) {
            try { juntar(l, new JSONArray(baixar(servidor + b, null, null))); respondeu = true; } catch (Exception e) { falha = e; }
            comHorario = 0;
            for (JSONObject c : l) {
                if (c.isNull("syncedLyrics") || c.optString("syncedLyrics", "").length() == 0) continue;
                comHorario++;
                double n = nota(c.optString("artistName"), c.optString("trackName"), c.optDouble("duration", 0), artista, musica, duracaoSeg);
                menorNota = Math.min(menorNota, n);
                if (n < melhorNota) { melhorNota = n; melhor = c; }
            }
            if (melhor != null && melhorNota <= 8) break;
        }
        if (!respondeu && falha != null) throw falha;
        if (melhor == null) detalhe.set(l.size() + " resultados, " + comHorario + " com horários" + (comHorario > 0 ? ", melhor nota " + Math.round(menorNota) + " de no máximo " + Math.round(LIMITE) : ""));
        if (melhor != null) {
            JSONArray linhas = linhasBoas(melhor.optString("syncedLyrics"));
            if (linhas != null) return achado("LRCLIB", linhas, melhor.optString("artistName"), melhor.optString("trackName"), melhor.optDouble("duration", 0), duracaoSeg);
        }
        for (JSONObject c : l)
            if (!c.isNull("plainLyrics") && c.optString("plainLyrics").length() > 0
                    && nota(c.optString("artistName"), c.optString("trackName"), c.optDouble("duration", 0), artista, musica, duracaoSeg) < LIMITE)
                return new JSONObject().put("texto", c.optString("plainLyrics"));
        return null;
    }

    // ---- 2) Musixmatch (o maior catálogo, forte em música brasileira), pela API do aplicativo de computador
    static String musixmatch = "https://apic-desktop.musixmatch.com/ws/1.1/";
    private static String mxmToken;
    private static long mxmTokenEm;

    private static JSONObject mxm(String acao, String params) throws Exception {
        for (int tentativa = 0; tentativa < 2; tentativa++) {
            if (mxmToken == null || System.currentTimeMillis() - mxmTokenEm > 6 * 3600 * 1000L) {
                JSONObject t = new JSONObject(baixar(musixmatch + "token.get?app_id=web-desktop-app-v1.0&user_language=en&t=" + System.currentTimeMillis(), null, "AWSELBCORS=0; AWSELB=0"));
                JSONObject m = t.getJSONObject("message");
                String tk = m.optJSONObject("body") == null ? "" : m.optJSONObject("body").optString("user_token");
                if (tk.length() == 0 || tk.startsWith("UpgradeOnly")) throw new Exception("Musixmatch: sem acesso agora (" + m.optJSONObject("header").optInt("status_code") + ")");
                mxmToken = tk;
                mxmTokenEm = System.currentTimeMillis();
            }
            JSONObject r = new JSONObject(baixar(musixmatch + acao + "?app_id=web-desktop-app-v1.0&usertoken=" + enc(mxmToken) + "&t=" + System.currentTimeMillis() + "&" + params, null, "AWSELBCORS=0; AWSELB=0"));
            int cod = r.getJSONObject("message").getJSONObject("header").optInt("status_code");
            if (cod == 401 && tentativa == 0) { mxmToken = null; continue; } // ficha vencida: pega outra
            if (cod != 200) return null;
            return r.getJSONObject("message").optJSONObject("body");
        }
        return null;
    }

    private static JSONObject doMusixmatch(String artista, String musica, double duracaoSeg) throws Exception {
        String p = "page_size=10&page=1&f_has_subtitle=1&s_track_rating=desc&q_track=" + enc(musica) + (artista.length() > 0 ? "&q_artist=" + enc(artista) : "");
        JSONObject b = mxm("track.search", p);
        JSONArray l = b == null ? null : b.optJSONArray("track_list");
        if ((l == null || l.length() == 0) && artista.length() > 0) {
            b = mxm("track.search", "page_size=10&page=1&f_has_subtitle=1&s_track_rating=desc&q=" + enc(artista + " " + musica));
            l = b == null ? null : b.optJSONArray("track_list");
        }
        if (l == null) return null;
        JSONObject melhor = null;
        double melhorNota = LIMITE;
        for (int i = 0; i < l.length(); i++) {
            JSONObject t = l.optJSONObject(i) == null ? null : l.optJSONObject(i).optJSONObject("track");
            if (t == null || t.optInt("has_subtitles") == 0) continue;
            double n = nota(t.optString("artist_name"), t.optString("track_name"), t.optDouble("track_length", 0), artista, musica, duracaoSeg);
            if (n < melhorNota) { melhorNota = n; melhor = t; }
        }
        if (melhor == null) return null;
        JSONObject s = mxm("track.subtitle.get", "subtitle_format=lrc&track_id=" + melhor.optLong("track_id"));
        String corpo = s == null || s.optJSONObject("subtitle") == null ? "" : s.optJSONObject("subtitle").optString("subtitle_body");
        JSONArray linhas = linhasBoas(corpo);
        return linhas == null ? null : achado("Musixmatch", linhas, melhor.optString("artist_name"), melhor.optString("track_name"), melhor.optDouble("track_length", 0), duracaoSeg);
    }

    // ---- 3) NetEase Cloud Music (busca pública e letra em LRC)
    private static JSONObject doNetease(String artista, String musica, double duracaoSeg) throws Exception {
        String ref = netease + "/";
        JSONObject j = new JSONObject(baixar(netease + "/api/search/get?type=1&limit=15&s=" + enc((artista + " " + musica).trim()), ref, null));
        JSONArray songs = j.optJSONObject("result") == null ? null : j.optJSONObject("result").optJSONArray("songs");
        if (songs == null) return null;
        JSONObject melhor = null;
        String melhorArt = "";
        double melhorNota = LIMITE;
        for (int i = 0; i < songs.length(); i++) {
            JSONObject s = songs.optJSONObject(i);
            if (s == null) continue;
            JSONArray as = s.optJSONArray("artists");
            String art = as != null && as.length() > 0 ? as.optJSONObject(0).optString("name") : "";
            double n = nota(art, s.optString("name"), s.optLong("duration") / 1000.0, artista, musica, duracaoSeg);
            if (n < melhorNota) { melhorNota = n; melhor = s; melhorArt = art; }
        }
        if (melhor == null) return null;
        JSONObject l = new JSONObject(baixar(netease + "/api/song/lyric?lv=1&kv=1&tv=-1&id=" + melhor.optLong("id"), ref, null));
        JSONArray linhas = linhasBoas(l.optJSONObject("lrc") == null ? "" : l.optJSONObject("lrc").optString("lyric"));
        return linhas == null ? null : achado("NetEase", linhas, melhorArt, melhor.optString("name"), melhor.optLong("duration") / 1000.0, duracaoSeg);
    }

    // ---- 4) Kugou (catálogo de letras do player Kugou: procura pela duração e pelo nome)
    static String kugou = "https://lyrics.kugou.com";

    private static JSONObject doKugou(String artista, String musica, double duracaoSeg) throws Exception {
        String chave = (artista.length() > 0 ? artista + " - " : "") + musica;
        JSONObject j = new JSONObject(baixar(kugou + "/search?ver=1&man=yes&client=pc&hash=&keyword=" + enc(chave) + "&duration=" + Math.round(duracaoSeg * 1000), null, null));
        JSONArray c = j.optJSONArray("candidates");
        if (c == null) return null;
        JSONObject melhor = null;
        double melhorNota = LIMITE;
        for (int i = 0; i < c.length(); i++) {
            JSONObject o = c.optJSONObject(i);
            if (o == null) continue;
            double n = nota(o.optString("singer"), o.optString("song"), o.optLong("duration") / 1000.0, artista, musica, duracaoSeg);
            if (n < melhorNota) { melhorNota = n; melhor = o; }
        }
        if (melhor == null) return null;
        JSONObject d = new JSONObject(baixar(kugou + "/download?ver=1&client=pc&fmt=lrc&charset=utf8&id=" + enc(melhor.optString("id")) + "&accesskey=" + enc(melhor.optString("accesskey")), null, null));
        String texto = new String(android.util.Base64.decode(d.optString("content"), android.util.Base64.DEFAULT), "UTF-8");
        JSONArray linhas = linhasBoas(texto);
        return linhas == null ? null : achado("Kugou", linhas, melhor.optString("singer"), melhor.optString("song"), melhor.optLong("duration") / 1000.0, duracaoSeg);
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

    private static String baixar(String endereco, String referer, String biscoito) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(endereco).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", referer == null ? "Tocador/4.2 (app Android de código aberto)" : "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36");
        if (referer != null) c.setRequestProperty("Referer", referer);
        if (biscoito != null) c.setRequestProperty("Cookie", biscoito);
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
