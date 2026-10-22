package app.tocador;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whisper (OpenAI, licença MIT) rodando no celular pelo ONNX Runtime.
 * Recebe até 30 s de áudio (16 kHz, mono) e devolve as falas com horário.
 * Java puro + ONNX Runtime: o mesmo código é testado no computador.
 *
 * Modelos no formato do transformers.js (Xenova / onnx-community): encoder_model + decoder_model_merged
 * (com cache de atenção) ou decoder_model (sem cache).
 */
public final class Whisper implements AutoCloseable {

    /** Um trecho falado: horário (ms, relativo ao início do áudio) e texto. */
    public static final class Segmento {
        public final long ini, fim;
        public final String texto;
        public Segmento(long ini, long fim, String texto) { this.ini = ini; this.fim = fim; this.texto = texto; }
        @Override public String toString() { return ini + "-" + fim + ":" + texto; }
    }

    /** Resultado de uma janela: as falas, o idioma e quanto do áudio foi de fato usado (o resto vai para a próxima janela). */
    public static final class Resultado {
        public final List<Segmento> segmentos = new ArrayList<Segmento>();
        public String idioma = "";
        public long consumidoMs;
        /** Chance de não haver fala nenhuma (0 a 1) e média do log da probabilidade dos tokens escolhidos. */
        public double semFala, logprobMedia;
        /** Confiança do Whisper no idioma detectado (0 a 1); 1 quando o idioma foi informado. */
        public double idiomaProb;
        /** Regra do próprio Whisper para descartar janelas sem fala (evita "Obrigado." inventado no silêncio). */
        public boolean silencio() { return semFala > 0.6 && logprobMedia < -1.0; }
    }

    public static final int TAXA = 16000, AMOSTRAS = 480000, N_FFT = 400, SALTO = 160, QUADROS = 3000;

    static final String[] IDIOMAS = ("en,zh,de,es,ru,ko,fr,ja,pt,tr,pl,ca,nl,ar,sv,it,id,hi,fi,vi,he,uk,el,ms,cs,ro,da,hu,ta,no,th,ur,hr,bg,lt,la,mi,ml,cy,sk,te,fa,lv,bn,sr,az,sl,kn,et,mk,br,eu,is,hy,ne,mn,bs,kk,sq,sw,gl,mr,pa,si,km,sn,yo,so,af,oc,ka,be,tg,sd,gu,am,yi,lo,uz,fo,ht,ps,tk,nn,mt,sa,lb,my,bo,tl,mg,as,tt,haw,ln,ha,ba,jw,su,yue").split(",");

    // Tokens especiais (vocabulário multilíngue de 51865; o large-v3 tem um idioma a mais e desloca tudo em 1).
    private final int eot = 50257, sot = 50258, idioma0 = 50259;
    private final int numIdiomas, traduzir, transcrever, semFala, semHorarios, horario0;

    private final OrtEnvironment env;
    private final OrtSession codificador, decodificador;
    private final int nMels;          // 80 (tiny a large-v2) ou 128 (large-v3 e turbo)
    private final float[][] filtros;  // nMels x 201
    private final byte[][] tokens;    // bytes de cada token de texto (tiktoken)
    private final float[] janela, cosT, senT;

    /**
     * @param mel    conteúdo de assets/whisper/mel80.bin (80x201 float32) ou mel128.bin (128x201), conforme o modelo
     * @param vocab  assets/whisper/multilingual.tiktoken
     * @param tamanhoVocab 51865 (tiny/base/small/medium/large-v2) ou 51866 (large-v3/turbo)
     */
    public Whisper(String encoderOnnx, String decoderOnnx, InputStream mel, InputStream vocab, int tamanhoVocab, int threads) throws Exception {
        numIdiomas = tamanhoVocab >= 51866 ? 100 : 99;
        traduzir = idioma0 + numIdiomas;
        transcrever = traduzir + 1;
        semFala = traduzir + 4;
        semHorarios = traduzir + 5;
        horario0 = traduzir + 6;

        byte[] b = lerTudo(mel);
        nMels = b.length / (201 * 4);
        if ((nMels != 80 && nMels != 128) || b.length != nMels * 201 * 4) throw new IllegalArgumentException("banco de filtros mel inesperado: " + b.length + " bytes");
        filtros = new float[nMels][201];
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < nMels; i++) for (int j = 0; j < 201; j++) filtros[i][j] = bb.getFloat();

