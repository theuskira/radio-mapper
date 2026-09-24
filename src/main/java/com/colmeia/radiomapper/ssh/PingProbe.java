package com.colmeia.radiomapper.ssh;

import com.colmeia.radiomapper.model.Radio;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * Probe minimo para rádios marcados como OTHER: nao tenta SSH nem descobrir
 * vizinhos - apenas confirma se o IP responde.
 *
 * Estrategia:
 *  1) ICMP via {@link InetAddress#isReachable(int)} (3s). Em Windows muitas
 *     vezes falha sem privilegios elevados, entao
 *  2) faz um TCP connect curto no sshPort do radio (5s). Se conectar, OK.
 *
 * Sucesso => retorna lista vazia (radio fica UP, sem novos vizinhos).
 * Falha   => lanca IOException (radio vira DOWN, links com ele ficam stale).
 */
public class PingProbe implements RadioProbe {

    private static final int ICMP_TIMEOUT_MS = 3_000;
    private static final int TCP_TIMEOUT_MS = 5_000;

    @Override
    public List<NeighborInfo> probe(Radio radio) throws IOException {
        String host = radio.getHost();
        if (host == null || host.isBlank()) {
            throw new IOException("host vazio");
        }

        // 1) ICMP nativo
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isReachable(ICMP_TIMEOUT_MS)) return List.of();
        } catch (IOException ignored) {
            // segue pro TCP
        }

        // 2) fallback: TCP connect na porta SSH
        int port = radio.getSshPort() > 0 ? radio.getSshPort() : 22;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), TCP_TIMEOUT_MS);
            return List.of();
        } catch (IOException ex) {
            throw new IOException("ping falhou (ICMP e TCP:" + port + " sem resposta)");
        }
    }
}
