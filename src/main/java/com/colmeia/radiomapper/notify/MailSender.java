package com.colmeia.radiomapper.notify;

import com.colmeia.radiomapper.util.Log;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/**
 * Envio SMTP. Bloqueia a thread chamadora — nunca chame da thread do JavaFX.
 *
 * Não tem estado: cada envio abre e fecha sua própria sessão. Para o volume
 * aqui (um e-mail por incidente, não por rádio) isso é mais simples e mais
 * robusto que manter conexão viva, que expiraria no meio da madrugada
 * justamente quando a rede cai.
 */
public final class MailSender {

    private MailSender() {}

    /** Erro de envio com mensagem já legível para mostrar ao usuário. */
    public static class SendException extends Exception {
        public SendException(String msg, Throwable cause) { super(msg, cause); }
    }

    public static void send(MailConfig cfg, String subject, String body) throws SendException {
        send(cfg, subject, body, cfg.getRecipients());
    }

    public static void send(MailConfig cfg, String subject, String body, List<String> to)
            throws SendException {

        String why = cfg.whyCannotSend();
        if (why != null) throw new SendException("Configuração incompleta: " + why, null);
        if (to == null || to.isEmpty()) throw new SendException("Nenhum destinatário.", null);

        Properties props = new Properties();
        props.put("mail.smtp.host", cfg.getHost());
        props.put("mail.smtp.port", String.valueOf(cfg.getPort()));
        props.put("mail.smtp.connectiontimeout", "15000");
        props.put("mail.smtp.timeout", "20000");
        props.put("mail.smtp.writetimeout", "20000");

        switch (cfg.getSecurity()) {
            case STARTTLS -> {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
            }
            case SSL -> {
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.socketFactory.port", String.valueOf(cfg.getPort()));
            }
            case NONE -> { /* nada: SMTP puro */ }
        }

        boolean auth = !cfg.getUser().isBlank();
        props.put("mail.smtp.auth", String.valueOf(auth));

        Session session = auth
                ? Session.getInstance(props, new Authenticator() {
                    @Override protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(cfg.getUser(), cfg.getPassword());
                    }
                })
                : Session.getInstance(props);

        try {
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(fromAddress(cfg));
            for (String r : to) {
                msg.addRecipient(Message.RecipientType.TO, new InternetAddress(r.trim()));
            }
            msg.setSubject(subject, StandardCharsets.UTF_8.name());
            msg.setText(body, StandardCharsets.UTF_8.name());
            msg.setSentDate(new java.util.Date());

            Transport.send(msg);
            Log.info("E-mail enviado para %d destinatario(s): %s", to.size(), subject);

        } catch (jakarta.mail.AuthenticationFailedException ex) {
            throw new SendException("Servidor recusou usuario/senha. Em Gmail e Microsoft 365, "
                    + "use uma senha de aplicativo, nao a senha da conta.", ex);
        } catch (jakarta.mail.MessagingException ex) {
            throw new SendException(humanize(ex), ex);
        }
    }

    private static InternetAddress fromAddress(MailConfig cfg) throws jakarta.mail.MessagingException {
        try {
            return cfg.getFromName().isBlank()
                    ? new InternetAddress(cfg.getFrom())
                    : new InternetAddress(cfg.getFrom(), cfg.getFromName(), StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException ex) {
            return new InternetAddress(cfg.getFrom());
        }
    }

    /** Traduz as falhas mais comuns para algo acionável. */
    private static String humanize(jakarta.mail.MessagingException ex) {
        Throwable root = ex;
        while (root.getCause() != null) root = root.getCause();
        String m = root.getMessage() == null ? ex.toString() : root.getMessage();

        if (root instanceof java.net.UnknownHostException) {
            return "Servidor SMTP nao encontrado: " + m
                    + ". Confira o endereco e se a maquina tem internet.";
        }
        if (root instanceof java.net.ConnectException) {
            return "Nao consegui conectar na porta informada (" + m
                    + "). Confira porta e firewall.";
        }
        if (root instanceof java.net.SocketTimeoutException) {
            return "Tempo esgotado falando com o servidor. "
                    + "Porta bloqueada pelo firewall costuma dar exatamente isso.";
        }
        if (m != null && m.toLowerCase().contains("ssl")) {
            return "Falha de TLS/SSL: " + m
                    + ". Normalmente e o modo de criptografia trocado "
                    + "(587 usa STARTTLS, 465 usa SSL direto).";
        }
        return m;
    }
}
