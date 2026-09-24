package com.colmeia.radiomapper.ssh;

import com.colmeia.radiomapper.model.Radio;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Ubiquiti AirOS 6 (AirMAX M - NanoStation M, Rocket M, LiteBeam M, etc.).
 * Comando 'wstalist' devolve JSON com a lista de estacoes (modo AP).
 * Em modo Station, 'mca-status' tem o sinal e o MAC do AP.
 *
 * Formato AirOS 6 (campos string, "tx"/"rx" sao Mbps como string):
 *   [{ "mac":"...", "signal":-65, "rx":"130", "tx":"130", "ccq":99, ... }]
 */
public class UbiquitiAirOs6Probe implements RadioProbe {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public List<NeighborInfo> probe(Radio radio) throws IOException {
        List<NeighborInfo> result = new ArrayList<>();

        String wstalist = SshExec.run(radio.getHost(), radio.getSshPort(),
                radio.getSshUser(), radio.getSshPassword(), "wstalist");
        result.addAll(parseWstalist(wstalist));

        if (result.isEmpty()) {
            String mca = SshExec.run(radio.getHost(), radio.getSshPort(),
                    radio.getSshUser(), radio.getSshPassword(), "mca-status");
            parseMcaStatus(mca).ifPresent(result::add);
        }
        return result;
    }

    static List<NeighborInfo> parseWstalist(String json) {
        List<NeighborInfo> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isArray()) return out;
            for (JsonNode n : root) {
                String mac = n.path("mac").asText("");
                int signal = n.path("signal").asInt(0);
                long tx = parseLong(n.path("tx").asText("0"));
                long rx = parseLong(n.path("rx").asText("0"));
                long txB = n.path("tx_bytes").asLong(0);
                long rxB = n.path("rx_bytes").asLong(0);
                // alguns firmwares trazem como string
                if (txB == 0) txB = parseLong(n.path("tx_bytes").asText("0"));
                if (rxB == 0) rxB = parseLong(n.path("rx_bytes").asText("0"));
                String name = firstNonBlank(n, "name", "hostname");
                String lastIp = firstNonBlank(n, "lastip", "last_ip", "ip");
                if (!mac.isBlank()) {
                    out.add(new NeighborInfo(mac, signal, tx, rx, txB, rxB, name, lastIp));
                }
            }
        } catch (IOException ignored) {}
        return out;
    }

    static Optional<NeighborInfo> parseMcaStatus(String text) {
        if (text == null) return Optional.empty();
        String mac = "";
        int signal = 0;
        for (String line : text.split("\\r?\\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            if (k.equalsIgnoreCase("wlanPollerCpe") || k.equalsIgnoreCase("apMac")) mac = v;
            else if (k.equalsIgnoreCase("signal") || k.equalsIgnoreCase("rssi")) {
                try { signal = Integer.parseInt(v.split(" ")[0]); } catch (NumberFormatException ignored) {}
            }
        }
        return mac.isBlank() ? Optional.empty()
                : Optional.of(new NeighborInfo(mac, signal, 0, 0));
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.replaceAll("[^0-9]", "")); }
        catch (NumberFormatException e) { return 0; }
    }

    static String firstNonBlank(JsonNode n, String... keys) {
        for (String k : keys) {
            String v = n.path(k).asText("");
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }
}
