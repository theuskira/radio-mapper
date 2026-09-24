package com.colmeia.radiomapper.notify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Configuração de envio de e-mail, guardada por usuário do SO.
 *
 * <h3>Sobre a senha</h3>
 * Fica em {@link Preferences}, que no Windows é o registro do usuário — texto
 * claro, legível por qualquer processo rodando com a sua conta. Isso NÃO é
 * cofre de senha. A recomendação é usar uma senha de aplicativo dedicada
 * (Gmail/Microsoft 365 oferecem) em vez da senha principal da conta, para que
 * um vazamento não custe a caixa de e-mail inteira.
 *
 * Fica nas preferências e não no .rmap de propósito: projeto é arquivo que se
 * troca com colega, credencial não.
 */
public final class MailConfig {

    public enum Security {
        NONE("Sem criptografia (porta 25)"),
        STARTTLS("STARTTLS (porta 587)"),
        SSL("SSL/TLS direto (porta 465)");

        private final String label;
        Security(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private static final Preferences P = Preferences.userNodeForPackage(MailConfig.class);

    private static final String K_ENABLED = "mail.enabled";
    private static final String K_HOST = "mail.host";
    private static final String K_PORT = "mail.port";
    private static final String K_SECURITY = "mail.security";
    private static final String K_USER = "mail.user";
    private static final String K_PASSWORD = "mail.password";
    private static final String K_FROM = "mail.from";
    private static final String K_FROM_NAME = "mail.fromName";
    private static final String K_RECIPIENTS = "mail.recipients";
    private static final String K_SUBJECT = "mail.subject";
    private static final String K_BODY = "mail.body";
    private static final String K_LINE = "mail.line";
    private static final String K_NOTIFY_RECOVERY = "mail.notifyRecovery";
    private static final String K_RECOVERY_SUBJECT = "mail.recoverySubject";
    private static final String K_FAILURES = "mail.failuresBeforeAlert";
    private static final String K_AGGREGATION = "mail.aggregationSeconds";
    private static final String K_REALERT = "mail.reAlertMinutes";

    public static final String DEF_SUBJECT = "[Radio Mapper] {qtd} radio(s) offline";
    public static final String DEF_BODY =
            "Os seguintes radios pararam de responder:\n\n"
            + "{lista}\n\n"
            + "Projeto: {projeto}\n"
            + "Detectado em: {data} {hora}\n\n"
            + "-- \nMensagem automatica do Radio Mapper.";
    public static final String DEF_LINE =
            "- {nome} ({host}) no ponto {ponto}\n"
            + "    caiu em: {caiu}\n"
            + "    ultima vez online: {ultimoOnline}\n"
            + "    tempo online antes de cair: {tempoOnline}";
    public static final String DEF_RECOVERY_SUBJECT = "[Radio Mapper] {qtd} radio(s) voltaram";

    public static final int DEF_PORT = 587;
    /**
     * Padrões de imediatismo: alerta na primeira leitura DOWN e sem janela de
     * agrupamento. É o comportamento pedido — avisar no momento da queda.
     * Quem tiver enlace oscilando pode subir {@code failuresBeforeAlert} para
     * 2 ou 3, ao custo de um ciclo de sincronização de atraso.
     */
    public static final int DEF_FAILURES = 1;
    public static final int DEF_AGGREGATION = 0;
    public static final int DEF_REALERT = 30;

    private boolean enabled;
    private String host = "";
    private int port = DEF_PORT;
    private Security security = Security.STARTTLS;
    private String user = "";
    private String password = "";
    private String from = "";
    private String fromName = "Radio Mapper";
    private List<String> recipients = new ArrayList<>();
    private String subjectTemplate = DEF_SUBJECT;
    private String bodyTemplate = DEF_BODY;
    private String lineTemplate = DEF_LINE;
    private boolean notifyRecovery = true;
    private String recoverySubjectTemplate = DEF_RECOVERY_SUBJECT;
    private int failuresBeforeAlert = DEF_FAILURES;
    private int aggregationSeconds = DEF_AGGREGATION;
    private int reAlertMinutes = DEF_REALERT;

    // ------------------------ Persistência ------------------------

    public static MailConfig load() {
        MailConfig c = new MailConfig();
        c.enabled = P.getBoolean(K_ENABLED, false);
        c.host = P.get(K_HOST, "");
        c.port = P.getInt(K_PORT, DEF_PORT);
        try {
            c.security = Security.valueOf(P.get(K_SECURITY, Security.STARTTLS.name()));
        } catch (IllegalArgumentException ex) {
            c.security = Security.STARTTLS;
        }
        c.user = P.get(K_USER, "");
        c.password = P.get(K_PASSWORD, "");
        c.from = P.get(K_FROM, "");
        c.fromName = P.get(K_FROM_NAME, "Radio Mapper");
        c.recipients = splitRecipients(P.get(K_RECIPIENTS, ""));
        c.subjectTemplate = P.get(K_SUBJECT, DEF_SUBJECT);
        c.bodyTemplate = P.get(K_BODY, DEF_BODY);
        c.lineTemplate = P.get(K_LINE, DEF_LINE);
        c.notifyRecovery = P.getBoolean(K_NOTIFY_RECOVERY, true);
        c.recoverySubjectTemplate = P.get(K_RECOVERY_SUBJECT, DEF_RECOVERY_SUBJECT);
        c.failuresBeforeAlert = P.getInt(K_FAILURES, DEF_FAILURES);
        c.aggregationSeconds = P.getInt(K_AGGREGATION, DEF_AGGREGATION);
        c.reAlertMinutes = P.getInt(K_REALERT, DEF_REALERT);
        return c;
    }

    public void save() {
        P.putBoolean(K_ENABLED, enabled);
        P.put(K_HOST, nz(host));
        P.putInt(K_PORT, port);
        P.put(K_SECURITY, security.name());
        P.put(K_USER, nz(user));
        P.put(K_PASSWORD, nz(password));
        P.put(K_FROM, nz(from));
        P.put(K_FROM_NAME, nz(fromName));
        P.put(K_RECIPIENTS, String.join("; ", recipients));
        P.put(K_SUBJECT, nz(subjectTemplate));
        P.put(K_BODY, nz(bodyTemplate));
        P.put(K_LINE, nz(lineTemplate));
        P.putBoolean(K_NOTIFY_RECOVERY, notifyRecovery);
        P.put(K_RECOVERY_SUBJECT, nz(recoverySubjectTemplate));
        P.putInt(K_FAILURES, failuresBeforeAlert);
        P.putInt(K_AGGREGATION, aggregationSeconds);
        P.putInt(K_REALERT, reAlertMinutes);
    }

    /**
     * Separa a lista de destinatários. O separador oficial é o ponto e vírgula
     * (mesma convenção do Outlook); vírgula e quebra de linha também passam,
     * porque lista colada de outro lugar costuma vir assim.
     *
     * Espaço NÃO separa de propósito: senão "a@b.com c@d.com" viraria dois
     * endereços silenciosamente, e um endereço digitado com espaço no meio
     * passaria despercebido em vez de ser recusado na validação.
     */
    public static List<String> splitRecipients(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String s : raw.split("[;,\\r\\n]+")) {
            String t = s.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /** Validação deliberadamente frouxa: só o que quebraria o envio na cara. */
    public static boolean looksLikeEmail(String s) {
        if (s == null) return false;
        String t = s.trim();
        int at = t.indexOf('@');
        return at > 0 && at == t.lastIndexOf('@')
                && at < t.length() - 1
                && t.indexOf('.', at) > at + 1
                && !t.endsWith(".")
                && !t.contains(" ");
    }

    /** Falta alguma coisa essencial para conseguir enviar? Devolve o motivo, ou null. */
    public String whyCannotSend() {
        if (host == null || host.isBlank()) return "servidor SMTP não informado";
        if (port <= 0 || port > 65535) return "porta SMTP inválida";
        if (from == null || from.isBlank()) return "remetente não informado";
        if (!looksLikeEmail(from)) return "remetente não parece um e-mail válido";
        if (recipients.isEmpty()) return "nenhum destinatário cadastrado";
        for (String r : recipients) {
            if (!looksLikeEmail(r)) return "destinatário inválido: " + r;
        }
        return null;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // ------------------------ Acessores ------------------------

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }

    public String getHost() { return host; }
    public void setHost(String v) { this.host = nz(v).trim(); }

    public int getPort() { return port; }
    public void setPort(int v) { this.port = v; }

    public Security getSecurity() { return security; }
    public void setSecurity(Security v) { this.security = v == null ? Security.STARTTLS : v; }

    public String getUser() { return user; }
    public void setUser(String v) { this.user = nz(v).trim(); }

    public String getPassword() { return password; }
    public void setPassword(String v) { this.password = nz(v); }

    public String getFrom() { return from; }
    public void setFrom(String v) { this.from = nz(v).trim(); }

    public String getFromName() { return fromName; }
    public void setFromName(String v) { this.fromName = nz(v).trim(); }

    public List<String> getRecipients() { return recipients; }
    public void setRecipients(List<String> v) {
        this.recipients = v == null ? new ArrayList<>() : new ArrayList<>(v);
    }
    public void setRecipients(String raw) { this.recipients = splitRecipients(raw); }

    public String getSubjectTemplate() { return subjectTemplate; }
    public void setSubjectTemplate(String v) { this.subjectTemplate = nz(v); }

    public String getBodyTemplate() { return bodyTemplate; }
    public void setBodyTemplate(String v) { this.bodyTemplate = nz(v); }

    public String getLineTemplate() { return lineTemplate; }
    public void setLineTemplate(String v) { this.lineTemplate = nz(v); }

    public boolean isNotifyRecovery() { return notifyRecovery; }
    public void setNotifyRecovery(boolean v) { this.notifyRecovery = v; }

    public String getRecoverySubjectTemplate() { return recoverySubjectTemplate; }
    public void setRecoverySubjectTemplate(String v) { this.recoverySubjectTemplate = nz(v); }

    public int getFailuresBeforeAlert() { return Math.max(1, failuresBeforeAlert); }
    public void setFailuresBeforeAlert(int v) { this.failuresBeforeAlert = v; }

    public int getAggregationSeconds() { return Math.max(0, aggregationSeconds); }
    public void setAggregationSeconds(int v) { this.aggregationSeconds = v; }

    public int getReAlertMinutes() { return Math.max(0, reAlertMinutes); }
    public void setReAlertMinutes(int v) { this.reAlertMinutes = v; }

    /** Cópia rasa, para o diálogo editar sem sujar a configuração ativa. */
    public MailConfig copy() {
        MailConfig c = new MailConfig();
        c.enabled = enabled; c.host = host; c.port = port; c.security = security;
        c.user = user; c.password = password; c.from = from; c.fromName = fromName;
        c.recipients = new ArrayList<>(recipients);
        c.subjectTemplate = subjectTemplate; c.bodyTemplate = bodyTemplate;
        c.lineTemplate = lineTemplate; c.notifyRecovery = notifyRecovery;
        c.recoverySubjectTemplate = recoverySubjectTemplate;
        c.failuresBeforeAlert = failuresBeforeAlert;
        c.aggregationSeconds = aggregationSeconds;
        c.reAlertMinutes = reAlertMinutes;
        return c;
    }

    /** Lista de placeholders aceitos, para mostrar como ajuda no diálogo. */
    public static List<String> placeholders() {
        return Arrays.asList(
                "{qtd}", "{lista}", "{projeto}", "{data}", "{hora}",
                "{nome}", "{host}", "{ponto}", "{vendor}", "{mac}", "{erro}",
                "{caiu}", "{ultimoOnline}", "{tempoOnline}");
    }
}
