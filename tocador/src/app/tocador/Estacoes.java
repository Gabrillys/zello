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
                .put("site", e.optString("homepage"));
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
