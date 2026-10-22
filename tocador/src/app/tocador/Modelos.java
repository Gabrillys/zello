package app.tocador;

import android.app.ActivityManager;
import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Modelos do Whisper (formato ONNX do projeto transformers.js, licença MIT/Apache),
 * baixados uma vez e guardados no armazenamento interno do app.
 * Três níveis, com os nomes do TurboScribe: Guepardo (base), Golfinho (small) e Baleia (large-v3-turbo).
 */
final class Modelos {

    static final String[][] OPCOES = {
        { "base", "Guepardo: rápido, erra mais (Whisper base, cerca de 80 MB)" },
        { "small", "Golfinho: equilibrado e preciso (Whisper small, cerca de 250 MB)" },
        { "turbo", "Baleia: o melhor, bem mais lento (Whisper large-v3-turbo, cerca de 1 GB)" },
    };
    /** Nomes dos arquivos no aparelho. */
    static final String[] ARQUIVOS = { "encoder_model_quantized.onnx", "decoder_model_merged_quantized.onnx" };
    /** Nomes aceitos no servidor, na ordem (os repositórios variam entre "_quantized" e "_int8"). */
    static final String[][] CANDIDATOS = {
        { "encoder_model_quantized.onnx", "encoder_model_int8.onnx" },
        { "decoder_model_merged_quantized.onnx", "decoder_model_merged_int8.onnx" },
    };
    static final String[] ORIGENS = { "https://huggingface.co/%s/resolve/main/onnx/", "https://hf-mirror.com/%s/resolve/main/onnx/" };

    /** A Baleia é o large-v3-turbo: 128 faixas de mel e um idioma a mais no vocabulário. Os demais são v1/v2 (80 faixas). */
    static boolean ehTurbo(String modelo) { return "turbo".equals(modelo); }

    static String repositorio(String modelo) { return ehTurbo(modelo) ? "onnx-community/whisper-large-v3-turbo" : "Xenova/whisper-" + modelo; }

    static int vocab(String modelo) { return ehTurbo(modelo) ? 51866 : 51865; }

    static String filtroMel(String modelo) { return ehTurbo(modelo) ? "whisper/mel128.bin" : "whisper/mel80.bin"; }

    /** Memória total do aparelho (MB) a partir da qual o modelo roda com folga (a Baleia carrega mais de 1 GB só de pesos). */
    static int memoriaMinimaMB(String modelo) { return ehTurbo(modelo) ? 5000 : 0; }

    static boolean cabe(Context c, String modelo) {
        int min = memoriaMinimaMB(modelo);
        if (min == 0) return true;
        try {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
            return (mi.totalMem >> 20) >= min;
        } catch (Exception e) { return true; }
    }

    private static volatile String baixando;   // nome do modelo em download
    private static volatile String andamento = "";
    private static volatile boolean cancelado;

    /** O que o serviço em primeiro plano mostra na notificação enquanto o modelo baixa. */
    private static final ServicoTrabalho.Tarefa TAREFA = new ServicoTrabalho.Tarefa() {
        public String nome() { return "modelo do Whisper"; }
        public String titulo() { return "Baixando o Whisper " + apelido(nomeBaixando()); }
        public String texto() { return andamento; }
        public int andamento() { return -1; }
        public void cancelar() { cancelado = true; }
    };

    private Modelos() { }

    /** Só o apelido: Guepardo, Golfinho ou Baleia. */
    static String apelido(String chave) {
        for (String[] o : OPCOES) if (o[0].equals(chave)) return o[1].substring(0, o[1].indexOf(':'));
        return chave;
    }

    static String nomeDe(String chave) {
        for (String[] o : OPCOES) if (o[0].equals(chave)) return o[1];
        return chave;
    }

    static File pasta(Context c, String modelo) { return new File(c.getFilesDir(), "whisper/" + modelo); }

    static File encoder(Context c, String modelo) { return new File(pasta(c, modelo), ARQUIVOS[0]); }

    static File decoder(Context c, String modelo) { return new File(pasta(c, modelo), ARQUIVOS[1]); }

    /** Outro modelo já baixado (Golfinho ou Guepardo), ou null. Para o que roda sozinho, sem pedir download nem usar a Baleia. */
    static String prontoAlternativo(Context c, String evitar) {
        for (String m : new String[] { "small", "base" }) if (!m.equals(evitar) && pronto(c, m)) return m;
        return null;
    }

    static boolean pronto(Context c, String modelo) { return encoder(c, modelo).length() > 1000000 && decoder(c, modelo).length() > 1000000; }

    static boolean baixando() { return baixando != null; }

