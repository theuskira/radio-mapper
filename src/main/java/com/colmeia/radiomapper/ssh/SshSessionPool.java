package com.colmeia.radiomapper.ssh;

import com.colmeia.radiomapper.util.Log;
import net.schmizz.keepalive.KeepAliveProvider;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Pool de conexões SSH reutilizáveis por host:port:user.
 *
 * Por que: abrir SSH (handshake + key exchange + auth) custa segundos por
 * rádio; em redes com dezenas de pontos isso dominava cada rodada de sync.
 * Aqui guardamos um {@link SSHClient} por destino; em cada comando só abrimos
 * um {@link Session} curto (multiplexado sobre o mesmo transport).
 *
 * Concorrência: cada entry tem um lock só para connect/reconnect. A criação
 * de Sessions concorrentes sobre o mesmo SSHClient é suportada pelo sshj e
 * acontece sem lock.
 *
 * Keep-alive: 30s — derruba conexões mortas sem esperar pelo timeout TCP, o
 * que permite reconectar na próxima chamada quando o rádio reinicia.
 *
 * Ciclo de vida: chame {@link #closeAll()} no shutdown da aplicação.
 */
public final class SshSessionPool {

    public static final SshSessionPool INSTANCE = new SshSessionPool();

    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int SOCKET_TIMEOUT_MS = 15_000;
    private static final int CMD_TIMEOUT_SEC = 15;
    private static final int KEEPALIVE_SEC = 30;

    private static final class Entry {
        final Object lock = new Object();
        SSHClient client;
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    private SshSessionPool() {}

    public String exec(String host, int port, String user, String password, String command)
            throws IOException {
        String key = host + ":" + port + ":" + user;
        Entry entry = entries.computeIfAbsent(key, k -> new Entry());

        IOException lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            SSHClient client = acquire(entry, host, port, user, password);
            try (Session session = client.startSession()) {
                Session.Command cmd = session.exec(command);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                cmd.getInputStream().transferTo(out);
                cmd.join(CMD_TIMEOUT_SEC, TimeUnit.SECONDS);
                return out.toString();
            } catch (IOException ex) {
                lastError = ex;
                // Só vale a pena reconectar se o transport caiu. Erros do
                // comando em si (timeout, exit != 0) reusam a mesma conexão.
                boolean transportDead = !client.isConnected();
                synchronized (entry.lock) {
                    if (transportDead && entry.client == client) {
                        closeQuietly(entry.client, host);
                        entry.client = null;
                    }
                }
                if (!transportDead) throw ex;
            }
        }
        throw lastError != null ? lastError : new IOException("ssh exec falhou: " + key);
    }

    private SSHClient acquire(Entry entry, String host, int port, String user, String password)
            throws IOException {
        synchronized (entry.lock) {
            SSHClient c = entry.client;
            if (c != null && c.isConnected() && c.isAuthenticated()) return c;
            closeQuietly(c, host);
            entry.client = connect(host, port, user, password);
            return entry.client;
        }
    }

    private static SSHClient connect(String host, int port, String user, String password)
            throws IOException {
        DefaultConfig cfg = new DefaultConfig();
        cfg.setKeepAliveProvider(KeepAliveProvider.KEEP_ALIVE);
        SSHClient ssh = new SSHClient(cfg);
        ssh.addHostKeyVerifier(new PromiscuousVerifier());
        ssh.setConnectTimeout(CONNECT_TIMEOUT_MS);
        ssh.setTimeout(SOCKET_TIMEOUT_MS);
        try {
            ssh.connect(host, port);
            ssh.authPassword(user, password);
            ssh.getConnection().getKeepAlive().setKeepAliveInterval(KEEPALIVE_SEC);
            Log.info("SSH conectado: %s@%s:%d", user, host, port);
            return ssh;
        } catch (IOException ex) {
            closeQuietly(ssh, host);
            throw ex;
        }
    }

    private static void closeQuietly(SSHClient c, String host) {
        if (c == null) return;
        try { c.disconnect(); } catch (Exception ignored) {}
        try { c.close(); } catch (Exception ignored) {}
        if (host != null) Log.info("SSH fechado: %s", host);
    }

    /** Fecha todas as conexões. Chamar no shutdown da aplicação. */
    public void closeAll() {
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            Entry entry = e.getValue();
            synchronized (entry.lock) {
                closeQuietly(entry.client, e.getKey());
                entry.client = null;
            }
        }
        entries.clear();
    }
}
