package app.tocador;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Vozes neurais da Microsoft (as mesmas do "Ler em voz alta" do Edge), pelo protocolo
 * do projeto de código aberto edge-tts (github.com/rany2/edge-tts, licença LGPL/GPL; aqui só o protocolo).
 * Devolve MP3 mono 24 kHz, 48 kbit/s (taxa constante: a duração sai direto do tamanho).
 */
public final class VozNeural {

    public static final String[][] VOZES = {
        { "pt-BR-FranciscaNeural", "Francisca (feminina, Brasil)" },
        { "pt-BR-AntonioNeural", "Antônio (masculina, Brasil)" },
        { "pt-BR-ThalitaMultilingualNeural", "Thalita (feminina, Brasil)" },
        { "pt-PT-RaquelNeural", "Raquel (feminina, Portugal)" },
        { "pt-PT-DuarteNeural", "Duarte (masculina, Portugal)" },
    };

    static final String TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    static final String CHROMIUM = "143.0.3650.75";
    static final String ENDERECO = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=" + TOKEN;
    static final String MAIOR = CHROMIUM.substring(0, CHROMIUM.indexOf('.'));
    static final String AGENTE = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
            + MAIOR + ".0.0.0 Safari/537.36 Edg/" + MAIOR + ".0.0.0";

    /** Diferença entre o relógio do celular e o do servidor (corrigida quando o servidor recusa). */
    static volatile double desvioSegundos = 0;
    /** Para testes: outro servidor. */
    static String enderecoBase = ENDERECO;

    private final OkHttpClient cliente;

    public VozNeural() {
        cliente = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /** Duração (ms) do MP3 de 48 kbit/s. */
    public static long duracaoMs(byte[] mp3) { return mp3.length * 8L / 48; }

    /**
     * Sintetiza uma frase.
     * @param voz   ex.: "pt-BR-FranciscaNeural"
     * @param ritmo variação de velocidade em %, ex.: 0, 15, -10
     */
    public byte[] falar(String texto, String voz, int ritmo) throws Exception {
        try {
            return tentar(texto, voz, ritmo);
        } catch (Recusado r) {
            // O token depende da hora; se o relógio do celular está errado, ajusta pelo do servidor e tenta de novo.
            if (r.dataServidor > 0) desvioSegundos += r.dataServidor - agora();
            return tentar(texto, voz, ritmo);
        }
    }

    static final class Recusado extends Exception {
        final double dataServidor;
        Recusado(String m, double data) { super(m); dataServidor = data; }
    }

    private static double agora() { return System.currentTimeMillis() / 1000.0 + desvioSegundos; }

    private byte[] tentar(String texto, String voz, int ritmo) throws Exception {
        final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        final String[] erro = { null };
        final double[] dataServidor = { 0 };
        final boolean[] recusado = { false };
        final CountDownLatch fim = new CountDownLatch(1);

        String url = enderecoBase + "&ConnectionId=" + idAleatorio()
                + "&Sec-MS-GEC=" + gec(agora()) + "&Sec-MS-GEC-Version=2-" + CHROMIUM;
        Request req = new Request.Builder().url(url)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("User-Agent", AGENTE)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", "muid=" + idAleatorio().toUpperCase(Locale.ROOT) + ";")
                .build();
        final String config = "X-Timestamp:" + data() + "\r\n"
                + "Content-Type:application/json; charset=utf-8\r\n"
                + "Path:speech.config\r\n\r\n"
                + "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{"
                + "\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"},"
                + "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n";
        final String ssml = "X-RequestId:" + idAleatorio() + "\r\n"
                + "Content-Type:application/ssml+xml\r\n"
                + "X-Timestamp:" + data() + "Z\r\n"
                + "Path:ssml\r\n\r\n"
                + ssml(texto, voz, ritmo);

        WebSocket ws = cliente.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket w, Response r) { w.send(config); w.send(ssml); }

            @Override public void onMessage(WebSocket w, String t) {
                int fimCab = t.indexOf("\r\n\r\n");
                String cab = fimCab >= 0 ? t.substring(0, fimCab) : t;
                if (cab.contains("Path:turn.end")) { w.close(1000, null); fim.countDown(); }
            }

            @Override public void onMessage(WebSocket w, ByteString b) {
                byte[] d = b.toByteArray();
                if (d.length < 2) return;
                int n = ((d[0] & 0xff) << 8) | (d[1] & 0xff);
                if (n + 2 > d.length) return;
                String cab = new String(d, 2, n);
                if (!cab.contains("Path:audio") || !cab.contains("Content-Type:audio/mpeg")) return;
                synchronized (audio) { audio.write(d, 2 + n, d.length - 2 - n); }
            }

            @Override public void onFailure(WebSocket w, Throwable e, Response r) {
                if (r != null && r.code() == 403) {
                    recusado[0] = true;
                    String dt = r.header("Date");
                    if (dt != null) try {
                        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
                        dataServidor[0] = f.parse(dt).getTime() / 1000.0;
                    } catch (Exception ignorado) { }
                }
                erro[0] = (r != null ? "HTTP " + r.code() + " " : "") + e;
                fim.countDown();
            }

