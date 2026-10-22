package app.tocador;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Downloads de vídeos, músicas e playlists inteiras (YouTube e centenas de outros sites) com o yt-dlp,
 * o programa de código aberto que sites como o Cobalt também usam. Roda no próprio celular:
 * Python 3.12, QuickJS (para destravar os links do YouTube) e FFmpeg (para juntar áudio e vídeo),
 * compilados para Android pelo projeto youtubedl-android. O yt-dlp pode ser atualizado pelo app.
 */
final class Baixador {

    private static Baixador unico;

    static synchronized Baixador de(Context c) {
        if (unico == null) unico = new Baixador(c.getApplicationContext());
        return unico;
    }

    static synchronized Baixador seExistir() { return unico; }

    interface Pronto { void terminou(String nome, String tipo, String origem, List<String[]> arquivos); }

    private final Context ctx;
    private final File base, python, ffmpeg, rodas;
    private volatile boolean trabalhando, cancelado;
    private volatile String estado = "", titulo = "", erro = "";
    private volatile int atual, total, feitos, falhas;
    private volatile double pct;
    private volatile Process proc;

    private Baixador(Context c) {
        ctx = c;
        base = new File(Build.VERSION.SDK_INT >= 21 ? c.getNoBackupFilesDir() : c.getFilesDir(), "ytdlp");
        python = new File(base, "python");
        ffmpeg = new File(base, "ffmpeg");
        rodas = new File(base, "rodas");
    }

    static boolean disponivel() { return Principal.whisperDisponivel(); } // mesmas bibliotecas: só 64 bits (arm64)

    JSONObject estado() {
        JSONObject o = new JSONObject();
        try {
            o.put("trabalhando", trabalhando).put("estado", estado).put("titulo", titulo).put("atual", atual).put("total", total)
             .put("pct", pct).put("feitos", feitos).put("falhas", falhas).put("erro", erro).put("versao", versaoMotor())
             .put("disponivel", disponivel());
        } catch (Exception e) { }
        return o;
    }

    void cancelar() {
        cancelado = true;
        Process p = proc;
        if (p != null) p.destroy();
    }

    // ------------------------------------------------------------ baixar

