package com.colmeia.radiomapper.ssh;

import com.colmeia.radiomapper.model.Router;
import com.colmeia.radiomapper.model.RouterInterface;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê as interfaces de um RouterOS e seus contadores de bytes.
 *
 * <h3>Dois comandos, não um</h3>
 * {@code /interface print terse} dá a lista com nome, tipo e flags, mas não
 * traz contador de tráfego em todas as versões. {@code /interface print stats
 * terse} traz os contadores mas é mais pobre em metadados. Ler os dois e
 * casar por nome é o que funciona em v6 e v7 sem depender de qual build está
 * na frente.
 *
 * <h3>As flags vêm em coluna, não em chave</h3>
 * No modo terse o RouterOS escreve as flags como letras soltas antes dos pares
 * chave=valor: {@code 0 R  name=ether1 type=ether ...}. {@code X} é
 * desabilitada, {@code R} é running, {@code D} é dinâmica, {@code S} slave.
 * Não há {@code running=true} para ler, então a leitura é posicional — o que
 * torna o parser o lugar mais frágil daqui, e o motivo de ele ser tolerante:
 * campo que não bate vira valor neutro em vez de exceção.
 */
public final class RouterOsProbe {

    private RouterOsProbe() {}

    private static final Pattern KV = Pattern.compile("([a-zA-Z0-9-]+)=(\"[^\"]*\"|\\S+)");

    /** O trecho antes do primeiro "chave=" — índice e flags. */
    private static final Pattern PREFIXO = Pattern.compile("^\\s*(\\d+)\\s+([A-Za-z ]*?)\\s*(?=[a-zA-Z0-9-]+=)");

    /**
     * Lista as interfaces do equipamento, já com os contadores quando houver.
     *
     * @throws IOException se o SSH falhar — quem chama trata como equipamento fora
     */
    public static List<RouterInterface> listInterfaces(Router r) throws IOException {
        String lista = SshExec.run(r.getHost(), r.getSshPort(), r.getSshUser(),
                r.getSshPassword(), "/interface print terse without-paging");
        List<RouterInterface> ifaces = parseInterfaces(lista);

        // Contadores são opcionais: se este comando falhar ou vier vazio, a
        // lista continua válida — só não dá para medir tráfego por diferença.
        try {
            String stats = SshExec.run(r.getHost(), r.getSshPort(), r.getSshUser(),
                    r.getSshPassword(), "/interface print stats terse without-paging");
            aplicarContadores(ifaces, parseCounters(stats));
        } catch (IOException ignored) {
            // segue com o que já se tem
        }
        return ifaces;
    }

    /**
     * Lê só os contadores de uma interface — a chamada repetida do gráfico ao vivo.
     *
     * @return {rxBytes, txBytes}, ou null se a interface não apareceu
     */
    public static long[] readCounters(Router r, String interfaceName) throws IOException {
        String out = SshExec.run(r.getHost(), r.getSshPort(), r.getSshUser(), r.getSshPassword(),
                "/interface print stats terse without-paging where name=\"" + interfaceName + "\"");
        Map<String, long[]> m = parseCounters(out);
        long[] v = m.get(interfaceName);
        if (v != null) return v;
        // Alguns builds ignoram o "where" no modo terse e devolvem tudo.
        return m.size() == 1 ? m.values().iterator().next() : null;
    }

    // ------------------------ Parsing ------------------------

    static List<RouterInterface> parseInterfaces(String saida) {
        List<RouterInterface> out = new ArrayList<>();
        if (saida == null) return out;
        for (String linha : saida.split("\\r?\\n")) {
            if (linha.isBlank() || !linha.contains("name=")) continue;
            Map<String, String> kv = pares(linha);
            String nome = kv.getOrDefault("name", "");
            if (nome.isBlank()) continue;

            String flags = flagsDe(linha);
            RouterInterface i = new RouterInterface();
            i.setName(nome);
            i.setType(kv.getOrDefault("type", ""));
            i.setComment(kv.getOrDefault("comment", ""));
            i.setMacAddress(kv.getOrDefault("mac-address", ""));
            // X e R são as únicas que mudam a leitura da tela; as outras
            // (dinâmica, slave, passthrough) não alteram se passa tráfego.
            i.setDisabled(flags.indexOf('X') >= 0);
            i.setRunning(flags.indexOf('R') >= 0);
            out.add(i);
        }
        return out;
    }

    /** name -> {rxBytes, txBytes} */
    static Map<String, long[]> parseCounters(String saida) {
        Map<String, long[]> out = new LinkedHashMap<>();
        if (saida == null) return out;
        for (String linha : saida.split("\\r?\\n")) {
            if (linha.isBlank() || !linha.contains("name=")) continue;
            Map<String, String> kv = pares(linha);
            String nome = kv.getOrDefault("name", "");
            if (nome.isBlank()) continue;
            long rx = primeiroLong(kv, "rx-byte", "rx-bytes", "bytes-in");
            long tx = primeiroLong(kv, "tx-byte", "tx-bytes", "bytes-out");
            out.put(nome, new long[] { rx, tx });
        }
        return out;
    }

    private static void aplicarContadores(List<RouterInterface> ifaces, Map<String, long[]> stats) {
        for (RouterInterface i : ifaces) {
            long[] v = stats.get(i.getName());
            if (v == null) continue;
            i.setRxBytes(v[0]);
            i.setTxBytes(v[1]);
        }
    }

    private static Map<String, String> pares(String linha) {
        Map<String, String> kv = new HashMap<>();
        Matcher m = KV.matcher(linha);
        while (m.find()) {
            String v = m.group(2);
            if (v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
            kv.put(m.group(1), v);
        }
        return kv;
    }

    /** Letras de flag entre o índice da linha e o primeiro chave=valor. */
    static String flagsDe(String linha) {
        Matcher m = PREFIXO.matcher(linha);
        return m.find() ? m.group(2).replace(" ", "") : "";
    }

    private static long primeiroLong(Map<String, String> kv, String... chaves) {
        for (String c : chaves) {
            String v = kv.get(c);
            if (v == null) continue;
            // RouterOS separa milhar com espaço em alguns formatos de saída.
            String limpo = v.replaceAll("[^0-9]", "");
            if (limpo.isBlank()) continue;
            try { return Long.parseLong(limpo); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }
}
