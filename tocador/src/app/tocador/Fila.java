package app.tocador;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Ordem de reprodução de uma lista: em sequência ou aleatória (sem repetir faixa até passar por todas),
 * com repetição desligada, da lista inteira ou só da faixa. O "anterior" volta para a faixa que tocou antes.
 * Java puro, testado fora do celular.
 */
public final class Fila {

    public static final String NAO = "nao", LISTA = "lista", FAIXA = "faixa";

    private final Random sorte;
    private final List<Integer> ordem = new ArrayList<Integer>();
    private int pos = -1;
    private boolean aleatorio;
    private String repetir = NAO;

    public Fila() { this(new Random()); }
    public Fila(Random r) { sorte = r; }

    public boolean aleatorio() { return aleatorio; }
    public String repetir() { return repetir; }
    public int posicao() { return pos; }
    public int tamanho() { return ordem.size(); }

    public void definirRepetir(String r) { repetir = FAIXA.equals(r) || LISTA.equals(r) ? r : NAO; }

    /**
     * Começa a tocar uma lista de n itens.
     * @param primeiro item escolhido pela pessoa (-1 = começar do início, ou de um item sorteado no aleatório)
     */
    public int iniciar(int n, int primeiro, boolean emAleatorio) {
        aleatorio = emAleatorio;
        pos = 0;
        montar(n, primeiro);
        return atual();
    }

    private void montar(int n, int primeiro) {
        ordem.clear();
        for (int i = 0; i < n; i++) ordem.add(i);
        if (aleatorio) {
            Collections.shuffle(ordem, sorte);
            if (primeiro >= 0 && primeiro < n) { ordem.remove(Integer.valueOf(primeiro)); ordem.add(0, primeiro); }
        } else if (primeiro > 0 && primeiro < n) {
            // em sequência a partir do item escolhido (os anteriores ficam para o "anterior")
            pos = primeiro;
        }
    }

    /** Item que está tocando agora (-1 se nenhum). */
    public int atual() { return pos >= 0 && pos < ordem.size() ? ordem.get(pos) : -1; }

    /** Liga/desliga o aleatório no meio da lista, sem interromper a faixa atual. */
    public void definirAleatorio(boolean sim) {
        if (sim == aleatorio) return;
        int agora = atual(), n = ordem.size();
        aleatorio = sim;
        if (n == 0) return;
        if (sim) {
            // as que já tocaram ficam para trás; o resto é sorteado
            List<Integer> antes = new ArrayList<Integer>(ordem.subList(0, pos + 1));
            List<Integer> resto = new ArrayList<Integer>(ordem.subList(pos + 1, n));
            Collections.shuffle(resto, sorte);
            ordem.clear(); ordem.addAll(antes); ordem.addAll(resto);
        } else {
            ordem.clear();
            for (int i = 0; i < n; i++) ordem.add(i);
            pos = Math.max(0, agora);
        }
    }

    /**
     * Próxima (dir = +1) ou anterior (dir = -1).
     * @param sozinho a faixa acabou sozinha (a repetição da faixa só vale nesse caso)
     * @return o item a tocar, ou -1 quando a lista acabou
     */
    public int avancar(int dir, boolean sozinho) {
        int n = ordem.size();
        if (n == 0) return -1;
        if (sozinho && FAIXA.equals(repetir)) return atual();
        int p = pos + dir;
        if (p < 0) p = LISTA.equals(repetir) ? n - 1 : 0;
        if (p >= n) {
            if (!LISTA.equals(repetir)) return -1;
            int ultima = atual();
            if (aleatorio) { // nova volta sorteada, sem começar pela mesma faixa que acabou de tocar
                Collections.shuffle(ordem, sorte);
                if (n > 1 && ordem.get(0) == ultima) Collections.swap(ordem, 0, n - 1);
            }
            p = 0;
        }
        pos = p;
        return ordem.get(pos);
    }
}