            @Override public void onClosed(WebSocket w, int c, String m) { fim.countDown(); }
        });
        if (!fim.await(45, TimeUnit.SECONDS)) { ws.cancel(); throw new Exception("voz neural: tempo esgotado"); }
        byte[] r;
        synchronized (audio) { r = audio.toByteArray(); }
        if (r.length > 0) return r;
        if (recusado[0]) throw new Recusado("voz neural recusada: " + erro[0], dataServidor[0]);
        throw new Exception("voz neural sem áudio" + (erro[0] != null ? ": " + erro[0] : ""));
    }

    static String ssml(String texto, String voz, int ritmo) {
        String esc = texto.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
        StringBuilder limpo = new StringBuilder();
        for (int i = 0; i < esc.length(); i++) {
            char c = esc.charAt(i);
            limpo.append((c < 32 && c != '\n') || (c >= 11 && c <= 12) || (c >= 14 && c <= 31) ? ' ' : c);
        }
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>"
                + "<voice name='" + voz + "'>"
                + "<prosody pitch='+0Hz' rate='" + (ritmo >= 0 ? "+" : "") + ritmo + "%' volume='+0%'>"
                + limpo + "</prosody></voice></speak>";
    }

    private static String data() {
        SimpleDateFormat f = new SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date((long) (agora() * 1000)));
    }

    private static final Random ALEATORIO = new Random();

    private static String idAleatorio() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 32; i++) s.append("0123456789abcdef".charAt(ALEATORIO.nextInt(16)));
        return s.toString();
    }

    // ------------------------------------------------------------ token Sec-MS-GEC (porta de edge_tts/drm.py)

    /** Token que acompanha cada pedido; muda a cada 5 minutos. */
    static String gec(double unixSegundos) {
        long s = (long) Math.floor(unixSegundos) + 11644473600L;
        s -= s % 300;
        String base = (s * 10000000L) + TOKEN;
        byte[] dados;
        try { dados = base.getBytes("US-ASCII"); } catch (Exception e) { throw new RuntimeException(e); }
        byte[] sha = sha256Especial(dados);
        int[] cs = cs64(dados, sha);
        StringBuilder r = new StringBuilder();
        for (int v : cs) for (int k = 0; k < 4; k++) r.append(String.format(Locale.ROOT, "%02X", (v >>> (8 * k)) & 0xff));
        r.append('-');
        for (byte b : sha) r.append(String.format(Locale.ROOT, "%02X", b & 0xff));
        return r.toString();
    }

    private static final int[] INI = { 0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A, 0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19 };
    private static final int[] ESTADO = { 0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A, 0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2 };
    private static final int[] AGENDA = { 0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA, 0xA622EE8C, 0xD1DD9491, 0xC0DD25A0,
            0xF9E70AB6, 0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33, 0xD258B060, 0x72766564 };
    private static final int[] K = {
        0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
        0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
        0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
        0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2 };
    private static final int AJUSTE = 0xC9DC06A7, AJ_S0 = 0x059C0B80, AJ_S1 = 0x000B92C0;
    private static final int[][] COEF = {
        { 0xE82E91B1, 0xB32700D0 }, { 0x44DE24C6, 0x88DFF51D }, { 0x3BEB65BC, 0x29514038 }, { 0xC89C5933, 0xB328CBDF },
        { 0x8D1EC211, 0xD405B4D5 }, { 0x314EF5D5, 0x26E55F3C }, { 0xE92491B1, 0x607801C6 }, { 0xF0A5B9D5, 0x82B2C1FB },
        { 0x06064CF3, 0xFA51A3DA }, { 0xDA79FA60, 0x207CC464 }, { 0xBED20560, 0x3C3C74F3 }, { 0x89F9C9F8, 0xD2D14B61 },
        { 0x35FA38C6, 0xBB04A2C1 }, { 0xCB49E3C6, 0xC5626167 } };
    private static final int FATOR = 0x82893933, AJ0 = 0xF2F90B1A, AJ1 = 0x0B1A0000;

    private static int s0(int x) { return Integer.rotateRight(x, 7) ^ Integer.rotateRight(x, 18) ^ (x >>> 3); }
    private static int s1(int x) { return Integer.rotateRight(x, 17) ^ Integer.rotateRight(x, 19) ^ (x >>> 10); }
    private static int rol16(int x) { return Integer.rotateLeft(x, 16); }

    private static void expandir(int[] w, int desde) {
        for (int i = desde; i < 64; i++) w[i] = s1(w[i - 2]) + w[i - 7] + s0(w[i - 15]) + w[i - 16];
    }

    private static int[] rodadas(int[] st, int[] w, int primeira) {
        int a = st[0], b = st[1], c = st[2], d = st[3], e = st[4], f = st[5], g = st[6], h = st[7];
        for (int i = primeira; i < 64; i++) {
            int k = (primeira == 14 && i == 14) ? AJUSTE : K[i];
            int t1 = h + (Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25))
                    + (g ^ (e & (f ^ g))) + k + w[i];
            int t2 = (Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22))
                    + ((a & b) | (c & (a | b)));
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2;
        }
        return new int[] { a, b, c, d, e, f, g, h };
    }

    private static int be(byte[] d, int o, int n) { int v = 0; for (int i = 0; i < n; i++) v = (v << 8) | (d[o + i] & 0xff); return v; }

    /** O SHA-256 do serviço (a parte inicial secreta já vem pré-calculada em ESTADO/AGENDA). */
    static byte[] sha256Especial(byte[] dados) {
        int[] w = new int[64];
        w[14] = be(dados, 0, 3);
        w[15] = be(dados, 3, 4);
        for (int i = 16; i < 30; i++) {
            int v = AGENDA[i - 16];
            if (i - 2 >= 14) v += i == 16 ? (s1(w[i - 2]) ^ AJ_S1) : s1(w[i - 2]);
            if (i - 7 >= 14) v += w[i - 7];
            if (i - 15 >= 14) v += i == 29 ? (s0(w[i - 15]) ^ AJ_S0) : s0(w[i - 15]);
            w[i] = v;
        }
        w[30] = s1(w[28]) + w[23] + s0(w[15]) + w[14] + AJUSTE - K[14];
        expandir(w, 31);
        int[] r = rodadas(ESTADO, w, 14);
        int[] st = new int[8];
        for (int i = 0; i < 8; i++) st[i] = INI[i] + r[i];

        int comprimento = 57 + dados.length;
        int resto = dados.length - 7;
        int zeros = Math.floorMod(55 - comprimento, 64);
        byte[] p = new byte[resto + 1 + zeros + 8];
        System.arraycopy(dados, 7, p, 0, resto);
        p[resto] = (byte) 0x80;
        long bits = comprimento * 8L;
        for (int i = 0; i < 8; i++) p[p.length - 1 - i] = (byte) (bits >>> (8 * i));
        for (int o = 0; o < p.length; o += 64) {
            int[] ww = new int[64];
            for (int i = 0; i < 16; i++) ww[i] = be(p, o + 4 * i, 4);
            expandir(ww, 16);
            int[] x = rodadas(st, ww, 0);
            for (int i = 0; i < 8; i++) st[i] += x[i];
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) for (int k = 0; k < 4; k++) out[4 * i + k] = (byte) (st[i] >>> (24 - 8 * k));
        return out;
    }

    static int[] cs64(byte[] dados, byte[] sha) {
        int ka = (sha[0] & 0xff) | (sha[1] & 0xff) << 8 | (sha[2] & 0xff) << 16 | (sha[3] & 0xff) << 24;
        int kb = (sha[4] & 0xff) | (sha[5] & 0xff) << 8 | (sha[6] & 0xff) << 16 | (sha[7] & 0xff) << 24;
        ka |= 1; kb |= 1;
        int fa = ka * FATOR, fb = kb * FATOR;
        // palavras little-endian de (0x00 + dados), completadas até um número par
        byte[] x = new byte[dados.length + 1];
        System.arraycopy(dados, 0, x, 1, dados.length);
        int nPal = (x.length + 3) / 4;
        if (nPal % 2 == 1) nPal++;
        int[] pal = new int[nPal];
        for (int i = 0; i < x.length; i++) pal[i / 4] |= (x[i] & 0xff) << (8 * (i % 4));
        int n = COEF.length + nPal;
        int[] termos = new int[n], desl = new int[n];
        for (int i = 0; i < COEF.length; i++) { termos[i] = COEF[i][0] * (i % 2 == 0 ? fa : fb); desl[i] = COEF[i][1]; }
        for (int i = 0; i < nPal; i++) {
            int w = pal[i];
            int t = w * (i % 2 == 0 ? ka : kb);
            int o = rol16(w) * (i % 2 == 0 ? 0xEF0569FB : 0xC31713DB);
            if (i == 0) { t += AJ0 * fa; o += AJ1; }
            termos[COEF.length + i] = t;
            desl[COEF.length + i] = o;
        }
        int trocaAc = 0, trocaUlt = 0, estado = 0, ac = 0;
        for (int i = 0; i < n; i += 2) {
            int xx = termos[i], yy = termos[i + 1];
            int t = rol16(desl[i] + xx);
            int q = t * 0x76F50F34;
            trocaAc += rol16(q) * 0xC3EFEA97 + q;
            t = rol16(desl[i + 1] + yy);
            q = t * 0x1881CE3C;
            trocaUlt = rol16(q) * 0x35BD1EC9 + q;
            trocaAc += trocaUlt;

            estado = estado * ka + xx;
            estado = rol16(estado) * 0xCF98B111;
            estado = rol16(estado) * 0x87085B9F;
            estado = rol16(estado) * 0x12CEB96D;
            estado = rol16(estado) * 0x257E1D83;
            ac += estado;
            t = estado * kb + yy;
            t = rol16(t) * 0xA27416F5;
            t = rol16(t) * 0xD38396FF;
            estado = rol16(rol16(t) * 0x7C932B89) * 0xBFA49F69;
            ac += estado;
        }
        return new int[] { trocaUlt ^ estado, trocaAc ^ ac };
    }
}
