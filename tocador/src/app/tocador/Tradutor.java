package app.tocador;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Tradução de frases para português pelo serviço público do Google Tradutor (o mesmo usado pelas
 * extensões de navegador). Várias frases vão num pedido só, separadas por quebra de linha.
 * Java puro, testável fora do celular.
 */
public final class Tradutor {

    static String endereco = "https://translate.googleapis.com/translate_a/single";
    static final int LIMITE = 1500; // caracteres por pedido (o endereço não pode ficar grande demais)

    private Tradutor() { }

    /** Traduz cada texto de "de" (ou "auto") para "para". Devolve uma lista do mesmo tamanho. */
    public static List<String> traduzir(List<String> textos, String de, String para) throws Exception {
        ArrayList<String> out = new ArrayList<String>();
        int i = 0;
        while (i < textos.size()) {
            ArrayList<String> lote = new ArrayList<String>();
            int tam = 0;
            while (i < textos.size() && (lote.isEmpty() || tam + textos.get(i).length() < LIMITE)) {
                String t = textos.get(i++).replace('\n', ' ').trim();
                lote.add(t);
                tam += t.length() + 1;
            }
            out.addAll(lote(lote, de, para));
        }
        return out;
    }

    private static List<String> lote(List<String> lote, String de, String para) throws Exception {
        StringBuilder junto = new StringBuilder();
        for (int k = 0; k < lote.size(); k++) { if (k > 0) junto.append('\n'); junto.append(lote.get(k)); }
        String r = pedir(junto.toString(), de, para);
        String[] partes = r.split("\n", -1);
        ArrayList<String> out = new ArrayList<String>();
        if (partes.length == lote.size()) { for (String p : partes) out.add(p.trim()); return out; }
        // O tradutor juntou ou separou linhas: traduz uma a uma.
        for (String t : lote) out.add(t.length() == 0 ? "" : pedir(t, de, para).replace('\n', ' ').trim());
        return out;
    }

    static String pedir(String texto, String de, String para) throws Exception {
        String url = endereco + "?client=gtx&dt=t&sl=" + (de == null || de.length() == 0 ? "auto" : de) + "&tl=" + para
                + "&q=" + URLEncoder.encode(texto, "UTF-8");
        Exception ultimo = null;
        for (int tentativa = 0; tentativa < 3; tentativa++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36");
                int cod = c.getResponseCode();
                if (cod == 429 || cod >= 500) { ultimo = new Exception("tradutor: HTTP " + cod); Thread.sleep(800L * (tentativa + 1)); continue; }
                if (cod != 200) throw new Exception("tradutor: HTTP " + cod);
                JSONArray a = new JSONArray(ler(c.getInputStream()));
                JSONArray frases = a.optJSONArray(0);
                StringBuilder s = new StringBuilder();
                if (frases != null) for (int i = 0; i < frases.length(); i++) {
                    JSONArray f = frases.optJSONArray(i);
                    if (f != null && !f.isNull(0)) s.append(f.optString(0));
                }
                return s.toString();
            } finally { c.disconnect(); }
        }
        throw ultimo;
    }

    private static String ler(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return new String(b.toByteArray(), "UTF-8");
    }
}
