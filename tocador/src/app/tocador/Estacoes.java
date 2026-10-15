package app.tocador;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

/**
 * Procura a rádio no Radio Browser (catálogo aberto e gratuito de rádios do mundo, radio-browser.info)
 * pelo endereço do stream ou pelo nome, para mostrar logotipo, cidade, país e estilo no rodapé da animação.
 */
final class Estacoes {

    private Estacoes() { }

    static final String[] SERVIDORES = { "https://all.api.radio-browser.info", "https://de1.api.radio-browser.info", "https://nl1.api.radio-browser.info" };
    private static final Map<String, JSONObject> cache = new HashMap<String, JSONObject>();

    /** Devolve os dados encontrados (ou um objeto vazio). Faz rede: chamar fora da linha da tela. */
    static JSONObject buscar(String url, String nome) {
        String chave = url + "|" + nome;
        synchronized (cache) { if (cache.containsKey(chave)) return cache.get(chave); }
        JSONObject r = new JSONObject();
        try {
            JSONObject e = primeiro("/json/stations/byurl?url=" + URLEncoder.encode(url, "UTF-8"));
            if (e == null && url.endsWith("/")) e = primeiro("/json/stations/byurl?url=" + URLEncoder.encode(url.substring(0, url.length() - 1), "UTF-8"));
            if (e == null && nome != null && nome.trim().length() > 2)
                e = primeiro("/json/stations/byname/" + URLEncoder.encode(nome.trim(), "UTF-8").replace("+", "%20") + "?limit=1&order=clickcount&reverse=true&hidebroken=true");
            if (e != null) r = resumo(e);
        } catch (Exception ignorado) { }
        synchronized (cache) { cache.put(chave, r); }
        return r;
    }

    static JSONObject resumo(JSONObject e) throws Exception {
        String local = juntar(e.optString("state"), e.optString("country"));
        String tags = e.optString("tags").replace(",", ", ");
        if (tags.length() > 60) tags = tags.substring(0, tags.lastIndexOf(", ", 60) > 0 ? tags.lastIndexOf(", ", 60) : 60);
        return new JSONObject().put("nome", e.optString("name").trim()).put("logo", e.optString("favicon"))
                .put("local", local).put("tags", tags).put("bitrate", e.optInt("bitrate")).put("codec", e.optString("codec"))
                .put("site", e.optString("homepage")).put("pais", e.optString("countrycode"))
                .put("idioma", e.optString("language")).put("votos", e.optInt("votes"))
                .put("estado", e.optString("state").trim()).put("paisNome", e.optString("country").trim());
    }

    /**
     * Cabeçalhos "icy-" que a própria rádio manda (nome, gênero, descrição, site, kbps, kHz).
     * Feito "na mão", com socket, porque servidores Shoutcast antigos respondem "ICY 200 OK", que o Java não aceita.
     */
    static JSONObject icy(String endereco) {
        JSONObject r = new JSONObject();
        java.net.Socket s = null;
        try {
            URL u = new URL(endereco);
            boolean https = "https".equalsIgnoreCase(u.getProtocol());
            int porta = u.getPort() > 0 ? u.getPort() : (https ? 443 : 80);
            s = https ? javax.net.ssl.SSLSocketFactory.getDefault().createSocket(u.getHost(), porta) : new java.net.Socket();
            if (!https) s.connect(new java.net.InetSocketAddress(u.getHost(), porta), 6000);
            s.setSoTimeout(6000);
            String caminho = (u.getPath() == null || u.getPath().length() == 0 ? "/" : u.getPath()) + (u.getQuery() != null ? "?" + u.getQuery() : "");
            String pedido = "GET " + caminho + " HTTP/1.0\r\nHost: " + u.getHost() + "\r\nUser-Agent: VLC/3.0.20 LibVLC/3.0.20\r\nIcy-MetaData: 1\r\nConnection: close\r\n\r\n";
            s.getOutputStream().write(pedido.getBytes("ISO-8859-1"));
            java.io.InputStream in = s.getInputStream();
            StringBuilder linha = new StringBuilder();
            int c, linhas = 0, lidos = 0;
            while ((c = in.read()) >= 0 && lidos++ < 16384) {
                if (c == '\n') {
                    String l = linha.toString().trim();
                    linha.setLength(0);
                    if (l.length() == 0 && linhas > 0) break;
                    linhas++;
                    int dp = l.indexOf(':');
                    if (dp <= 0) continue;
                    String k = l.substring(0, dp).trim().toLowerCase(java.util.Locale.ROOT), v = l.substring(dp + 1).trim();
                    v = new String(v.getBytes("ISO-8859-1"), "UTF-8"); // a maioria manda UTF-8
                    if (k.equals("icy-name")) r.put("nome", v);
                    else if (k.equals("icy-genre")) r.put("genero", v);
                    else if (k.equals("icy-description")) r.put("descricao", v);
                    else if (k.equals("icy-url")) r.put("site", v);
                    else if (k.equals("icy-br")) r.put("kbps", v.replaceAll("\\D.*$", ""));
                    else if (k.equals("icy-sr")) r.put("hz", v);
                } else if (c != '\r') linha.append((char) c);
            }
        } catch (Exception e) { /* sem cabeçalhos: tudo bem */ }
        finally { if (s != null) try { s.close(); } catch (Exception e) { } }
        return r;
    }

    private static String juntar(String a, String b) {
        a = a == null ? "" : a.trim(); b = b == null ? "" : b.trim();
        if (a.length() == 0) return b;
        if (b.length() == 0) return a;
        return a + ", " + b;
    }

    private static JSONObject primeiro(String caminho) {
        for (String s : SERVIDORES) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(s + caminho).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", "Tocador/3.2");
                if (c.getResponseCode() != 200) { c.disconnect(); continue; }
                JSONArray a = new JSONArray(Listas.ler(c.getInputStream(), 2 << 20));
                return a.length() > 0 ? a.getJSONObject(0) : null;
            } catch (Exception e) { /* tenta o próximo servidor */ }
        }
        return null;
    }
}
