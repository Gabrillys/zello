package app.tocador;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Descobre o endereço real do vídeo/áudio de um link do YouTube ou do Instagram,
 * para tocar no player VLC (com equalizador) e para baixar.
 * Java puro (sem Android) para poder ser testado fora do celular.
 *
 * Isso depende de como cada rede responde hoje e pode parar de funcionar quando elas mudarem.
 */
public final class Extrator {

    /** O que foi encontrado. Campos nulos = não disponível. */
    public static final class Resultado {
        public String fonte;          // youtube, instagram
        public String titulo = "";
        public String video;          // vídeo com som (arquivo direto)
        public String audio;          // só áudio (arquivo direto)
        public String audioSeparado;  // som que vem num arquivo à parte e deve ser juntado ao vídeo
        public boolean semSom;        // o próprio post não tem som
        public String hls;            // lista .m3u8 (vídeo com som)
        public String agente;         // User-Agent que deve ser usado para baixar/tocar
        public String referer;
        public boolean aoVivo;
        public String erro;
        public boolean precisaLogin;
        public boolean ok() { return erro == null && (video != null || audio != null || hls != null); }
    }

    private Extrator() { }

    // ------------------------------------------------------------ reconhecer links

    private static final Pattern YT = Pattern.compile(
            "(?i)^https?://(?:www\\.|m\\.|music\\.)?(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|live/|embed/|v/)|youtu\\.be/)([A-Za-z0-9_-]{11}).*$");
    private static final Pattern IG = Pattern.compile(
            "(?i)^https?://(?:www\\.|m\\.)?instagram\\.com/(?:(?!share/)[A-Za-z0-9_.]+/)?(?:p|reels?|tv)/([A-Za-z0-9_-]+).*$");
    private static final Pattern IG_HOST = Pattern.compile("(?i)^https?://(?:www\\.|m\\.)?(?:instagram\\.com|instagr\\.am)/.*$");

    public static String idYoutube(String url) { Matcher m = YT.matcher(url); return m.matches() ? m.group(1) : null; }
    public static String codigoInstagram(String url) { Matcher m = IG.matcher(url); return m.matches() ? m.group(1) : null; }
    public static boolean ehInstagram(String url) { return IG_HOST.matcher(url).matches(); }

    // ------------------------------------------------------------ HTTP

    static final class Resposta { int codigo; String corpo; String url; Map<String, List<String>> cabecalhos; }