    /**
     * @param tipo "audio" (música, m4a com nome do artista e título) ou "video" (mp4 até 1080p)
     * @param playlist baixar a playlist/canal inteiro (senão, só o vídeo do link)
     */
    synchronized void baixar(final String url, final String tipo, final boolean playlist, final Pronto pronto) {
        if (trabalhando) return;
        trabalhando = true;
        cancelado = false;
        estado = "Preparando o motor de download…";
        titulo = ""; erro = ""; atual = 0; total = 0; feitos = 0; falhas = 0; pct = 0;
        ServicoDownload.iniciar(ctx); // mantém o download vivo com a tela apagada
        new Thread(new Runnable() { public void run() {
            final List<String[]> salvos = new ArrayList<String[]>();
            String nomeLista = null;
            try {
                preparar();
                File saida = new File(ctx.getExternalFilesDir(null), "baixando");
                saida.mkdirs();
                List<String> cmd = comando(ctx.getApplicationInfo().nativeLibraryDir, new File(ctx.getCacheDir(), "ytdlp").getPath(), tipo, playlist, saida);
                cmd.add(url);
                estado = "Lendo o link…";
                Diagnostico.anotar("Download: " + Diagnostico.curto(url) + " (" + tipo + (playlist ? ", playlist" : "") + ")");
                ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).directory(saida);
                ambiente(pb.environment());
                proc = pb.start();
                BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));
                String l;
                String ultimaLinha = "";
                while ((l = r.readLine()) != null) {
                    if (l.startsWith("TOC|")) progresso(l);
                    else if (l.startsWith("TOCFIM|")) {
                        String[] p = l.split("\\|", 4);
                        if (p.length >= 3) {
                            File f = new File(p[1]);
                            if (p.length >= 4 && p[3].length() > 0 && !"NA".equals(p[3])) nomeLista = p[3];
                            String uri = publicar(f, tipo, nomeLista);
                            if (uri != null) { salvos.add(new String[] { p[2], uri }); feitos++; }
                        }
                    } else if (l.startsWith("ERROR:")) {
                        falhas++;
                        erro = l.substring(6).trim();
                        Diagnostico.anotar("yt-dlp: " + Diagnostico.curto(erro));
                    } else if (l.contains("Downloading playlist") || l.contains("Downloading item")) {
                        estado = "Lendo a playlist…";
                    }
                    if (l.trim().length() > 0) ultimaLinha = l;
                }
                int fim = proc.waitFor();
                proc = null;
                if (cancelado) estado = "Cancelado. " + feitos + " arquivo(s) salvos.";
                else if (feitos == 0) {
                    estado = "Nada foi baixado" + (erro.length() > 0 ? ": " + erro : (fim != 0 ? " (" + ultimaLinha + ")" : "") + ".")
                            + " Se o YouTube mudou algo, toque em Atualizar o motor.";
                } else estado = "Pronto: " + feitos + " arquivo(s) em " + pastaDestino(tipo) + (falhas > 0 ? " (" + falhas + " com erro)" : "") + ".";
            } catch (Throwable e) {
                Diagnostico.anotar("Download falhou: " + e);
                estado = "Não consegui baixar: " + (e.getMessage() == null ? e.toString() : e.getMessage());
            } finally {
                trabalhando = false;
                proc = null;
            }
            try { if (!salvos.isEmpty() && pronto != null) pronto.terminou(nomeLista, tipo, url, salvos); }
            finally { ServicoDownload.encerrar(ctx, estado); }
        } }, "tocador-download").start();
    }

    // ------------------------------------------------------------ tocar no VLC sem login (Instagram, TikTok…)

    interface ProntoCache { void pronto(File arquivo, String titulo); void falhou(String motivo); }

    private volatile boolean cacheTrabalhando, cacheCancelado;
    private volatile String cacheEstado = "";
    private volatile double cachePct;
    private volatile Process cacheProc;

    JSONObject estadoCache() {
        JSONObject o = new JSONObject();
        try { o.put("trabalhando", cacheTrabalhando).put("estado", cacheEstado).put("pct", cachePct); } catch (Exception e) { }
        return o;
    }

    void cancelarCache() {
        cacheCancelado = true;
        Process p = cacheProc;
        if (p != null) p.destroy();
    }

    private File pastaCache() { return new File(ctx.getCacheDir(), "tocar"); }

    /**
     * Busca o vídeo de uma rede social (sem conta, como visitante) e guarda numa pasta temporária do app,
     * para o player VLC tocar dali. O mesmo link, aberto de novo, toca na hora.
     */
    synchronized void paraCache(final String url, final ProntoCache cb) {
        final String chave = Dublador.hash(url);
        final File pasta = pastaCache();
        File[] ja = pasta.listFiles();
        if (ja != null) for (File f : ja) if (f.getName().startsWith(chave + ".") && !f.getName().endsWith(".part") && !f.getName().endsWith(".titulo") && !f.getName().endsWith(".ytdl") && f.length() > 0) {
            f.setLastModified(System.currentTimeMillis());
            cb.pronto(f, ler(new File(pasta, chave + ".titulo")));
            return;
        }
        if (cacheTrabalhando) cancelarCache();
        cacheTrabalhando = true;
        cacheCancelado = false;
        cachePct = 0;
        cacheEstado = "Buscando o vídeo…";
        new Thread(new Runnable() { public void run() {
            String motivo = null;
            File achado = null;
            String titulo = "";
            try {
                preparar();
                pasta.mkdirs();
                limparCache(pasta);
                String nativas = ctx.getApplicationInfo().nativeLibraryDir;
                List<String> c = new ArrayList<String>();
                c.add(nativas + "/libpython.so"); c.add("-m"); c.add("yt_dlp");
                c.add("--js-runtimes"); c.add("quickjs:" + nativas + "/libqjs.so");
                c.add("--ffmpeg-location"); c.add(nativas + "/libffmpeg.so");
                c.add("--cache-dir"); c.add(new File(ctx.getCacheDir(), "ytdlp").getPath());
                c.add("--no-colors"); c.add("--newline"); c.add("--progress"); c.add("--no-playlist"); c.add("--no-mtime");
                c.add("-f"); c.add("bv*[height<=1080][ext=mp4]+ba[ext=m4a]/bv*[height<=1080]+ba/b[ext=mp4]/b");
                c.add("--merge-output-format"); c.add("mp4");
                c.add("-o"); c.add(pasta.getPath() + "/" + chave + ".%(ext)s");
                c.add("--progress-template"); c.add("download:TOC|1|1|%(progress._percent_str)s|%(info.title)s");
                c.add("--print"); c.add("after_move:TOCFIM|%(filepath)s|%(title)s|");
                c.add("--no-simulate");
                c.add(url);
                ProcessBuilder pb = new ProcessBuilder(c).redirectErrorStream(true).directory(pasta);
                ambiente(pb.environment());
                cacheProc = pb.start();
                BufferedReader r = new BufferedReader(new InputStreamReader(cacheProc.getInputStream(), "UTF-8"));
                String l, erro = "";
                while ((l = r.readLine()) != null) {
                    if (l.startsWith("TOC|")) {
                        String[] p = l.split("\\|", 5);
                        try { cachePct = Double.parseDouble(p[3].replace("%", "").trim()); } catch (Exception e) { }
                        cacheEstado = "Preparando o vídeo… " + Math.round(cachePct) + "%";
                    } else if (l.startsWith("TOCFIM|")) {
                        String[] p = l.split("\\|", 4);
                        achado = new File(p[1]);
                        titulo = p.length > 2 ? p[2] : "";
                    } else if (l.startsWith("ERROR:")) erro = l.substring(6).trim();
                }
                cacheProc.waitFor();
                cacheProc = null;
                if (cacheCancelado) motivo = "cancelado";
                else if (achado == null || !achado.exists()) motivo = erro.length() > 0 ? erro : "o site não entregou o vídeo";
                else gravar(new File(pasta, chave + ".titulo"), titulo);
            } catch (Throwable e) {
                motivo = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                cacheTrabalhando = false;
                cacheEstado = "";
            }
            if (motivo == null) { Diagnostico.anotar("Vídeo pronto para o VLC: " + Diagnostico.curto(url)); cb.pronto(achado, titulo); }
            else { Diagnostico.anotar("Vídeo não preparado (" + Diagnostico.curto(url) + "): " + Diagnostico.curto(motivo)); cb.falhou(motivo); }
        } }, "tocador-cache").start();
    }

    /** A pasta temporária guarda no máximo ~1,5 GB: os vídeos mais antigos saem primeiro. */
    private static void limparCache(File pasta) {
        File[] fs = pasta.listFiles();
        if (fs == null) return;
        java.util.Arrays.sort(fs, new java.util.Comparator<File>() { public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); } });
        long total = 0;
        for (File f : fs) { total += f.length(); if (total > 1500L << 20 || f.getName().endsWith(".part")) f.delete(); }
    }

    private void progresso(String l) {
        // TOC|índice|total|porcentagem|título
        String[] p = l.split("\\|", 5);
        if (p.length < 5) return;
        try { atual = Integer.parseInt(p[1].trim()); } catch (Exception e) { atual = 0; }
        try { total = Integer.parseInt(p[2].trim()); } catch (Exception e) { total = 0; }
        try { pct = Double.parseDouble(p[3].replace("%", "").trim()); } catch (Exception e) { }
        titulo = p[4];
        estado = (total > 1 ? "Baixando " + atual + " de " + total + ": " : "Baixando: ") + titulo;
    }

    /** Linha de comando do yt-dlp (separada para ser testada no computador). */
    static List<String> comando(String nativas, String cache, String tipo, boolean playlist, File saida) {
        List<String> c = new ArrayList<String>();
        c.add(nativas + "/libpython.so");
        c.add("-m"); c.add("yt_dlp");
        c.add("--js-runtimes"); c.add("quickjs:" + nativas + "/libqjs.so");
        c.add("--ffmpeg-location"); c.add(nativas + "/libffmpeg.so");
        c.add("--cache-dir"); c.add(cache);
        c.add("--no-colors"); c.add("--newline"); c.add("--progress"); c.add("--ignore-errors");
        c.add("--no-mtime"); c.add("--windows-filenames"); c.add("--no-overwrites");
        c.add(playlist ? "--yes-playlist" : "--no-playlist");
        if ("audio".equals(tipo)) {
            c.add("-f"); c.add("bestaudio[ext=m4a]/bestaudio/best");
            c.add("--embed-metadata");
            c.add("--parse-metadata"); c.add("%(artist,creator,uploader)s:%(meta_artist)s");
        } else {
            c.add("-f"); c.add("bv*[height<=1080][ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*[height<=1080]+ba/b");
            c.add("--merge-output-format"); c.add("mp4");
            c.add("--embed-metadata");
        }
        c.add("-o"); c.add(saida.getPath() + "/%(playlist_index&{:03d} - |)s%(title).150B.%(ext)s");
        c.add("--progress-template"); c.add("download:TOC|%(info.playlist_index|1)s|%(info.n_entries|1)s|%(progress._percent_str)s|%(info.title)s");
        c.add("--print"); c.add("after_move:TOCFIM|%(filepath)s|%(title)s|%(playlist_title|)s");
        c.add("--no-simulate");
        return c;
    }

    private void ambiente(Map<String, String> e) {
        e.put("PYTHONHOME", new File(python, "usr").getPath());
        e.put("LD_LIBRARY_PATH", new File(python, "usr/lib").getPath() + ":" + new File(ffmpeg, "usr/lib").getPath());
        e.put("SSL_CERT_FILE", new File(python, "usr/etc/tls/cert.pem").getPath());
        e.put("PYTHONPATH", new File(rodas, "yt_dlp.whl").getPath() + ":" + new File(rodas, "yt_dlp_ejs.whl").getPath());
        e.put("HOME", ctx.getFilesDir().getPath());
        e.put("TMPDIR", ctx.getCacheDir().getPath());
        e.put("PYTHONIOENCODING", "utf-8");
        e.put("PYTHONUTF8", "1");
        e.put("LANG", "C.UTF-8");
    }

    // ------------------------------------------------------------ guardar no aparelho

    private String pastaDestino(String tipo) { return ("audio".equals(tipo) ? "Música" : "Filmes") + "/Tocador"; }

    /** Move o arquivo terminado para Música/Tocador (ou Filmes/Tocador) e devolve o endereço para tocar. */
    /** O arquivo está na pasta temporária do "tocar no VLC"? */
    boolean estaNoCache(String url) {
        try {
            if (url == null || !url.startsWith("file://")) return false;
            return new File(Uri.parse(url).getPath()).getCanonicalPath().startsWith(pastaCache().getCanonicalPath());
        } catch (Exception e) { return false; }
    }

    /**
     * Salva no aparelho (Filmes/Tocador) uma cópia do vídeo que está na pasta temporária, sem baixar de novo.
     * Devolve o endereço salvo, ou null.
     */
    String salvarDoCache(String url, String titulo) {
        if (!estaNoCache(url)) return null;
        try {
            File origem = new File(Uri.parse(url).getPath());
            String ext = origem.getName().contains(".") ? origem.getName().substring(origem.getName().lastIndexOf('.')) : ".mp4";
            File copia = new File(ctx.getExternalFilesDir(null), "baixando/" + Arquivos.nomeSeguro(titulo == null || titulo.length() == 0 ? "Vídeo" : titulo) + ext);
            copia.getParentFile().mkdirs();
            copiar(new FileInputStream(origem), new FileOutputStream(copia));
            String tipo = ext.equals(".m4a") || ext.equals(".mp3") || ext.equals(".opus") ? "audio" : "video";
            return publicar(copia, tipo, null); // publicar move a cópia e a registra como baixada pelo app
        } catch (Exception e) {
            Diagnostico.anotar("Salvar do cache falhou: " + e);
            return null;
        }
    }

    private String publicar(File f, String tipo, String lista) {
        if (!f.exists()) return null;
        boolean audio = "audio".equals(tipo);
        String sub = (audio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES) + "/Tocador"
                + (lista != null && lista.length() > 0 ? "/" + Arquivos.nomeSeguro(lista) : "");
        String nome = f.getName();
        String mime = nome.endsWith(".m4a") ? "audio/mp4" : nome.endsWith(".webm") ? (audio ? "audio/webm" : "video/webm")
                : nome.endsWith(".opus") ? "audio/ogg" : nome.endsWith(".mp3") ? "audio/mpeg" : audio ? "audio/mp4" : "video/mp4";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, nome);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, sub);
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                ContentResolver cr = ctx.getContentResolver();
                Uri col = audio ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                Uri u = cr.insert(col, v);
                if (u == null) return null;
                OutputStream o = cr.openOutputStream(u);
                copiar(new FileInputStream(f), o);
                v.clear();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(u, v, null, null);
                f.delete();
                registrar(ctx, u.toString());
                return u.toString();
            }
            File pasta = new File(Environment.getExternalStoragePublicDirectory(audio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES),
                    "Tocador" + (lista != null && lista.length() > 0 ? "/" + Arquivos.nomeSeguro(lista) : ""));
            pasta.mkdirs();
            File destino = new File(pasta, nome);
            if (!f.renameTo(destino)) { copiar(new FileInputStream(f), new FileOutputStream(destino)); f.delete(); }
            android.media.MediaScannerConnection.scanFile(ctx, new String[] { destino.getPath() }, new String[] { mime }, null);
            registrar(ctx, Uri.fromFile(destino).toString());
            return Uri.fromFile(destino).toString();
        } catch (Exception e) {
            Diagnostico.anotar("Download: não consegui guardar " + nome + ": " + e);
            return null;
        }
    }

    // ------------------------------------------------------------ registro do que o app baixou

    /**
     * Guarda o endereço de cada arquivo que o próprio Tocador baixou. Só esses podem ser apagados pelo app:
     * vídeos da câmera, músicas de outros apps e qualquer outro arquivo do celular nunca entram aqui.
     */
    static synchronized void registrar(Context c, String uri) {
        android.content.SharedPreferences p = c.getSharedPreferences("baixados", Context.MODE_PRIVATE);
        java.util.Set<String> s = new java.util.HashSet<String>(p.getStringSet("uris", new java.util.HashSet<String>()));
        s.add(uri);
        p.edit().putStringSet("uris", s).apply();
    }

    static synchronized boolean foiBaixadoPeloApp(Context c, String uri) {
        return c.getSharedPreferences("baixados", Context.MODE_PRIVATE).getStringSet("uris", new java.util.HashSet<String>()).contains(uri);
    }

    static synchronized void esquecer(Context c, String uri) {
        android.content.SharedPreferences p = c.getSharedPreferences("baixados", Context.MODE_PRIVATE);
        java.util.Set<String> s = new java.util.HashSet<String>(p.getStringSet("uris", new java.util.HashSet<String>()));
        s.remove(uri);
        p.edit().putStringSet("uris", s).apply();
    }

    private static void copiar(InputStream in, OutputStream out) throws Exception {
        try {
            byte[] b = new byte[256 * 1024];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } finally { in.close(); out.close(); }
    }

    // ------------------------------------------------------------ preparar o motor (primeira vez)

    private void preparar() throws Exception {
        if (!disponivel()) throw new Exception("o download precisa de um celular de 64 bits");
        String nativas = ctx.getApplicationInfo().nativeLibraryDir;
        String marca = Long.toString(new File(nativas, "libpython.zip.so").length()) + "/" + new File(nativas, "libffmpeg.zip.so").length();
        File feito = new File(base, "preparado");
        if (!marca.equals(ler(feito))) {
            estado = "Instalando o motor de download (só na primeira vez)…";
            apagar(python); apagar(ffmpeg);
            ZipComLinks.extrair(new File(nativas, "libpython.zip.so"), python);
            ZipComLinks.extrair(new File(nativas, "libffmpeg.zip.so"), ffmpeg);
            gravar(feito, marca);
        }
        rodas.mkdirs();
        for (String w : new String[] { "yt_dlp.whl", "yt_dlp_ejs.whl" }) {
            File f = new File(rodas, w);
            File versaoApp = new File(rodas, w + ".app");
            String doApp = lerAsset("ytdlp/" + w + ".versao");
            // A versão que veio com o app substitui a instalada só se for mais nova (a atualização pelo app pode ser mais nova ainda).
            if (!f.exists() || (doApp != null && maisNova(doApp, ler(versaoApp)) && maisNova(doApp, ler(new File(rodas, w + ".versao"))))) {
                copiar(ctx.getAssets().open("ytdlp/" + w), new FileOutputStream(f));
                gravar(versaoApp, doApp == null ? "" : doApp);
                gravar(new File(rodas, w + ".versao"), doApp == null ? "" : doApp);
            }
        }
    }

    String versaoMotor() {
        String v = ler(new File(rodas, "yt_dlp.whl.versao"));
        if (v == null || v.length() == 0) v = lerAsset("ytdlp/yt_dlp.whl.versao");
        return v == null ? "" : v.trim();
    }

    /** Baixa a versão mais nova do yt-dlp (e do resolvedor de JavaScript) do PyPI, o repositório oficial do Python. */
    synchronized void atualizarMotor() {
        if (trabalhando) return;
        trabalhando = true;
        cancelado = false;
        estado = "Procurando a versão mais nova do motor…";
        new Thread(new Runnable() { public void run() {
            try {
                rodas.mkdirs();
                String[][] pacotes = { { "yt-dlp", "yt_dlp.whl" }, { "yt-dlp-ejs", "yt_dlp_ejs.whl" } };
                StringBuilder msg = new StringBuilder();
                for (String[] p : pacotes) {
                    JSONObject j = new JSONObject(texto("https://pypi.org/pypi/" + p[0] + "/json"));
                    String versao = j.getJSONObject("info").getString("version");
                    if (versao.equals(ler(new File(rodas, p[1] + ".versao")))) { msg.append(p[0]).append(' ').append(versao).append(" (já era a mais nova) "); continue; }
                    JSONArray urls = j.getJSONArray("urls");
                    String wheel = null;
                    for (int i = 0; i < urls.length(); i++) if (urls.getJSONObject(i).getString("filename").endsWith(".whl")) wheel = urls.getJSONObject(i).getString("url");
                    if (wheel == null) throw new Exception("pacote " + p[0] + " sem wheel");
                    File tmp = new File(rodas, p[1] + ".tmp");
                    HttpURLConnection c = (HttpURLConnection) new URL(wheel).openConnection();
                    c.setConnectTimeout(20000); c.setReadTimeout(60000);
                    copiar(c.getInputStream(), new FileOutputStream(tmp));
                    if (tmp.length() < 1000 || !tmp.renameTo(new File(rodas, p[1]))) throw new Exception("download incompleto");
                    gravar(new File(rodas, p[1] + ".versao"), versao);
                    msg.append(p[0]).append(" atualizado para ").append(versao).append(' ');
                }
                estado = "Motor: " + msg.toString().trim() + ".";
                Diagnostico.anotar("Motor de download: " + estado);
            } catch (Exception e) {
                estado = "Não consegui atualizar o motor: " + e.getMessage();
            } finally { trabalhando = false; }
        } }, "tocador-motor").start();
    }

    /** Compara versões no formato do yt-dlp (2026.8.19, 2026.08.19, 0.8.0). */
    static boolean maisNova(String a, String b) {
        if (b == null || b.trim().length() == 0) return true;
        String[] x = a.trim().split("\\."), y = b.trim().split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int num(String s) { try { return Integer.parseInt(s.replaceAll("\\D.*$", "")); } catch (Exception e) { return 0; } }

    private static String texto(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
        return Listas.ler(c.getInputStream(), 8 << 20);
    }

    private String lerAsset(String nome) {
        try { return Listas.ler(ctx.getAssets().open(nome), 1 << 16).trim(); } catch (Exception e) { return null; }
    }

    private static String ler(File f) {
        try { return f.exists() ? Listas.ler(new FileInputStream(f), 1 << 16).trim() : null; } catch (Exception e) { return null; }
    }

    private static void gravar(File f, String s) throws Exception {
        f.getParentFile().mkdirs();
        FileOutputStream o = new FileOutputStream(f);
        o.write(s.getBytes("UTF-8"));
        o.close();
    }

    private static void apagar(File f) {
        if (f.isDirectory() && !ehLink(f)) { File[] fs = f.listFiles(); if (fs != null) for (File x : fs) apagar(x); }
        f.delete();
    }

    private static boolean ehLink(File f) {
        try { return !f.getCanonicalFile().equals(f.getAbsoluteFile()); } catch (Exception e) { return false; }
    }

    /**
     * Extrai um zip mantendo os atalhos (symlinks) das bibliotecas, que o java.util.zip não informa:
     * os atributos Unix são lidos direto do diretório central do zip.
     */
    static final class ZipComLinks {
        static void extrair(File zip, File destino) throws Exception {
            RandomAccessFile raf = new RandomAccessFile(zip, "r");
            try {
                long tam = raf.length();
                int busca = (int) Math.min(tam, 65557);
                byte[] fim = new byte[busca];
                raf.seek(tam - busca);
                raf.readFully(fim);
                int e = -1;
                for (int i = busca - 22; i >= 0; i--) if (u32(fim, i) == 0x06054b50L) { e = i; break; }
                if (e < 0) throw new Exception("zip inválido");
                int n = u16(fim, e + 10);
                long cd = u32(fim, e + 16);
                raf.seek(cd);
                List<long[]> entradas = new ArrayList<long[]>();
                List<String> nomes = new ArrayList<String>();
                for (int k = 0; k < n; k++) {
                    byte[] h = new byte[46];
                    raf.readFully(h);
                    if (u32(h, 0) != 0x02014b50L) throw new Exception("zip corrompido");
                    int metodo = u16(h, 10), ln = u16(h, 28), lx = u16(h, 30), lc = u16(h, 32);
                    long comp = u32(h, 20), orig = u32(h, 24), ext = u32(h, 38), local = u32(h, 42);
                    byte[] nb = new byte[ln];
                    raf.readFully(nb);
                    raf.skipBytes(lx + lc);
                    nomes.add(new String(nb, "UTF-8"));
                    entradas.add(new long[] { metodo, comp, orig, ext, local });
                }
                Map<String, String> links = new HashMap<String, String>();
                for (int k = 0; k < n; k++) {
                    String nome = nomes.get(k);
                    long[] x = entradas.get(k);
                    if (nome.contains("..")) continue;
                    File f = new File(destino, nome);
                    if (nome.endsWith("/")) { f.mkdirs(); continue; }
                    f.getParentFile().mkdirs();
                    byte[] lh = new byte[30];
                    raf.seek(x[4]);
                    raf.readFully(lh);
                    long dados = x[4] + 30 + u16(lh, 26) + u16(lh, 28);
                    InputStream in = new Trecho(raf, dados, x[1]);
                    if (x[0] == 8) in = new InflaterInputStream(in, new Inflater(true), 65536);
                    int modo = (int) (x[3] >>> 16);
                    if ((modo & 0xF000) == 0xA000) { links.put(f.getPath(), Listas.ler(in, 4096)); continue; }
                    copiar(in, new FileOutputStream(f));
                    if ((modo & 0111) != 0) f.setExecutable(true, false);
                }
                // Atalhos podem apontar para outros atalhos: repete até todos estarem resolvidos.
                for (int volta = 0; volta < 5 && !links.isEmpty(); volta++) {
                    for (java.util.Iterator<Map.Entry<String, String>> it = links.entrySet().iterator(); it.hasNext(); ) {
                        Map.Entry<String, String> l = it.next();
                        File f = new File(l.getKey());
                        f.delete();
                        try { android.system.Os.symlink(l.getValue(), f.getPath()); it.remove(); }
                        catch (Throwable t) { // sem atalho: copia o arquivo de destino
                            File alvo = new File(f.getParentFile(), l.getValue());
                            if (alvo.exists()) { copiar(new FileInputStream(alvo), new FileOutputStream(f)); it.remove(); }
                        }
                    }
                }
            } finally { raf.close(); }
        }

        private static int u16(byte[] b, int i) { return (b[i] & 0xff) | (b[i + 1] & 0xff) << 8; }
        private static long u32(byte[] b, int i) { return (u16(b, i) | (long) u16(b, i + 2) << 16) & 0xffffffffL; }

        /** Lê um pedaço do arquivo (os dados de uma entrada do zip). */
        private static final class Trecho extends InputStream {
            private final RandomAccessFile raf;
            private long pos, resta;
            Trecho(RandomAccessFile r, long inicio, long tamanho) { raf = r; pos = inicio; resta = tamanho; }
            @Override public int read() throws java.io.IOException { byte[] b = new byte[1]; return read(b, 0, 1) < 0 ? -1 : b[0] & 0xff; }
            @Override public int read(byte[] b, int o, int n) throws java.io.IOException {
                if (resta <= 0) return -1;
                n = (int) Math.min(n, resta);
                raf.seek(pos);
                int lido = raf.read(b, o, n);
                if (lido > 0) { pos += lido; resta -= lido; }
                return lido;
            }
            @Override public void close() { }
        }
    }
}