    private static String nomeBaixando() { String b = baixando; return b == null ? "" : b; }

    static String andamento() { return andamento; }

    interface Fim { void pronto(boolean ok, String erro); }

    /** Baixa o modelo em segundo plano (continua de onde parou se a internet cair). */
    static synchronized void baixar(final Context c, final String modelo, final Fim fim) {
        if (baixando != null) return;
        if (!cabe(c, modelo)) {
            andamento = "Este celular tem pouca memória para a Baleia (precisa de uns 6 GB). Use o Golfinho.";
            if (fim != null) fim.pronto(false, andamento);
            return;
        }
        baixando = modelo;
        cancelado = false;
        andamento = "Baixando o Whisper " + apelido(modelo) + "…";
        ServicoTrabalho.iniciar(c, TAREFA); // mantém o download vivo com a tela apagada
        new Thread(new Runnable() { public void run() {
            String erro = null;
            try {
                File p = pasta(c, modelo);
                p.mkdirs();
                for (int i = 0; i < ARQUIVOS.length; i++) {
                    File destino = new File(p, ARQUIVOS[i]);
                    if (destino.length() > 1000000) continue;
                    Exception ultimo = null;
                    boolean ok = false;
                    for (int tentativa = 0; tentativa < 6 && !ok; tentativa++) {
                        String base = String.format(ORIGENS[tentativa % ORIGENS.length], repositorio(modelo));
                        for (String nome : CANDIDATOS[i]) {
                            try { baixarArquivo(base + nome, destino, "parte " + (i + 1) + " de " + ARQUIVOS.length); ok = true; break; }
                            catch (Exception e) {
                                ultimo = e;
                                if (cancelado || !String.valueOf(e.getMessage()).startsWith("HTTP 404")) break; // só o 404 tenta o outro nome do arquivo
                            }
                        }
                        if (!ok) {
                            if (cancelado) break;
                            try { Thread.sleep(1500L * (tentativa + 1)); } catch (InterruptedException x) { }
                        }
                    }
                    if (!ok) throw ultimo;
                }
                andamento = "";
            } catch (Exception e) {
                erro = e.getMessage();
                andamento = cancelado ? "Download do Whisper cancelado. O que já veio fica guardado para continuar depois." : "Falha ao baixar o Whisper: " + erro;
                Diagnostico.anotar("Whisper: download falhou: " + erro);
            }
            baixando = null;
            try { if (fim != null) fim.pronto(erro == null, erro); }
            finally { ServicoTrabalho.encerrar(c, TAREFA, erro == null ? "Whisper baixado e pronto." : andamento); }
        } }, "tocador-modelo").start();
    }

    private static void baixarArquivo(String url, File destino, String rotulo) throws Exception {
        File parte = new File(destino.getPath() + ".part");
        long ja = parte.exists() ? parte.length() : 0;
        String atual = url;
        HttpURLConnection c = null;
        for (int r = 0; r < 6; r++) { // segue redirecionamentos (o arquivo fica num CDN)
            c = (HttpURLConnection) new URL(atual).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "Tocador");
            if (ja > 0) c.setRequestProperty("Range", "bytes=" + ja + "-");
            int cod = c.getResponseCode();
            if (cod >= 300 && cod < 400 && c.getHeaderField("Location") != null) {
                atual = new URL(new URL(atual), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            break;
        }
        int cod = c.getResponseCode();
        if (cod != 200 && cod != 206) throw new Exception("HTTP " + cod);
        if (cod == 200) ja = 0;
        long total = c.getContentLength() > 0 ? c.getContentLength() + ja : -1;
        InputStream in = c.getInputStream();
        FileOutputStream out = new FileOutputStream(parte, ja > 0);
        try {
            byte[] b = new byte[64 * 1024];
            long feito = ja;
            int n;
            long ultimo = 0;
            while ((n = in.read(b)) > 0) {
                if (cancelado) throw new Exception("cancelado");
                out.write(b, 0, n);
                feito += n;
                if (feito - ultimo > 512 * 1024) {
                    ultimo = feito;
                    andamento = "Baixando o Whisper " + apelido(nomeBaixando()) + " (" + rotulo + "): " + (feito >> 20) + (total > 0 ? " de " + (total >> 20) : "") + " MB";
                }
            }
        } finally { out.close(); in.close(); c.disconnect(); }
        if (total > 0 && parte.length() < total) throw new Exception("download incompleto");
        if (!parte.renameTo(destino)) throw new Exception("não consegui gravar o modelo");
    }

    static void apagar(Context c, String modelo) {
        File[] fs = pasta(c, modelo).listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }
}
