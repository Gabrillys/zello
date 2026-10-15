package app.tocador;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Modelos do Whisper (formato ONNX do projeto transformers.js, licença MIT/Apache),
 * baixados uma vez e guardados no armazenamento interno do app.
 */
final class Modelos {

    static final String[][] OPCOES = {
        { "base", "Rápido (Whisper base, cerca de 80 MB)" },
        { "small", "Mais preciso (Whisper small, cerca de 250 MB)" },
    };
    static final String[] ARQUIVOS = { "encoder_model_quantized.onnx", "decoder_model_merged_quantized.onnx" };
    static final String[] ORIGENS = { "https://huggingface.co/Xenova/whisper-%s/resolve/main/onnx/", "https://hf-mirror.com/Xenova/whisper-%s/resolve/main/onnx/" };

    private static volatile String baixando;   // nome do modelo em download
    private static volatile String andamento = "";

    private Modelos() { }

    static String nomeDe(String chave) {
        for (String[] o : OPCOES) if (o[0].equals(chave)) return o[1];
        return chave;
    }

    static File pasta(Context c, String modelo) { return new File(c.getFilesDir(), "whisper/" + modelo); }

    static File encoder(Context c, String modelo) { return new File(pasta(c, modelo), ARQUIVOS[0]); }

    static File decoder(Context c, String modelo) { return new File(pasta(c, modelo), ARQUIVOS[1]); }

    static boolean pronto(Context c, String modelo) { return encoder(c, modelo).length() > 1000000 && decoder(c, modelo).length() > 1000000; }

    static boolean baixando() { return baixando != null; }

    static String andamento() { return andamento; }

    interface Fim { void pronto(boolean ok, String erro); }

    /** Baixa o modelo em segundo plano (continua de onde parou se a internet cair). */
    static synchronized void baixar(final Context c, final String modelo, final Fim fim) {
        if (baixando != null) return;
        baixando = modelo;
        andamento = "Baixando o Whisper…";
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
                        String base = String.format(ORIGENS[tentativa % ORIGENS.length], modelo);
                        try { baixarArquivo(base + ARQUIVOS[i], destino, "parte " + (i + 1) + " de " + ARQUIVOS.length); ok = true; }
                        catch (Exception e) { ultimo = e; try { Thread.sleep(1500L * (tentativa + 1)); } catch (InterruptedException x) { } }
                    }
                    if (!ok) throw ultimo;
                }
                andamento = "";
            } catch (Exception e) {
                erro = e.getMessage();
                andamento = "Falha ao baixar o Whisper: " + erro;
                Diagnostico.anotar("Whisper: download falhou: " + erro);
            }
            baixando = null;
            if (fim != null) fim.pronto(erro == null, erro);
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
                out.write(b, 0, n);
                feito += n;
                if (feito - ultimo > 512 * 1024) {
                    ultimo = feito;
                    andamento = "Baixando o Whisper (" + rotulo + "): " + (feito >> 20) + (total > 0 ? " de " + (total >> 20) : "") + " MB";
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
