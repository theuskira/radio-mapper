package com.colmeia.radiomapper.history;

import com.colmeia.radiomapper.model.NetworkPoint;
import com.colmeia.radiomapper.model.Project;
import com.colmeia.radiomapper.model.Radio;
import com.colmeia.radiomapper.model.RadioStatus;
import com.colmeia.radiomapper.util.Log;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Histórico de disponibilidade dos rádios.
 *
 * Guarda, por rádio: em que estado está e desde quando, a última vez que
 * esteve online, quanto durou o último período online e as transições
 * recentes. É isso que permite a notificação dizer "caiu às 03:12, estava
 * online há 4 dias" em vez de só "caiu".
 *
 * <h3>Por que fora do .rmap</h3>
 * O projeto descreve a rede (o que existe, onde está); o histórico descreve o
 * que aconteceu. Misturar os dois faria o arquivo de projeto crescer sem
 * parar e mudar a cada sincronização, atrapalhando quem versiona ou troca o
 * .rmap com um colega. Fica em {@code ~/.radio-mapper/history.json}.
 *
 * <h3>Independente das notificações</h3>
 * Registra sempre que há sincronização, mesmo com e-mail desligado — o
 * histórico é do software, não do alerta.
 */
public final class RadioHistory {

    /** Quantas transições guardamos por rádio. Suficiente para ver um padrão. */
    private static final int MAX_TRANSITIONS = 50;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * ATENÇÃO À ORDEM: esta linha precisa vir DEPOIS de {@link #MAPPER}.
     *
     * Campos estáticos são inicializados na ordem em que aparecem no arquivo,
     * e o construtor aqui chama {@link #load()}, que usa o MAPPER. Com a
     * declaração no topo da classe, o MAPPER ainda era null nesse momento e o
     * NullPointerException resultante estourava dentro do inicializador
     * estático — o que marca a classe como inutilizável para o resto da
     * execução, fazendo todo acesso seguinte falhar com NoClassDefFoundError.
     *
     * Pior: só acontecia quando o history.json já existia, porque sem arquivo
     * o load() retorna antes de tocar no MAPPER. Ou seja, a primeira execução
     * funcionava e todas as seguintes quebravam.
     */
    public static final RadioHistory INSTANCE = new RadioHistory();

    /** Uma mudança de estado. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Transition {
        public RadioStatus from = RadioStatus.UNKNOWN;
        public RadioStatus to = RadioStatus.UNKNOWN;
        public Instant at;
        /** Quanto durou o estado anterior, em segundos. */
        public long previousDurationSec;

        public Transition() {}

        Transition(RadioStatus from, RadioStatus to, Instant at, long prev) {
            this.from = from; this.to = to; this.at = at; this.previousDurationSec = prev;
        }
    }

    /** O que sabemos sobre um rádio. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Entry {
        public String radioId = "";
        /** Nome e host copiados para o histórico sobreviver à exclusão do rádio. */
        public String name = "";
        public String host = "";
        public RadioStatus status = RadioStatus.UNKNOWN;
        /** Quando o estado atual começou. */
        public Instant since;
        /** Última vez observado online. */
        public Instant lastUpAt;
        /** Última queda registrada. */
        public Instant lastDownAt;
        /** Duração do último período online já encerrado, em segundos. */
        public long lastUptimeSec;
        /** Quantas quedas desde que o histórico existe. */
        public int downCount;
        public List<Transition> transitions = new ArrayList<>();

        /** Há quanto tempo está no estado atual, em segundos. */
        public long currentDurationSec() {
            return since == null ? 0 : Duration.between(since, Instant.now()).getSeconds();
        }
    }

    private final Map<String, Entry> entries = new HashMap<>();
    private final Path file = Paths.get(System.getProperty("user.home"),
                                        ".radio-mapper", "history.json");
    private boolean dirty;

    private RadioHistory() { load(); }

    // ------------------------ Registro ------------------------