    static Resposta http(String metodo, String url, String[][] cab, String corpo) throws IOException {
        URL u = new URL(url);
        for (int r = 0; r < 6; r++) {
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod(metodo);
            if (cab != null) for (String[] h : cab) if (h[1] != null && h[1].length() > 0) c.setRequestProperty(h[0], h[1]);
            if (corpo != null) {
                c.setDoOutput(true);
                OutputStream o = c.getOutputStream();
                o.write(corpo.getBytes("UTF-8"));
                o.close();
            }
            int cod = c.getResponseCode();
            String loc = c.getHeaderField("Location");
            if (cod >= 300 && cod < 400 && loc != null && corpo == null) { u = new URL(u, loc); c.disconnect(); continue; }
            Resposta res = new Resposta();
            res.codigo = cod;
            res.url = u.toString();
            res.cabecalhos = c.getHeaderFields();
            InputStream in = cod >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            if (in != null) { byte[] buf = new byte[16384]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); in.close(); }
            res.corpo = new String(b.toByteArray(), "UTF-8");
            c.disconnect();
            return res;
        }
        throw new IOException("Redirecionamentos demais.");
    }

    private static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; } }

    // ------------------------------------------------------------ YouTube

    /** Clientes que o YouTube aceita sem "PO token" (na data em que isto foi escrito). Testados em ordem. */
    private static final String[][] CLIENTES_YT = {
        // nome, versão, id numérico, User-Agent, JSON extra do cliente
        { "ANDROID_VR", "1.65.10", "28",
          "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
          "\"deviceMake\":\"Oculus\",\"deviceModel\":\"Quest 3\",\"androidSdkVersion\":32,\"osName\":\"Android\",\"osVersion\":\"12L\"" },
        { "IOS", "20.10.4", "5",
          "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
          "\"deviceMake\":\"Apple\",\"deviceModel\":\"iPhone16,2\",\"osName\":\"iPhone\",\"osVersion\":\"18.3.2.22D82\"" },
    };

    public static Resultado youtube(String url) {
        Resultado r = new Resultado();
        r.fonte = "youtube";
        String id = idYoutube(url);
        if (id == null) { r.erro = "Não reconheci o link do YouTube."; return r; }
        String ultimoErro = "O YouTube não respondeu.";
        for (String[] cl : CLIENTES_YT) {
            try {
                String corpo = "{\"context\":{\"client\":{\"clientName\":\"" + cl[0] + "\",\"clientVersion\":\"" + cl[1] + "\",\"userAgent\":\"" + cl[3]
                        + "\"," + cl[4] + ",\"hl\":\"pt\",\"gl\":\"BR\"}},\"videoId\":\"" + id + "\",\"contentCheckOk\":true,\"racyCheckOk\":true}";
                Resposta res = http("POST", "https://www.youtube.com/youtubei/v1/player?prettyPrint=false", new String[][] {
                        { "Content-Type", "application/json" }, { "User-Agent", cl[3] }, { "X-YouTube-Client-Name", cl[2] },
                        { "X-YouTube-Client-Version", cl[1] }, { "Origin", "https://www.youtube.com" } }, corpo);
                if (res.codigo != 200) { ultimoErro = "O YouTube recusou (erro " + res.codigo + ")."; continue; }
                Resultado x = lerRespostaYoutube(new JSONObject(res.corpo));
                x.agente = cl[3];
                if (x.ok() || x.aoVivo) return x;
                if (x.erro != null) ultimoErro = x.erro;
            } catch (Exception e) {
                ultimoErro = "Falha ao falar com o YouTube: " + e.getMessage();
            }
        }
        r.erro = ultimoErro;
        return r;
    }

    /** Lê a resposta do /youtubei/v1/player. Separado para poder ser testado com respostas salvas. */
    static Resultado lerRespostaYoutube(JSONObject j) {
        Resultado r = new Resultado();
        r.fonte = "youtube";
        JSONObject vd = j.optJSONObject("videoDetails");
        if (vd != null) {
            r.titulo = vd.optString("title", "");
            r.aoVivo = vd.optBoolean("isLive", false) || (vd.optBoolean("isLiveContent", false) && vd.optBoolean("isUpcoming", false));
        }
        JSONObject ps = j.optJSONObject("playabilityStatus");
        String status = ps == null ? "" : ps.optString("status", "");
        if (!status.equals("OK")) {
            String motivo = ps == null ? "" : ps.optString("reason", "");
            r.erro = motivo.length() > 0 ? "YouTube: " + motivo : "O YouTube não liberou este vídeo.";
            if (status.equals("LOGIN_REQUIRED")) r.precisaLogin = true;
            return r;
        }
        if (r.aoVivo) { r.erro = "É uma transmissão ao vivo; não dá para baixar."; return r; }
        JSONObject sd = j.optJSONObject("streamingData");
        if (sd == null) { r.erro = "O YouTube não enviou os endereços do vídeo."; return r; }
        // Vídeo com som: o arquivo "progressivo" de melhor qualidade (geralmente 360p).
        JSONArray f = sd.optJSONArray("formats");
        int melhorAltura = -1;
        if (f != null) for (int i = 0; i < f.length(); i++) {
            JSONObject o = f.optJSONObject(i);
            if (o == null || !o.has("url")) continue;
            if (!o.optString("mimeType").startsWith("video/mp4")) continue;
            int h = o.optInt("height", 0);
            if (h > melhorAltura) { melhorAltura = h; r.video = o.optString("url"); }
        }
        // Só áudio: m4a (AAC) de maior taxa; se não houver, qualquer áudio.
        JSONArray a = sd.optJSONArray("adaptiveFormats");
        int melhorM4a = -1, melhorOutro = -1; String outro = null;
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || !o.has("url") || !o.optString("mimeType").startsWith("audio/")) continue;
            if (o.has("audioTrack") && !o.optJSONObject("audioTrack").optBoolean("audioIsDefault", true)) continue; // dublagens
            int br = o.optInt("bitrate", 0);
            if (o.optString("mimeType").startsWith("audio/mp4")) { if (br > melhorM4a) { melhorM4a = br; r.audio = o.optString("url"); } }
            else if (br > melhorOutro) { melhorOutro = br; outro = o.optString("url"); }
        }
        if (r.audio == null) r.audio = outro;
        String h = sd.optString("hlsManifestUrl", "");
        if (h.length() > 0) r.hls = h;
        if (!r.ok()) r.erro = "O YouTube não liberou o download deste vídeo.";
        return r;
    }

    // ------------------------------------------------------------ Instagram

    static final String UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String IG_APP_ID = "936619743392459";
    private static final String[] DOC_IDS = { "8845758582119845", "10015901848480474" };

    /**
     * @param cookies cookies do navegador interno para instagram.com (se a pessoa entrou na conta, eles
     *                incluem a sessão e o Instagram libera mais vídeos). Pode ser nulo.
     */
    public static Resultado instagram(String url, String cookies) {
        Resultado r = new Resultado();
        r.fonte = "instagram";
        r.agente = UA_DESKTOP;
        r.referer = "https://www.instagram.com/";
        try {
            String codigo = codigoInstagram(url);
            if (codigo == null) {
                // Link curto (instagram.com/share/…): segue o redirecionamento até o post.
                Resposta res = http("GET", url, new String[][] { { "User-Agent", UA_DESKTOP }, { "Cookie", cookies } }, null);
                codigo = codigoInstagram(res.url);
                if (codigo == null) {
                    Matcher m = Pattern.compile("instagram\\.com/(?:p|reels?|tv)/([A-Za-z0-9_-]+)").matcher(res.corpo);
                    if (m.find()) codigo = m.group(1);
                }
                if (codigo == null) { r.erro = "Não achei um post ou reel neste link do Instagram."; r.precisaLogin = res.url.contains("/accounts/login"); return r; }
            }
            String csrf = cookie(cookies, "csrftoken");
            // 1) API GraphQL (a mesma que o site usa).
            for (String doc : DOC_IDS) {
                String corpo = "variables=" + enc("{\"shortcode\":\"" + codigo + "\",\"fetch_tagged_user_count\":null,\"hoisted_comment_id\":null,\"hoisted_reply_id\":null}")
                        + "&doc_id=" + doc + "&server_timestamps=true";
                Resposta res = http("POST", "https://www.instagram.com/graphql/query", new String[][] {
                        { "User-Agent", UA_DESKTOP }, { "Content-Type", "application/x-www-form-urlencoded" }, { "X-IG-App-ID", IG_APP_ID },
                        { "X-ASBD-ID", "129477" }, { "X-Requested-With", "XMLHttpRequest" }, { "X-CSRFToken", csrf }, { "Origin", "https://www.instagram.com" },
                        { "Referer", "https://www.instagram.com/p/" + codigo + "/" }, { "Cookie", cookies } }, corpo);
                if (res.codigo == 200 && res.corpo.trim().startsWith("{")) {
                    Resultado x = lerRespostaInstagram(new JSONObject(res.corpo));
                    if (x.ok()) { x.agente = r.agente; x.referer = r.referer; return x; }
                    if (x.erro != null) r.erro = x.erro;
                }
                if (res.codigo == 401 || res.codigo == 403 || res.corpo.contains("\"require_login\":true")) r.precisaLogin = true;
            }
            // 2) Página "embed" (às vezes traz o endereço do vídeo no HTML).
            Resposta emb = http("GET", "https://www.instagram.com/p/" + codigo + "/embed/captioned/", new String[][] { { "User-Agent", UA_DESKTOP }, { "Cookie", cookies } }, null);
            String v = videoNoHtml(emb.corpo);
            if (v != null) { r.video = v; r.titulo = "Instagram"; return r; }
            if (r.erro == null) r.erro = "O Instagram não liberou este vídeo sem login.";
            r.precisaLogin = true;
        } catch (Exception e) {
            r.erro = "Falha ao falar com o Instagram: " + e.getMessage();
        }
        return r;
    }

    static Resultado lerRespostaInstagram(JSONObject j) {
        Resultado r = new Resultado();
        r.fonte = "instagram";
        JSONObject d = j.optJSONObject("data");
        JSONObject m = d == null ? null : d.optJSONObject("xdt_shortcode_media");
        if (m == null && d != null) m = d.optJSONObject("shortcode_media");
        if (m == null) { r.erro = "O Instagram não mostrou este post sem login."; r.precisaLogin = true; return r; }
        JSONObject dono = m.optJSONObject("owner");
        String usuario = dono == null ? "" : dono.optString("username", "");
        String legenda = "";
        JSONObject cap = m.optJSONObject("edge_media_to_caption");
        if (cap != null && cap.optJSONArray("edges") != null && cap.optJSONArray("edges").length() > 0)
            legenda = cap.optJSONArray("edges").optJSONObject(0).optJSONObject("node").optString("text", "");
        legenda = legenda.replaceAll("\\s+", " ").trim();
        if (legenda.length() > 60) legenda = legenda.substring(0, 60).trim() + "…";
        r.titulo = (usuario.length() > 0 ? "@" + usuario : "Instagram") + (legenda.length() > 0 ? " — " + legenda : "");
        if (m.optBoolean("is_video", false) && m.optString("video_url", "").length() > 0) { preencherVideoInstagram(r, m); return r; }
        // Carrossel: pega o primeiro vídeo.
        JSONObject filhos = m.optJSONObject("edge_sidecar_to_children");
        JSONArray e = filhos == null ? null : filhos.optJSONArray("edges");
        if (e != null) for (int i = 0; i < e.length(); i++) {
            JSONObject n = e.optJSONObject(i).optJSONObject("node");
            if (n != null && n.optBoolean("is_video", false) && n.optString("video_url", "").length() > 0) { preencherVideoInstagram(r, n); return r; }
        }
        r.erro = "Este post do Instagram não tem vídeo.";
        return r;
    }

    /**
     * O Instagram costuma mandar imagem e som em arquivos separados: o "video_url" pode vir mudo,
     * e o som fica no manifesto DASH. Quando o manifesto tem os dois, usa a melhor imagem + o melhor som.
     */
    static void preencherVideoInstagram(Resultado r, JSONObject m) {
        r.video = m.optString("video_url");
        String[] d = melhoresDoDash(m.optString("video_dash_manifest", ""));
        if (d[1] != null) {
            if (d[0] != null) r.video = d[0];
            r.audioSeparado = d[1];
            r.audio = d[1];
        } else if (m.has("has_audio") && !m.optBoolean("has_audio", true)) {
            r.semSom = true;
        }
    }

    /** Lê um manifesto MPEG-DASH e devolve {melhor vídeo, melhor áudio} (endereços), ou nulos. */
    static String[] melhoresDoDash(String mpd) {
        String[] res = new String[2];
        if (mpd == null || mpd.indexOf("<MPD") < 0) return res;
        long[] melhor = { -1, -1 };
        Matcher conj = Pattern.compile("(?s)<AdaptationSet\\b([^>]*)>(.*?)</AdaptationSet>").matcher(mpd);
        while (conj.find()) {
            String attrConj = conj.group(1);
            Matcher rep = Pattern.compile("(?s)<Representation\\b([^>]*)>(.*?)</Representation>").matcher(conj.group(2));
            while (rep.find()) {
                String attr = attrConj + " " + rep.group(1);
                Matcher base = Pattern.compile("(?s)<BaseURL[^>]*>\\s*([^<]+?)\\s*</BaseURL>").matcher(rep.group(2));
                if (!base.find()) continue;
                String tipo = attr.contains("audio") ? "audio" : attr.contains("video") ? "video" : "";
                int i = tipo.equals("video") ? 0 : tipo.equals("audio") ? 1 : -1;
                if (i < 0) continue;
                Matcher bw = Pattern.compile("bandwidth=\"(\\d+)\"").matcher(rep.group(1));
                long b = bw.find() ? Long.parseLong(bw.group(1)) : 0;
                if (b > melhor[i]) { melhor[i] = b; res[i] = desescapar(base.group(1).trim()); }
            }
        }
        return res;
    }

    static String videoNoHtml(String html) {
        if (html == null) return null;
        Matcher m = Pattern.compile("\\\\?\"video_url\\\\?\"\\s*:\\s*\\\\?\"(https?:[^\"]+?)\\\\?\"").matcher(html);
        if (!m.find()) return null;
        return desescapar(m.group(1));
    }

    static String desescapar(String s) {
        s = s.replace("\\\\/", "/").replace("\\/", "/");
        Matcher u = Pattern.compile("\\\\+u([0-9a-fA-F]{4})").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (u.find()) u.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(u.group(1), 16))));
        u.appendTail(sb);
        return sb.toString().replace("&amp;", "&");
    }

    private static String cookie(String cookies, String nome) {
        if (cookies == null) return null;
        for (String p : cookies.split(";")) { String[] kv = p.trim().split("=", 2); if (kv.length == 2 && kv[0].equals(nome)) return kv[1]; }
        return null;
    }
}