        ArrayList<byte[]> l = new ArrayList<byte[]>();
        BufferedReader r = new BufferedReader(new InputStreamReader(vocab, "UTF-8"));
        String linha;
        while ((linha = r.readLine()) != null) {
            int sp = linha.indexOf(' ');
            if (sp < 0) continue;
            int id = Integer.parseInt(linha.substring(sp + 1).trim());
            while (l.size() <= id) l.add(new byte[0]);
            l.set(id, decodificarBase64(linha.substring(0, sp)));
        }
        tokens = l.toArray(new byte[0][]);

        // Janela de Hann "periódica" (como torch.hann_window) e tabelas da transformada de Fourier.
        janela = new float[N_FFT];
        for (int i = 0; i < N_FFT; i++) janela[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / N_FFT));
        cosT = new float[201 * N_FFT];
        senT = new float[201 * N_FFT];
        for (int k = 0; k < 201; k++) for (int n = 0; n < N_FFT; n++) {
            double a = 2 * Math.PI * k * n / N_FFT;
            cosT[k * N_FFT + n] = (float) Math.cos(a);
            senT[k * N_FFT + n] = (float) Math.sin(a);
        }

        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions op = new OrtSession.SessionOptions();
        op.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        op.setSessionLogLevel(ai.onnxruntime.OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR);
        op.setIntraOpNumThreads(Math.max(1, threads));
        codificador = env.createSession(encoderOnnx, op);
        decodificador = env.createSession(decoderOnnx, op);
    }

    // ------------------------------------------------------------ espectrograma (igual ao whisper/audio.py)

    /** log-Mel de 80 ou 128 faixas x 3000 quadros a partir de até 30 s de áudio 16 kHz (completa com silêncio). */
    public float[] logMel(float[] audio) {
        float[] x = new float[AMOSTRAS];
        System.arraycopy(audio, 0, x, 0, Math.min(audio.length, AMOSTRAS));
        int pad = N_FFT / 2;
        float[] p = new float[AMOSTRAS + 2 * pad];
        for (int i = 0; i < AMOSTRAS; i++) p[i + pad] = x[i];
        for (int i = 0; i < pad; i++) { p[pad - 1 - i] = x[i + 1]; p[pad + AMOSTRAS + i] = x[AMOSTRAS - 2 - i]; } // reflexão
        float[] mel = new float[nMels * QUADROS];
        float[] potencia = new float[201];
        float[] q = new float[N_FFT];
        float max = -Float.MAX_VALUE;
        for (int f = 0; f < QUADROS; f++) {
            int o = f * SALTO;
            for (int n = 0; n < N_FFT; n++) q[n] = p[o + n] * janela[n];
            for (int k = 0; k < 201; k++) {
                float re = 0, im = 0;
                int base = k * N_FFT;
                for (int n = 0; n < N_FFT; n++) { re += q[n] * cosT[base + n]; im -= q[n] * senT[base + n]; }
                potencia[k] = re * re + im * im;
            }
            for (int m = 0; m < nMels; m++) {
                float s = 0;
                float[] fl = filtros[m];
                for (int k = 0; k < 201; k++) s += fl[k] * potencia[k];
                float v = (float) Math.log10(Math.max(s, 1e-10f));
                mel[m * QUADROS + f] = v;
                if (v > max) max = v;
            }
        }
        float piso = max - 8f;
        for (int i = 0; i < mel.length; i++) mel[i] = (Math.max(mel[i], piso) + 4f) / 4f;
        return mel;
    }

    // ------------------------------------------------------------ transcrição

    /**
     * Transcreve até 30 s de áudio. idioma: código ("en", "pt"…) ou null para detectar.
     */
    public synchronized Resultado transcrever(float[] audio, String idioma) throws Exception {
        if (fechado) throw new IllegalStateException("Whisper fechado");
        Resultado res = new Resultado();
        float[] mel = logMel(audio);
        OnnxTensor entrada = OnnxTensor.createTensor(env, FloatBuffer.wrap(mel), new long[] { 1, nMels, QUADROS });
        OnnxTensor hidden;
        try {
            String saida = codificador.getOutputNames().iterator().next(); // last_hidden_state (as atenções não interessam)
            OrtSession.Result r = codificador.run(Collections.singletonMap(nomeEntrada(codificador, "input_features"), entrada),
                    Collections.singleton(saida));
            try {
                OnnxTensor h = (OnnxTensor) r.get(0);
                FloatBuffer fb = h.getFloatBuffer();
                float[] v = new float[fb.remaining()];
                fb.get(v);
                hidden = OnnxTensor.createTensor(env, FloatBuffer.wrap(v), h.getInfo().getShape());
            } finally { r.close(); }
        } finally { entrada.close(); }

        Passos dec = new Passos(hidden);
        try {
            int idIdioma;
            float[] lg = dec.avancar(new long[] { sot });
            res.semFala = Math.exp(logSoftmax(lg, semFala));
            if (idioma != null && indiceIdioma(idioma) >= 0) { idIdioma = idioma0 + indiceIdioma(idioma); res.idiomaProb = 1; }
            else {
                int melhor = idioma0;
                for (int t = idioma0; t < idioma0 + numIdiomas; t++) if (lg[t] > lg[melhor]) melhor = t;
                idIdioma = melhor;
                res.idiomaProb = probIdioma(lg, idioma0, numIdiomas, melhor);
            }
            res.idioma = IDIOMAS[idIdioma - idioma0];

            ArrayList<Long> seq = new ArrayList<Long>();
            seq.add((long) sot); seq.add((long) idIdioma); seq.add((long) transcrever);
            int prefixo = seq.size();
            int duracaoQuadros = (int) Math.min(1500, Math.ceil(audio.length / 320.0)); // posições de horário (0,02 s) com áudio de verdade
            lg = dec.avancar(new long[] { idIdioma, transcrever });
            double somaLp = 0;
            int nTok = 0;
            for (int passo = 0; passo < 224; passo++) {
                aplicarRegras(lg, seq, prefixo, duracaoQuadros);
                int t = argmax(lg);
                somaLp += logSoftmax(lg, t);
                nTok++;
                seq.add((long) t);
                if (t == eot || repetindo(seq, prefixo)) break;
                lg = dec.avancar(new long[] { t });
            }
            res.logprobMedia = nTok == 0 ? 0 : somaLp / nTok;
            montarSegmentos(seq, prefixo, res, audio.length);
        } finally { dec.close(); hidden.close(); }
        return res;
    }

    /** O Whisper às vezes entra em laço ("obrigado obrigado obrigado…"): corta quando os últimos tokens se repetem demais. */
    private boolean repetindo(List<Long> seq, int prefixo) {
        int n = seq.size() - prefixo;
        for (int p = 1; p <= 8; p++) {
            int vezes = p == 1 ? 12 : 5;
            if (n < p * vezes) continue;
            boolean igual = true;
            for (int i = 0; i < p * (vezes - 1) && igual; i++)
                if (!seq.get(seq.size() - 1 - i).equals(seq.get(seq.size() - 1 - i - p))) igual = false;
            if (igual) return true;
        }
        return false;
    }

    /**
     * Executa o decodificador passo a passo.
     * Modelo "merged" (com past_key_values): guarda o cache e só manda os tokens novos (rápido).
     * Modelo simples (decoder_model): manda a sequência inteira a cada passo.
     */
    private final class Passos implements AutoCloseable {
        private final OnnxTensor hidden;
        private final ArrayList<Long> todos = new ArrayList<Long>();
        private OrtSession.Result anterior;   // "present.*.decoder" do passo anterior
        private OrtSession.Result primeiro;   // "present.*.encoder": só o primeiro passo calcula; depois o modelo devolve lixo ali
        private final boolean comCache;
        private final List<String> passados = new ArrayList<String>();
        private final Set<String> saidas = new HashSet<String>();

        Passos(OnnxTensor hidden) throws Exception {
            this.hidden = hidden;
            comCache = decodificador.getInputNames().contains("use_cache_branch");
            if (comCache) {
                for (String n : decodificador.getInputNames()) if (n.startsWith("past_key_values.")) passados.add(n);
                saidas.add("logits");
                for (String n : passados) saidas.add("present." + n.substring("past_key_values.".length()));
            } else saidas.add(decodificador.getOutputNames().iterator().next());
        }

        float[] avancar(long[] novos) throws Exception {
            for (long t : novos) todos.add(t);
            long[] ids;
            if (comCache && anterior != null) ids = novos;
            else { ids = new long[todos.size()]; for (int i = 0; i < ids.length; i++) ids[i] = todos.get(i); }
            ArrayList<OnnxTensor> temporarios = new ArrayList<OnnxTensor>();
            try {
                OnnxTensor tIds = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), new long[] { 1, ids.length });
                temporarios.add(tIds);
                Map<String, OnnxTensor> in = new HashMap<String, OnnxTensor>();
                in.put(nomeEntrada(decodificador, "input_ids"), tIds);
                in.put(nomeEntrada(decodificador, "encoder_hidden_states"), hidden);
                if (comCache) {
                    boolean usar = anterior != null;
                    OnnxTensor flag = OnnxTensor.createTensor(env, new boolean[] { usar });
                    temporarios.add(flag);
                    in.put("use_cache_branch", flag);
                    for (String n : passados) {
                        if (usar) {
                            String p = "present." + n.substring("past_key_values.".length());
                            OrtSession.Result de = n.contains(".encoder.") ? primeiro : anterior;
                            in.put(n, (OnnxTensor) de.get(p).get());
                        } else {
                            long[] f = ((ai.onnxruntime.TensorInfo) decodificador.getInputInfo().get(n).getInfo()).getShape();
                            OnnxTensor vazio = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), new long[] { 1, f[1], 0, f[3] });
                            temporarios.add(vazio);
                            in.put(n, vazio);
                        }
                    }
                }
                OrtSession.Result r = decodificador.run(in, saidas);
                OnnxTensor lg = (OnnxTensor) (comCache ? r.get("logits").get() : r.get(0));
                long[] f = lg.getInfo().getShape(); // [1, seq, vocab]
                int vocab = (int) f[2];
                FloatBuffer fb = lg.getFloatBuffer();
                float[] out = new float[vocab];
                fb.position((ids.length - 1) * vocab);
                fb.get(out);
                if (comCache) {
                    if (primeiro == null) primeiro = r;
                    else if (anterior != primeiro) anterior.close();
                    anterior = r;
                }
                else r.close();
                return out;
            } finally { for (OnnxTensor t : temporarios) t.close(); }
        }

        @Override public void close() {
            if (anterior != null && anterior != primeiro) anterior.close();
            if (primeiro != null) primeiro.close();
            anterior = primeiro = null;
        }
    }

    /** Regras de horário do Whisper (versão simplificada de whisper/decoding.py: ApplyTimestampRules). */
    private void aplicarRegras(float[] lg, List<Long> seq, int prefixo, int duracaoQuadros) {
        lg[semHorarios] = Float.NEGATIVE_INFINITY;
        for (int t = sot; t < horario0; t++) if (t != eot) lg[t] = Float.NEGATIVE_INFINITY; // sem tokens especiais no texto
        int n = seq.size() - prefixo;
        boolean ultimoHorario = n >= 1 && seq.get(seq.size() - 1) >= horario0;
        boolean penultimoHorario = n < 2 || seq.get(seq.size() - 2) >= horario0;
        if (ultimoHorario) {
            if (penultimoHorario) for (int t = horario0; t < lg.length; t++) lg[t] = Float.NEGATIVE_INFINITY; // depois do par, vem texto
            else for (int t = 0; t < eot; t++) lg[t] = Float.NEGATIVE_INFINITY;                               // fecha o trecho com horário
        }
        // Horários nunca voltam.
        long ultimoTs = -1;
        for (int i = prefixo; i < seq.size(); i++) if (seq.get(i) >= horario0) ultimoTs = seq.get(i);
        if (ultimoTs >= 0) {
            long minimo = ultimoHorario && !penultimoHorario ? ultimoTs : ultimoTs + 1;
            for (long t = horario0; t < minimo && t < lg.length; t++) lg[(int) t] = Float.NEGATIVE_INFINITY;
        }
        if (n == 0) {
            // O primeiro token é um horário de até 1 s; nada de texto antes.
            for (int t = 0; t < horario0; t++) lg[t] = Float.NEGATIVE_INFINITY;
            for (int t = horario0 + 51; t < lg.length; t++) lg[t] = Float.NEGATIVE_INFINITY;
        }
        // Não marcar horário depois do fim do áudio real.
        for (int t = horario0 + duracaoQuadros + 1; t < lg.length; t++) lg[t] = Float.NEGATIVE_INFINITY;
        // Se a soma das probabilidades de horário vence o melhor texto, escolhe horário.
        double maxTs = Double.NEGATIVE_INFINITY, maxTx = Double.NEGATIVE_INFINITY;
        for (int t = horario0; t < lg.length; t++) maxTs = Math.max(maxTs, lg[t]);
        for (int t = 0; t < horario0; t++) maxTx = Math.max(maxTx, lg[t]);
        if (maxTs > Double.NEGATIVE_INFINITY && maxTx > Double.NEGATIVE_INFINITY) {
            double soma = 0;
            for (int t = horario0; t < lg.length; t++) if (lg[t] > Float.NEGATIVE_INFINITY) soma += Math.exp(lg[t] - maxTs);
            double lseTs = maxTs + Math.log(soma);
            // compara em log-probabilidade (o mesmo denominador nos dois lados)
            if (lseTs > maxTx) for (int t = 0; t < horario0; t++) lg[t] = Float.NEGATIVE_INFINITY;
        }
    }

    private void montarSegmentos(List<Long> seq, int prefixo, Resultado res, int amostras) {
        long ini = -1, ultimoFim = 0;
        ByteArrayOutputStream texto = new ByteArrayOutputStream();
        for (int i = prefixo; i < seq.size(); i++) {
            long t = seq.get(i);
            if (t == eot) break;
            if (t >= horario0) {
                long ms = (t - horario0) * 20;
                if (ini < 0) { ini = ms; texto.reset(); }
                else {
                    String s = utf8(texto.toByteArray()).trim();
                    if (s.length() > 0) res.segmentos.add(new Segmento(ini, ms, s));
                    ultimoFim = ms;
                    ini = -1;
                }
            } else if (t < tokens.length) {
                byte[] bt = tokens[(int) t];
                texto.write(bt, 0, bt.length);
            }
        }
        long total = amostras * 1000L / TAXA;
        // Se o último trecho ficou aberto (a fala continua na próxima janela), a próxima janela recomeça do último horário fechado.
        res.consumidoMs = ini >= 0 && ultimoFim > 0 ? ultimoFim : total;
        if (ini >= 0 && ultimoFim == 0) {
            String s = utf8(texto.toByteArray()).trim();
            if (s.length() > 0) res.segmentos.add(new Segmento(ini, total, s));
        }
    }

    /** Usa o nome esperado se existir; senão, a entrada da mesma posição (modelos exportados com outros nomes). */
    private static String nomeEntrada(OrtSession s, String esperado) throws Exception {
        if (s.getInputNames().contains(esperado)) return esperado;
        List<String> nomes = new ArrayList<String>(s.getInputNames());
        if ("encoder_hidden_states".equals(esperado) && nomes.size() > 1) return nomes.get(1);
        return nomes.get(0);
    }

    private static double logSoftmax(float[] v, int i) {
        double m = Double.NEGATIVE_INFINITY;
        for (float x : v) if (x > m) m = x;
        double s = 0;
        for (float x : v) if (x > Float.NEGATIVE_INFINITY) s += Math.exp(x - m);
        return v[i] - m - Math.log(s);
    }

    /** Probabilidade do idioma escolhido entre os idiomas (como o detect_language do Whisper: softmax só sobre os tokens de idioma). */
    static double probIdioma(float[] lg, int ini, int n, int escolhido) {
        double m = Double.NEGATIVE_INFINITY;
        for (int t = ini; t < ini + n; t++) m = Math.max(m, lg[t]);
        double soma = 0;
        for (int t = ini; t < ini + n; t++) soma += Math.exp(lg[t] - m);
        return Math.exp(lg[escolhido] - m) / soma;
    }

    private static byte[] lerTudo(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int k;
        while ((k = in.read(buf)) > 0) o.write(buf, 0, k);
        in.close();
        return o.toByteArray();
    }

    private static int argmax(float[] v) { int m = 0; for (int i = 1; i < v.length; i++) if (v[i] > v[m]) m = i; return m; }

    static int indiceIdioma(String c) { for (int i = 0; i < IDIOMAS.length; i++) if (IDIOMAS[i].equals(c)) return i; return -1; }

    private static String utf8(byte[] b) { try { return new String(b, "UTF-8"); } catch (Exception e) { return ""; } }

    private static byte[] decodificarBase64(String s) {
        String alfa = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=') break;
            int v = alfa.indexOf(c);
            if (v < 0) continue;
            buf = (buf << 6) | v; bits += 6;
            if (bits >= 8) { bits -= 8; o.write((buf >> bits) & 0xff); }
        }
        return o.toByteArray();
    }

    /** Texto de uma lista de tokens (para testes). */
    String decodificar(int[] ids) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int t : ids) if (t < tokens.length) b.write(tokens[t], 0, tokens[t].length);
        return utf8(b.toByteArray());
    }

    private boolean fechado;

    /** Espera a transcrição em andamento terminar antes de liberar os modelos. */
    @Override
    public synchronized void close() {
        if (fechado) return;
        fechado = true;
        try { codificador.close(); } catch (Exception e) { }
        try { decodificador.close(); } catch (Exception e) { }
    }
}
