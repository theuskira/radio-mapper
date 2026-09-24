package com.colmeia.radiomapper.notify;

import com.colmeia.radiomapper.history.RadioHistory;
import com.colmeia.radiomapper.model.NetworkPoint;
import com.colmeia.radiomapper.model.Project;
import com.colmeia.radiomapper.model.Radio;
import com.colmeia.radiomapper.model.RadioStatus;
import com.colmeia.radiomapper.util.Log;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Decide QUANDO mandar e-mail de queda, e manda.
 *
 * O problema real não é enviar — é não virar spam. Três defesas:
 *
 * <ol>
 *   <li><b>Confirmação</b> ({@code failuresBeforeAlert}): só alerta depois de
 *       N leituras DOWN seguidas. Um timeout de SSH isolado não acorda
 *       ninguém às 3h.</li>
 *   <li><b>Agregação</b> ({@code aggregationSeconds}): quando o backbone cai,
 *       30 rádios somem juntos. Em vez de 30 e-mails, junta tudo numa janela
 *       curta e manda UM com a lista. Esta é a defesa que mais importa.</li>
 *   <li><b>Não repetir</b>: um rádio que já gerou alerta não gera outro
 *       enquanto não voltar. Se ficar oscilando, {@code reAlertMinutes}
 *       impõe um intervalo mínimo entre alertas do mesmo rádio.</li>
 * </ol>
 *
 * <h3>Limite honesto</h3>
 * Isso roda dentro do app. Só há vigilância enquanto o Radio Mapper estiver
 * aberto e sincronizando — fechou o programa, acabou a notificação. Para
 * monitoramento 24/7 de verdade o caminho é um serviço separado, fora daqui.
 */
public final class NotificationService {

    public static final NotificationService INSTANCE = new NotificationService();

    private static final DateTimeFormatter D_DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter D_HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Um rádio que mudou de estado e ainda não foi comunicado.
     *
     * Os campos de tempo já vêm formatados: quem monta o e-mail não deveria
     * precisar saber de fuso horário nem de como se escreve "4 d 3 h".
     */
    private record Event(String name, String host, String point, String vendor,
                         String mac, String error, LocalDateTime at,
                         String fellAt, String lastUpAt, String uptime) {}

    private static final class RadioState {
        int consecutiveDown;
        boolean alerted;
        Instant lastAlertAt;
    }

    /**
     * Como a mensagem sai daqui. Em produção é o {@link MailSender}; trocar
     * por uma API HTTP depois é só implementar isto. Também é o ponto por onde
     * a lógica de anti-spam pode ser verificada sem mandar e-mail de verdade.
     */
    @FunctionalInterface
    public interface Transport {
        void send(MailConfig cfg, String subject, String body) throws Exception;
    }

    private volatile Transport transport = MailSender::send;

    public void setTransport(Transport t) {
        this.transport = t == null ? MailSender::send : t;
    }

    private MailConfig cfg = MailConfig.load();

