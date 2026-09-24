package com.colmeia.radiomapper.ssh;

import com.colmeia.radiomapper.model.Router;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * "Este roteador está de pé?" — nada além disso.
 *
 * Num equipamento com SSH liberado, um comando barato serve de prova de vida
 * melhor que um ping: confirma que o RouterOS está respondendo, e não só que
 * a placa de rede está ligada. Sem SSH, cai no mesmo par ICMP-então-TCP que o
 * {@link PingProbe} usa para os rádios.
 */
public final class RouterPing {

    private RouterPing() {}

    private static final int ICMP_TIMEOUT_MS = 3_000;
    private static final int TCP_TIMEOUT_MS = 5_000;

    /** @throws IOException se o equipamento não respondeu */
    public static void check(Router r) throws IOException {
        String host = r.getHost();
        if (host == null || host.isBlank()) throw new IOException("host vazio");

        if (r.canProbeInterfaces()) {
            // Barato e universal nas duas versões do RouterOS.
            SshExec.run(host, r.getSshPort(), r.getSshUser(), r.getSshPassword(),
                    "/system identity print");
            return;
        }

        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isReachable(ICMP_TIMEOUT_MS)) return;
        } catch (IOException ignored) {
            // segue pro TCP
        }
        int port = r.getSshPort() > 0 ? r.getSshPort() : 22;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), TCP_TIMEOUT_MS);
        } catch (IOException ex) {
            throw new IOException("sem resposta (ICMP e TCP:" + port + ")");
        }
    }
}
