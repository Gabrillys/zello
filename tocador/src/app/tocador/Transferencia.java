package app.tocador;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A parte de rede dos downloads, em Java puro (sem Android), para poder ser testada fora do celular.
 *  - Arquivo direto (mp4, mp3…): copia o arquivo.
 *  - HLS (.m3u8) gravado: baixa todos os pedaços e junta num arquivo só (.ts ou .mp4),
 *    com suporte a AES-128, EXT-X-MAP, EXT-X-BYTERANGE e áudio separado.
 *  - Transmissão ao vivo (rádio Icecast, .m3u8 sem fim) é recusada com uma mensagem clara.
 */
public abstract class Transferencia {

    /** Um download na fila. */
    public static final class Tarefa {
        public final int id;
        public final String url, ua, referer, cookie;
        public volatile String nome, estado = "na fila", erro = ""; // na fila, baixando, concluido, erro, cancelado
        public volatile long feitos, total; // bytes (direto) ou pedaços (HLS)
        public volatile boolean porPedacos, cancelar;
        public Tarefa(int id, String url, String ua, String referer, String cookie, String nome) {
            this.id = id; this.url = url; this.ua = ua; this.referer = referer; this.cookie = cookie; this.nome = nome;
        }
        public boolean ativa() { return estado.equals("na fila") || estado.equals("baixando"); }
    }

    /** Onde o arquivo é gravado. */
    public interface Saida {
        OutputStream fluxo();
        void fechar(boolean ok);
    }

    protected abstract Saida abrirSaida(String nomeArquivo, String mime) throws IOException;
    protected abstract void progresso(Tarefa t);

    // ------------------------------------------------------------ rede

    private HttpURLConnection abrir(Tarefa t, String url, String faixa) throws IOException {
        URL u = new URL(url);
        for (int redirecionamentos = 0; redirecionamentos < 6; redirecionamentos++) {
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            if (t.ua != null && t.ua.length() > 0) c.setRequestProperty("User-Agent", t.ua);
            if (t.referer != null && t.referer.length() > 0) c.setRequestProperty("Referer", t.referer);
            if (t.cookie != null && t.cookie.length() > 0) c.setRequestProperty("Cookie", t.cookie);
            if (faixa != null) c.setRequestProperty("Range", faixa);
            int cod = c.getResponseCode();
            if (cod >= 300 && cod < 400 && c.getHeaderField("Location") != null) {
                u = new URL(u, c.getHeaderField("Location"));
                c.disconnect();
                continue;
            }
            if (cod >= 400) { c.disconnect(); throw new Recusado(cod); }
            return c;
        }
        throw new IOException("Redirecionamentos demais.");
    }

    static final class Recusado extends IOException {
        final int codigo;
        Recusado(int c) { super("O servidor recusou (erro " + c + ")."); codigo = c; }
    }

