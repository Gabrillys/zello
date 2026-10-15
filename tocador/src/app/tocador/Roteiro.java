package app.tocador;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Roteiro da dublagem: as falas (texto + horário) tiradas da legenda já traduzida para português,
 * e a decisão de qual fala dizer em cada momento do vídeo e em que velocidade.
 * Java puro (sem Android) para poder ser testado fora do celular.
 */
public final class Roteiro {

    /** Uma fala: começa em ini e deveria terminar até fim (milissegundos do vídeo). */
    public static final class Fala {
        public final long ini, fim;
        public final String texto;
        Fala(long ini, long fim, String texto) { this.ini = ini; this.fim = fim; this.texto = texto; }
        @Override public String toString() { return ini + "-" + fim + ":" + texto; }
    }

    /** Duração média de um caractere falado em velocidade normal (português). */
    static final double MS_POR_CARACTERE = 62;

    private final List<Fala> falas;
    private int proxima = 0;
    private long ultimoTempo = -1;

    public Roteiro(List<Fala> falas) { this.falas = falas; }

    public int tamanho() { return falas.size(); }

    /**
     * Lê a legenda no formato json3 do YouTube e monta as falas.
     * Pedaços curtos e colados (comuns na legenda automática) são juntados numa frase só,
     * para a voz não sair picotada.
     */
    public static List<Fala> deJson3(String json) {
        ArrayList<Fala> brutas = new ArrayList<Fala>();
        try {
            JSONArray ev = new JSONObject(json).optJSONArray("events");
            if (ev != null) for (int i = 0; i < ev.length(); i++) {
                JSONObject e = ev.optJSONObject(i);
                if (e == null || e.optJSONArray("segs") == null) continue;
                StringBuilder t = new StringBuilder();
                JSONArray segs = e.optJSONArray("segs");
                for (int k = 0; k < segs.length(); k++) t.append(segs.optJSONObject(k) == null ? "" : segs.optJSONObject(k).optString("utf8", ""));
                String s = t.toString().replace('\n', ' ').replaceAll("\\s+", " ").trim();
                if (s.length() == 0 || s.matches("\\[[^\\]]*\\]")) continue; // vazio ou "[Música]", "[Aplausos]"
                long ini = e.optLong("tStartMs", 0), dur = e.optLong("dDurationMs", 0);
                brutas.add(new Fala(ini, ini + Math.max(dur, 400), s));
            }
        } catch (Exception e) { }
        return juntar(brutas);
    }

    static List<Fala> juntar(List<Fala> brutas) {
        ArrayList<Fala> out = new ArrayList<Fala>();
        Fala atual = null;
        for (Fala f : brutas) {
            if (atual != null && f.texto.equals(atual.texto)) continue;
            if (atual != null && f.texto.startsWith(atual.texto)) { // legenda "rolando": a nova contém a anterior
                atual = new Fala(atual.ini, Math.max(atual.fim, f.fim), f.texto);
                continue;
            }
            boolean colada = atual != null && f.ini - atual.fim < 300 && !atual.texto.matches(".*[.!?…]$") && atual.texto.length() + f.texto.length() < 110;
            if (colada) atual = new Fala(atual.ini, Math.max(atual.fim, f.fim), atual.texto + " " + f.texto);
            else { if (atual != null) out.add(atual); atual = f; }
        }
        if (atual != null) out.add(atual);
        // A fala não deve invadir a seguinte: o tempo disponível vai até o começo da próxima.
        for (int i = 0; i + 1 < out.size(); i++) {
            Fala a = out.get(i), b = out.get(i + 1);
            if (a.fim > b.ini) out.set(i, new Fala(a.ini, Math.max(a.ini + 300, b.ini), a.texto));
        }
        return out;
    }

    /**
     * Chamado várias vezes por segundo com o tempo atual do vídeo.
     * Devolve a fala que deve começar agora, ou null. Se a pessoa avançou ou voltou o vídeo,
     * o roteiro se reposiciona sozinho (e não fala o que ficou para trás).
     */
    public synchronized Fala falaPara(long tempo) {
        if (ultimoTempo >= 0 && (tempo < ultimoTempo - 1500 || tempo > ultimoTempo + 3000)) reposicionar(tempo);
        ultimoTempo = tempo;
        if (proxima >= falas.size()) return null;
        Fala f = falas.get(proxima);
        if (tempo < f.ini) return null;
        proxima++;
        // Chegou tarde demais (mais de meia fala já passou): pula, para não ficar atrasado.
        if (tempo > f.ini + Math.max(1200, (f.fim - f.ini) / 2)) return falaPara(tempo);
        return f;
    }

    public synchronized void reposicionar(long tempo) {
        int i = 0;
        while (i < falas.size() && falas.get(i).ini < tempo - 300) i++;
        proxima = i;
        ultimoTempo = tempo;
    }

    /** Velocidade da voz para a fala caber no tempo dela (1,0 = normal; no máximo 1,8). */
    public static float velocidade(Fala f) {
        double precisa = f.texto.length() * MS_POR_CARACTERE;
        double tem = Math.max(400, f.fim - f.ini);
        return (float) Math.max(1.0, Math.min(1.8, precisa / tem));
    }
}
