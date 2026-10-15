package app.tocador;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vídeos do jw.org: consulta a API pública que o próprio site usa ("mediator") para obter
 * os arquivos em cada resolução (240p a 720p) e a legenda (WebVTT).
 * Java puro (sem Android) para poder ser testado fora do celular.
 */
public final class JwOrg {

    private JwOrg() { }

    /** Um vídeo do jw.org. */
    public static final class Midia {
        public String titulo = "";
        /** {rótulo ("720p"), endereço do .mp4}, da maior para a menor resolução. */
        public final ArrayList<String[]> arquivos = new ArrayList<String[]>();
        public String legenda; // endereço do .vtt, ou null
        public String chave, idioma;
    }

    static String API = "https://b.jw-cdn.org/apis/mediator/v1/media-items/";

    /** Código de idioma do jw.org a partir do idioma do endereço (pt, en, es…). */
    private static final HashMap<String, String> IDIOMAS = new HashMap<String, String>();
    static {
        String[] p = { "pt", "T", "pt-br", "T", "pt-pt", "TPO", "en", "E", "es", "S", "fr", "F", "de", "X", "it", "I", "ja", "J",
                "ko", "KO", "ru", "U", "nl", "O", "pl", "P", "zh-hans", "CHS", "zh-hant", "CH", "ro", "M", "uk", "K", "tr", "TK" };
        for (int i = 0; i < p.length; i += 2) IDIOMAS.put(p[i], p[i + 1]);
    }

    private static final Pattern HOST = Pattern.compile("(?i)^https?://([a-z0-9-]+\\.)*jw\\.org([/?#].*)?$");
    private static final Pattern LANK = Pattern.compile("[?&#]lank=([^&#]+)");
    private static final Pattern WTLOCALE = Pattern.compile("[?&#]wtlocale=([^&#]+)");
    private static final Pattern FRAGMENTO = Pattern.compile("#([a-z]{2,3}(?:-[a-z]+)?)/mediaitems/(?:[^/?#]+/)*([^/?#]+)/?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCALE_CAMINHO = Pattern.compile("(?i)jw\\.org/([a-z]{2,3}(?:-[a-z]+)?)/");

    public static boolean ehJwOrg(String url) { return url != null && HOST.matcher(url).matches(); }

    /** {código de idioma, chave do vídeo} a partir do endereço da página, ou null se não for um vídeo. */
    public static String[] identificar(String url) {
        if (!ehJwOrg(url)) return null;
        String chave = null, idioma = null;
        Matcher m = LANK.matcher(url);
        if (m.find()) chave = m.group(1);
        m = WTLOCALE.matcher(url);
        if (m.find()) idioma = m.group(1);
        m = FRAGMENTO.matcher(url);
        if (chave == null && m.find()) {
            chave = m.group(2);
            if (idioma == null) idioma = IDIOMAS.get(m.group(1).toLowerCase());
        }
        if (chave == null || !chave.matches("[A-Za-z0-9_.-]+")) return null;
        if (idioma == null) {
            m = LOCALE_CAMINHO.matcher(url);
            if (m.find()) idioma = IDIOMAS.get(m.group(1).toLowerCase());
        }
        if (idioma == null) idioma = "T";
        return new String[] { idioma, chave };
    }

    /** Consulta a API. */
    public static Midia buscar(String paginaUrl, String ua) throws IOException {
        String[] id = identificar(paginaUrl);
        if (id == null) return null;
        String corpo = Legendas.baixar(API + id[0] + "/" + id[1] + "?clientType=www", ua, null, "https://www.jw.org/");
        Midia m = ler(corpo);
        if (m != null) { m.chave = id[1]; m.idioma = id[0]; }
        return m;
    }

    /** Lê a resposta da API (separado para poder ser testado com respostas salvas). */
    static Midia ler(String json) {
        try {
            JSONArray media = new JSONObject(json).optJSONArray("media");
            if (media == null || media.length() == 0) return null;
            JSONObject v = media.optJSONObject(0);
            Midia m = new Midia();
            m.titulo = v.optString("title", "");
            JSONArray fs = v.optJSONArray("files");
            ArrayList<Object[]> lista = new ArrayList<Object[]>();
            if (fs != null) for (int i = 0; i < fs.length(); i++) {
                JSONObject f = fs.optJSONObject(i);
                if (f == null) continue;
                String u = f.optString("progressiveDownloadURL", "");
                if (u.length() == 0) continue;
                int altura = f.optInt("frameHeight", 0);
                String rotulo = f.optString("label", "");
                if (altura == 0) { Matcher d = Pattern.compile("(\\d{3,4})p").matcher(rotulo); if (d.find()) altura = Integer.parseInt(d.group(1)); }
                if (rotulo.length() == 0) rotulo = altura > 0 ? altura + "p" : "vídeo";
                lista.add(new Object[] { altura, rotulo, u, f.optBoolean("subtitled", false) ? 1 : 0 });
                JSONObject leg = f.optJSONObject("subtitles");
                if (m.legenda == null && leg != null && leg.optString("url", "").length() > 0) m.legenda = leg.optString("url");
            }
            // Da maior para a menor resolução, sem repetir rótulos (há versões com e sem legenda gravada na imagem).
            java.util.Collections.sort(lista, new java.util.Comparator<Object[]>() {
                public int compare(Object[] a, Object[] b) {
                    int d = (Integer) b[0] - (Integer) a[0];
                    return d != 0 ? d : (Integer) a[3] - (Integer) b[3]; // sem legenda gravada na imagem primeiro
                }
            });
            for (Object[] o : lista) {
                boolean repetido = false;
                for (String[] x : m.arquivos) if (x[0].equals(o[1])) repetido = true;
                if (!repetido) m.arquivos.add(new String[] { (String) o[1], (String) o[2] });
            }
            return m.arquivos.isEmpty() && m.legenda == null ? null : m;
        } catch (Exception e) {
            return null;
        }
    }
}