    private final Map<String, RadioState> states = new HashMap<>();
    private final List<Event> pendingDown = new ArrayList<>();
    private final List<Event> pendingUp = new ArrayList<>();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mail-notifier");
                t.setDaemon(true);
                return t;
            });

    private ScheduledFuture<?> flushTask;
    private String projectName = "";

    private NotificationService() {}

    public synchronized MailConfig config() { return cfg; }

    /** Recarrega a configuração depois que o usuário salvou no diálogo. */
    public synchronized void reloadConfig() {
        cfg = MailConfig.load();
        Log.info("Notificacoes por e-mail: %s", cfg.isEnabled() ? "ligadas" : "desligadas");
    }

    /** Zera o histórico de estado — usar ao trocar de projeto. */
    public synchronized void reset() {
        states.clear();
        pendingDown.clear();
        pendingUp.clear();
        if (flushTask != null) { flushTask.cancel(false); flushTask = null; }
    }

    /**
     * Avalia o resultado de uma rodada de sondagem. Chamado da thread de
     * sincronização, nunca da thread do JavaFX.
     */
    public synchronized void onSyncCompleted(Project project) {
        if (!cfg.isEnabled() || project == null) return;
        projectName = project.getName() == null ? "" : project.getName();

        LocalDateTime now = LocalDateTime.now();
        Instant nowI = Instant.now();

        for (NetworkPoint p : project.getPoints()) {
            for (Radio r : p.getRadios()) {
                if (!r.isMonitored()) {
                    // Deixou de ser monitorado: esquece o histórico dele.
                    states.remove(r.getId());
                    continue;
                }
                RadioState st = states.computeIfAbsent(r.getId(), k -> new RadioState());
                RadioStatus status = r.getStatus();

                if (status == RadioStatus.DOWN) {
                    st.consecutiveDown++;
                    if (!st.alerted && st.consecutiveDown >= cfg.getFailuresBeforeAlert()
                            && cooledDown(st, nowI)) {
                        st.alerted = true;
                        st.lastAlertAt = nowI;
                        pendingDown.add(toEvent(r, p, now));
                    }
                } else if (status == RadioStatus.UP) {
                    if (st.alerted && cfg.isNotifyRecovery()) {
                        pendingUp.add(toEvent(r, p, now));
                    }
                    st.consecutiveDown = 0;
                    st.alerted = false;
                }
                // UNKNOWN: rádio ainda não sondado nesta rodada. Não conta como
                // queda nem como volta — mexer aqui geraria alerta no boot.
            }
        }

        if (!pendingDown.isEmpty() || !pendingUp.isEmpty()) scheduleFlush();
    }

    private boolean cooledDown(RadioState st, Instant now) {
        if (st.lastAlertAt == null) return true;
        long min = cfg.getReAlertMinutes();
        if (min <= 0) return true;
        return Duration.between(st.lastAlertAt, now).toMinutes() >= min;
    }

    private Event toEvent(Radio r, NetworkPoint p, LocalDateTime at) {
        String nome = r.getName() == null || r.getName().isBlank() ? r.getHost() : r.getName();

        // O histórico é a fonte de "quando caiu" e "quanto ficou no ar". Se o
        // rádio ainda não tem registro (primeira execução), sai texto honesto
        // em vez de zero, que pareceria dado real.
        RadioHistory.Entry h = RadioHistory.INSTANCE.get(r.getId());
        String fellAt, lastUp, uptime;
        if (h == null) {
            fellAt = RadioHistory.stamp(java.time.Instant.now(), "-");
            lastUp = "sem registro anterior";
            uptime = "sem registro anterior";
        } else {
            fellAt = RadioHistory.stamp(h.lastDownAt, "-");
            lastUp = RadioHistory.stamp(h.lastUpAt, "nunca visto online");
            uptime = h.lastUptimeSec > 0
                    ? RadioHistory.humanDuration(h.lastUptimeSec)
                    : "sem registro anterior";
        }

        return new Event(nz(nome), nz(r.getHost()), nz(p.getName()),
                String.valueOf(r.getVendor()), nz(r.getMac()), nz(r.getLastError()), at,
                fellAt, lastUp, uptime);
    }

    /** Abre (ou mantém) a janela de agregação. */
    private void scheduleFlush() {
        if (flushTask != null && !flushTask.isDone()) return;   // janela já aberta
        int wait = cfg.getAggregationSeconds();
        if (wait <= 0) {
            scheduler.execute(this::flush);
        } else {
            flushTask = scheduler.schedule(this::flush, wait, TimeUnit.SECONDS);
        }
    }

    private void flush() {
        List<Event> downs;
        List<Event> ups;
        MailConfig c;
        String proj;
        synchronized (this) {
            if (pendingDown.isEmpty() && pendingUp.isEmpty()) return;
            downs = new ArrayList<>(pendingDown);
            ups = new ArrayList<>(pendingUp);
            pendingDown.clear();
            pendingUp.clear();
            flushTask = null;
            c = cfg;
            proj = projectName;
        }

        if (!downs.isEmpty()) {
            dispatch(c, proj, downs, c.getSubjectTemplate(), c.getBodyTemplate(), "queda");
        }
        if (!ups.isEmpty()) {
            dispatch(c, proj, ups, c.getRecoverySubjectTemplate(), c.getBodyTemplate(), "retorno");
        }
    }

    private void dispatch(MailConfig c, String proj, List<Event> events,
                          String subjectTpl, String bodyTpl, String kind) {
        String subject = render(subjectTpl, c, proj, events);
        String body = render(bodyTpl, c, proj, events);
        try {
            transport.send(c, subject, body);
            Log.info("Notificacao de %s enviada (%d radio(s))", kind, events.size());
        } catch (Exception ex) {
            // Falhar aqui não pode derrubar a sincronização; o log é o registro.
            Log.error("Falha ao enviar notificacao de %s: %s", kind, ex.getMessage());
        }
    }

    // ------------------------ Templates ------------------------

    /** Expande os placeholders de um template de assunto/corpo. */
    static String render(String template, MailConfig c, String projectName, List<Event> events) {
        StringBuilder lista = new StringBuilder();
        for (Event e : events) {
            if (lista.length() > 0) lista.append('\n');
            lista.append(renderLine(c.getLineTemplate(), e));
        }
        LocalDateTime when = events.isEmpty() ? LocalDateTime.now() : events.get(0).at();

        String out = template == null ? "" : template;
        out = out.replace("{qtd}", String.valueOf(events.size()));
        out = out.replace("{lista}", lista.toString());
        out = out.replace("{projeto}", nz(projectName));
        out = out.replace("{data}", when.format(D_DATA));
        out = out.replace("{hora}", when.format(D_HORA));
        // Num template de assunto com um rádio só, faz sentido citá-lo direto.
        if (!events.isEmpty()) out = renderLine(out, events.get(0));
        return out;
    }

    private static String renderLine(String template, Event e) {
        String out = template == null ? "" : template;
        out = out.replace("{nome}", e.name());
        out = out.replace("{host}", e.host());
        out = out.replace("{ponto}", e.point());
        out = out.replace("{vendor}", e.vendor());
        out = out.replace("{mac}", e.mac());
        out = out.replace("{erro}", e.error());
        out = out.replace("{caiu}", e.fellAt());
        out = out.replace("{ultimoOnline}", e.lastUpAt());
        out = out.replace("{tempoOnline}", e.uptime());
        out = out.replace("{hora}", e.at().format(D_HORA));
        out = out.replace("{data}", e.at().format(D_DATA));
        return out;
    }

    /**
     * Monta uma prévia com dados fictícios, para o usuário ver o resultado do
     * template sem esperar um rádio cair.
     */
    public static String preview(MailConfig c, String projectName, boolean recovery) {
        String agora = RadioHistory.stamp(java.time.Instant.now(), "-");
        List<Event> fake = List.of(
                new Event("Torre Centro", "10.0.0.11", "POP Centro", "MIKROTIK_V6",
                          "aa:bb:cc:dd:ee:01", "timeout", LocalDateTime.now(),
                          agora, agora, RadioHistory.humanDuration(372_600)),
                new Event("Cliente 204", "10.0.3.204", "Bairro Sul", "UBIQUITI_AIROS8",
                          "aa:bb:cc:dd:ee:02", "conexao recusada", LocalDateTime.now(),
                          agora, agora, RadioHistory.humanDuration(8_130)));
        String subj = render(recovery ? c.getRecoverySubjectTemplate() : c.getSubjectTemplate(),
                             c, projectName, fake);
        String body = render(c.getBodyTemplate(), c, projectName, fake);
        return "Assunto: " + subj + "\n\n" + body;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    public void shutdown() { scheduler.shutdownNow(); }
}