    private byte[] lerTudo(Tarefa t, String url, String faixa) throws IOException {
        IOException ultimo = null;
        for (int tentativa = 0; tentativa < 4; tentativa++) {
            if (t.cancelar) throw new IOException("cancelado");
            HttpURLConnection c = null;
            try {
                c = abrir(t, url, faixa);
                InputStream in = c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) { if (t.cancelar) throw new IOException("cancelado"); out.write(buf, 0, n); }
                in.close();
                return out.toByteArray();
            } catch (Recusado e) {
                if (e.codigo < 500) throw e; // 4xx não melhora tentando de novo
                ultimo = e;
            } catch (IOException e) {
                if (t.cancelar) throw e;
                ultimo = e;
            } finally { if (c != null) c.disconnect(); }
            try { Thread.sleep(1500L * (tentativa + 1)); } catch (InterruptedException x) { }
        }
        throw ultimo;
    }

    private String lerTexto(Tarefa t, String url) throws IOException {
        return new String(lerTudo(t, url, null), "UTF-8");
    }

    // ------------------------------------------------------------ execução

    public void executar(Tarefa t) throws Exception {
        String url = t.url.replaceAll("[&?](bytestart|byteend)=\\d+", ""); // Facebook/Instagram: arquivo inteiro
        if (url.toLowerCase(Locale.US).matches("^[^?#]+\\.m3u8?([?#].*)?$") || url.contains("/manifest/hls_")) { baixarHls(t, url); return; }
        Matcher clen = Pattern.compile("[?&]clen=(\\d+)").matcher(url);
        if (url.contains("googlevideo.com/") && clen.find()) { baixarEmPartes(t, url, Long.parseLong(clen.group(1))); return; }
        HttpURLConnection c = abrir(t, url, null);
        String tipo = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.US);
        if (tipo.contains("mpegurl")) { c.disconnect(); baixarHls(t, url); return; }
        try { baixarDireto(t, c, url, tipo); } finally { c.disconnect(); }
    }

    static String extensao(String tipo, String url) {
        String caminho = url.toLowerCase(Locale.US).replaceAll("[?#].*$", "");
        Matcher m = Pattern.compile("\\.(mp4|m4a|m4v|mp3|aac|ogg|oga|opus|webm|mkv|mov|flac|wav|3gp|ts)$").matcher(caminho);
        if (m.find()) return m.group(1);
        if (tipo.contains("audio/mpeg")) return "mp3";
        if (tipo.contains("aac")) return "aac";
        if (tipo.contains("ogg")) return "ogg";
        if (tipo.contains("webm")) return "webm";
        if (tipo.contains("audio/mp4")) return "m4a";
        return "mp4";
    }

    static String mime(String ext) {
        if (ext.equals("mp3")) return "audio/mpeg";
        if (ext.equals("m4a")) return "audio/mp4";
        if (ext.equals("aac")) return "audio/aac";
        if (ext.equals("ogg") || ext.equals("oga") || ext.equals("opus")) return "audio/ogg";
        if (ext.equals("flac")) return "audio/flac";
        if (ext.equals("wav")) return "audio/wav";
        if (ext.equals("webm")) return "video/webm";
        if (ext.equals("mkv")) return "video/x-matroska";
        if (ext.equals("mov")) return "video/quicktime";
        if (ext.equals("3gp")) return "video/3gpp";
        if (ext.equals("ts")) return "video/mp2t";
        return "video/mp4";
    }

    private void baixarDireto(Tarefa t, HttpURLConnection c, String url, String tipo) throws Exception {
        if (tipo.startsWith("text/html")) throw new IOException("Esse link é uma página, não um arquivo de mídia.");
        if (c.getHeaderField("icy-metaint") != null || c.getHeaderField("icy-name") != null || c.getHeaderField("icy-br") != null)
            throw new IOException("Isso é uma rádio ao vivo; não há arquivo para baixar.");
        long total = c.getContentLength();
        t.total = total > 0 ? total : 0;
        t.feitos = 0;
        String ext = extensao(tipo, url);
        Saida d = abrirSaida(t.nome + "." + ext, mime(ext));
        boolean ok = false;
        try {
            InputStream in = c.getInputStream();
            byte[] buf = new byte[131072];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (t.cancelar) throw new IOException("cancelado");
                d.fluxo().write(buf, 0, n);
                t.feitos += n;
                progresso(t);
            }
            in.close();
            if (t.total > 0 && t.feitos < t.total) throw new IOException("A conexão caiu antes do fim.");
            ok = true;
        } finally { d.fechar(ok); }
    }

    /**
     * Servidores do YouTube limitam a velocidade de quem pede o arquivo inteiro de uma vez;
     * pedindo em partes de 10 MB a velocidade fica normal.
     */
    private void baixarEmPartes(Tarefa t, String url, long total) throws Exception {
        String mimeTipo = "";
        Matcher mm = Pattern.compile("[?&]mime=([^&]+)").matcher(url);
        if (mm.find()) mimeTipo = java.net.URLDecoder.decode(mm.group(1), "UTF-8");
        String ext = mimeTipo.startsWith("audio/mp4") ? "m4a" : mimeTipo.startsWith("audio/webm") ? "webm" : mimeTipo.startsWith("video/webm") ? "webm" : "mp4";
        t.total = total;
        t.feitos = 0;
        final long PARTE = 10L * 1024 * 1024;
        Saida d = abrirSaida(t.nome + "." + ext, mime(ext));
        boolean ok = false;
        try {
            while (t.feitos < total) {
                if (t.cancelar) throw new IOException("cancelado");
                long fim = Math.min(total, t.feitos + PARTE) - 1;
                byte[] dados = lerTudo(t, url, "bytes=" + t.feitos + "-" + fim);
                if (dados.length == 0) throw new IOException("O servidor parou de enviar o arquivo.");
                d.fluxo().write(dados);
                t.feitos += dados.length;
                progresso(t);
            }
            ok = true;
        } finally { d.fechar(ok); }
    }

    // ------------------------------------------------------------ HLS

    private static final Pattern ATRIB = Pattern.compile("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)");

    static HashMap<String, String> atributos(String linha) {
        HashMap<String, String> m = new HashMap<String, String>();
        int p = linha.indexOf(':');
        Matcher x = ATRIB.matcher(p >= 0 ? linha.substring(p + 1) : linha);
        while (x.find()) { String v = x.group(2); if (v.startsWith("\"")) v = v.substring(1, v.length() - 1); m.put(x.group(1), v); }
        return m;
    }

    private static String resolver(String base, String rel) throws IOException { return new URL(new URL(base), rel.trim()).toString(); }

    private static final class Pedaco {
        String url, faixa, chaveUrl, metodo = "NONE";
        byte[] iv;
        long seq;
    }

    private void baixarHls(Tarefa t, String url) throws Exception {
        String texto = lerTexto(t, url);
        if (!texto.trim().startsWith("#EXTM3U")) throw new IOException("Lista .m3u8 inválida.");
        if (!texto.contains("#EXT-X-STREAM-INF")) { baixarListaDeMidia(t, url, texto, t.nome); return; }

        // Lista mestre: escolhe a melhor qualidade.
        String[] linhas = texto.split("\r?\n");
        long melhor = -1; String escolhida = null, grupoAudio = null;
        HashMap<String, String> audios = new HashMap<String, String>();
        for (int i = 0; i < linhas.length; i++) {
            String l = linhas[i].trim();
            if (l.startsWith("#EXT-X-MEDIA:")) {
                HashMap<String, String> a = atributos(l);
                if ("AUDIO".equals(a.get("TYPE")) && a.get("URI") != null && a.get("GROUP-ID") != null
                        && (!audios.containsKey(a.get("GROUP-ID")) || "YES".equals(a.get("DEFAULT"))))
                    audios.put(a.get("GROUP-ID"), resolver(url, a.get("URI")));
            } else if (l.startsWith("#EXT-X-STREAM-INF")) {
                HashMap<String, String> a = atributos(l);
                long bw = 0; try { bw = Long.parseLong(a.get("BANDWIDTH")); } catch (Exception e) { }
                int j = i + 1; while (j < linhas.length && (linhas[j].trim().length() == 0 || linhas[j].trim().startsWith("#"))) j++;
                if (j < linhas.length && bw > melhor) { melhor = bw; escolhida = resolver(url, linhas[j]); grupoAudio = a.get("AUDIO"); }
            }
        }
        if (escolhida == null) throw new IOException("Não achei nenhuma qualidade na lista .m3u8.");
        String audioSeparado = grupoAudio == null ? null : audios.get(grupoAudio);
        String video = lerTexto(t, escolhida);
        if (audioSeparado == null) { baixarListaDeMidia(t, escolhida, video, t.nome); return; }
        // Vídeo e áudio vêm em listas separadas: baixa os dois, em dois arquivos.
        String audio = lerTexto(t, audioSeparado);
        String base = t.nome;
        baixarListaDeMidia(t, escolhida, video, base + " (vídeo)");
        if (t.cancelar) return;
        baixarListaDeMidia(t, audioSeparado, audio, base + " (áudio)");
        t.nome = base + " (vídeo e áudio em 2 arquivos)";
    }

    private void baixarListaDeMidia(Tarefa t, String base, String texto, String nome) throws Exception {
        if (!texto.contains("#EXT-X-ENDLIST"))
            throw new IOException("Isso é uma transmissão ao vivo; só dá para baixar vídeos e áudios gravados.");
        String[] linhas = texto.split("\r?\n");
        ArrayList<Pedaco> pedacos = new ArrayList<Pedaco>();
        String mapa = null, mapaFaixa = null, metodo = "NONE", chave = null, faixa = null;
        byte[] iv = null;
        long seq = 0, proximoInicio = 0;
        for (String bruta : linhas) {
            String l = bruta.trim();
            if (l.length() == 0) continue;
            if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                try { seq = Long.parseLong(l.substring(22).trim()); } catch (Exception e) { }
            } else if (l.startsWith("#EXT-X-KEY:")) {
                HashMap<String, String> a = atributos(l);
                metodo = a.get("METHOD") == null ? "NONE" : a.get("METHOD");
                if (!metodo.equals("NONE") && !metodo.equals("AES-128"))
                    throw new IOException("Esse vídeo tem proteção (DRM) e não pode ser baixado.");
                chave = a.get("URI") == null ? null : resolver(base, a.get("URI"));
                iv = a.get("IV") == null ? null : hex(a.get("IV"));
            } else if (l.startsWith("#EXT-X-MAP:")) {
                HashMap<String, String> a = atributos(l);
                mapa = resolver(base, a.get("URI"));
                mapaFaixa = a.get("BYTERANGE") == null ? null : faixaHttp(a.get("BYTERANGE"), 0);
            } else if (l.startsWith("#EXT-X-BYTERANGE:")) {
                String v = l.substring(17).trim();
                String[] p = v.split("@");
                long len = Long.parseLong(p[0].trim());
                long ini = p.length > 1 ? Long.parseLong(p[1].trim()) : proximoInicio;
                faixa = "bytes=" + ini + "-" + (ini + len - 1);
                proximoInicio = ini + len;
            } else if (!l.startsWith("#")) {
                Pedaco p = new Pedaco();
                p.url = resolver(base, l); p.faixa = faixa; p.metodo = metodo; p.chaveUrl = chave; p.iv = iv; p.seq = seq++;
                pedacos.add(p);
                faixa = null;
            }
        }
        if (pedacos.isEmpty()) throw new IOException("A lista .m3u8 está vazia.");

        boolean fmp4 = mapa != null;
        t.porPedacos = true;
        t.total = pedacos.size();
        t.feitos = 0;
        HashMap<String, byte[]> chaves = new HashMap<String, byte[]>();
        Saida d = abrirSaida(nome + (fmp4 ? ".mp4" : ".ts"), fmp4 ? "video/mp4" : "video/mp2t");
        boolean ok = false;
        try {
            if (mapa != null) d.fluxo().write(lerTudo(t, mapa, mapaFaixa));
            for (Pedaco p : pedacos) {
                if (t.cancelar) throw new IOException("cancelado");
                byte[] dados = lerTudo(t, p.url, p.faixa);
                if ("AES-128".equals(p.metodo) && p.chaveUrl != null) {
                    byte[] k = chaves.get(p.chaveUrl);
                    if (k == null) { k = lerTudo(t, p.chaveUrl, null); chaves.put(p.chaveUrl, k); }
                    byte[] vetor = p.iv;
                    if (vetor == null) { vetor = new byte[16]; long s = p.seq; for (int i = 15; i >= 8; i--) { vetor[i] = (byte) (s & 0xff); s >>>= 8; } }
                    Cipher cf = Cipher.getInstance("AES/CBC/PKCS5Padding");
                    cf.init(Cipher.DECRYPT_MODE, new SecretKeySpec(k, "AES"), new IvParameterSpec(vetor));
                    dados = cf.doFinal(dados);
                }
                d.fluxo().write(dados);
                t.feitos++;
                progresso(t);
            }
            ok = true;
        } finally { d.fechar(ok); }
    }

    private static String faixaHttp(String v, long padrao) {
        String[] p = v.split("@");
        long len = Long.parseLong(p[0].trim());
        long ini = p.length > 1 ? Long.parseLong(p[1].trim()) : padrao;
        return "bytes=" + ini + "-" + (ini + len - 1);
    }

    private static byte[] hex(String s) {
        s = s.startsWith("0x") || s.startsWith("0X") ? s.substring(2) : s;
        while (s.length() < 32) s = "0" + s;
        byte[] b = new byte[16];
        for (int i = 0; i < 16; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
}