    /**
     * Observa o estado atual de todos os rádios do projeto e registra as
     * mudanças. Chamado da thread de sincronização.
     *
     * @return as quedas detectadas NESTA passada (transição para DOWN)
     */
    public synchronized List<Entry> observe(Project project) {
        List<Entry> fell = new ArrayList<>();
        if (project == null) return fell;

        Instant now = Instant.now();
        for (NetworkPoint p : project.getPoints()) {
            for (Radio r : p.getRadios()) {
                RadioStatus st = r.getStatus();
                // UNKNOWN significa "ainda não sondado", não é um estado real
                // de disponibilidade — registrá-lo poluiria o histórico com
                // uma transição a cada abertura do programa.
                if (st == RadioStatus.UNKNOWN) continue;

                boolean primeiraVez = !entries.containsKey(r.getId());
                Entry e = entries.computeIfAbsent(r.getId(), k -> {
                    Entry ne = new Entry();
                    ne.radioId = r.getId();
                    ne.since = now;
                    return ne;
                });
                e.name = nz(r.getName()).isBlank() ? nz(r.getHost()) : nz(r.getName());
                e.host = nz(r.getHost());

                if (st == RadioStatus.UP) e.lastUpAt = now;

                if (primeiraVez) {
                    // Linha de base: o rádio não mudou de estado, nós é que
                    // acabamos de conhecê-lo. Registrar isso como transição
                    // criaria um "UNKNOWN -> UP" que nunca aconteceu e contaria
                    // como queda um rádio que já estava fora antes de abrirmos.
                    e.status = st;
                    e.since = now;
                    if (st == RadioStatus.DOWN) e.lastDownAt = now;
                    dirty = true;
                    continue;
                }

                if (e.status == st) continue;   // nada mudou

                long prevDur = e.since == null ? 0 : Duration.between(e.since, now).getSeconds();
                RadioStatus from = e.status;

                if (st == RadioStatus.DOWN) {
                    e.lastDownAt = now;
                    e.downCount++;
                    // Só conta como "tempo online" se de fato vinha de UP.
                    if (from == RadioStatus.UP) e.lastUptimeSec = prevDur;
                    fell.add(e);
                }

                e.transitions.add(0, new Transition(from, st, now, prevDur));
                while (e.transitions.size() > MAX_TRANSITIONS) {
                    e.transitions.remove(e.transitions.size() - 1);
                }
                e.status = st;
                e.since = now;
                dirty = true;
            }
        }

        if (dirty) saveQuietly();
        return fell;
    }

    public synchronized Entry get(String radioId) {
        return entries.get(radioId);
    }

    /** Cópia da tabela, ordenada por rádio, para exibição. */
    public synchronized List<Entry> snapshot() {
        List<Entry> out = new ArrayList<>(entries.values());
        out.sort(Comparator.comparing(e -> e.name == null ? "" : e.name.toLowerCase()));
        return out;
    }

    /** Remove o histórico inteiro. Ação explícita do usuário. */
    public synchronized void clear() {
        entries.clear();
        dirty = true;
        saveQuietly();
        Log.warn("Historico de disponibilidade apagado");
    }

    /** Remove o histórico de um rádio só. */
    public synchronized void clear(String radioId) {
        if (entries.remove(radioId) != null) { dirty = true; saveQuietly(); }
    }

    public Path fileLocation() { return file; }

    // ------------------------ Persistência ------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Wrapper {
        public int version = 1;
        public Map<String, Entry> entries = new LinkedHashMap<>();
    }

    /**
     * Captura Exception, e não só IOException, de propósito: isto roda dentro
     * do inicializador estático, e qualquer coisa que escape daqui deixa a
     * classe permanentemente inutilizável. Um histórico corrompido deve
     * custar o histórico, não o programa.
     */
    private synchronized void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            Wrapper w = MAPPER.readValue(file.toFile(), Wrapper.class);
            if (w != null && w.entries != null) {
                entries.putAll(w.entries);
                Log.info("Historico carregado: %d radio(s)", entries.size());
            }
        } catch (Exception ex) {
            Log.warn("Nao consegui ler o historico (%s): %s: %s",
                    file, ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    /**
     * Grava só quando houve transição — sincronização de 1 s escrevendo em
     * disco a cada ciclo seria desperdício puro.
     *
     * Escreve em arquivo temporário e move por cima: uma queda de energia no
     * meio da gravação não deixa o histórico truncado.
     */
    private void saveQuietly() {
        try {
            Files.createDirectories(file.getParent());
            Wrapper w = new Wrapper();
            w.entries.putAll(entries);
            File tmp = new File(file.toFile().getAbsolutePath() + ".tmp");
            MAPPER.writeValue(tmp, w);
            Files.move(tmp.toPath(), file, StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException ex) {
            Log.warn("Nao consegui gravar o historico: %s", ex.getMessage());
        }
    }

    /** Força a gravação (usado no fechamento do programa). */
    public synchronized void flush() {
        if (dirty) saveQuietly();
    }

    // ------------------------ Formatação ------------------------

    /** Data/hora local legível, ou um texto alternativo quando não há registro. */
    public static String stamp(Instant i, String fallback) {
        if (i == null) return fallback;
        return LocalDateTime.ofInstant(i, ZoneId.systemDefault()).format(STAMP);
    }

    /**
     * Duração em linguagem de operador: "4 d 3 h", "2 h 15 min", "45 s".
     * Mostra no máximo duas unidades — precisão de segundo em cima de dias
     * não ajuda ninguém às 3 da manhã.
     */
    public static String humanDuration(long seconds) {
        if (seconds < 0) seconds = 0;
        if (seconds < 60) return seconds + " s";

        long d = seconds / 86400;
        long h = (seconds % 86400) / 3600;
        long m = (seconds % 3600) / 60;

        if (d > 0) return h > 0 ? d + " d " + h + " h" : d + " d";
        if (h > 0) return m > 0 ? h + " h " + m + " min" : h + " h";
        return m + " min";
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
