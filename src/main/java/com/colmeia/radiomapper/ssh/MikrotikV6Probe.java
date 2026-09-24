package com.colmeia.radiomapper.ssh;

import com.colmeia.radiomapper.model.Radio;
import com.colmeia.radiomapper.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mikrotik RouterOS v6 - wireless classico via SSH:
 *   /interface wireless registration-table print terse
 * Cada linha vem como sequencia de pares chave=valor (alguns entre aspas).
 */
public class MikrotikV6Probe implements RadioProbe {

    private static final Pattern KV = Pattern.compile("([a-zA-Z0-9-]+)=(\"[^\"]*\"|\\S+)");

    @Override
    public List<NeighborInfo> probe(Radio radio) throws IOException {
        String out = SshExec.run(radio.getHost(), radio.getSshPort(), radio.getSshUser(),
                radio.getSshPassword(),
                "/interface wireless registration-table print terse");
        return parse(out);
    }

    static List<NeighborInfo> parse(String output) {
        List<NeighborInfo> result = new ArrayList<>();
        if (output == null) return result;
        for (String line : output.split("\\r?\\n")) {
            if (line.isBlank() || !line.contains("mac-address=")) continue;
            Map<String, String> kv = new HashMap<>();
            Matcher m = KV.matcher(line);
            while (m.find()) {
                String key = m.group(1);
                String val = m.group(2);
                if (val.startsWith("\"") && val.endsWith("\"")) val = val.substring(1, val.length() - 1);
                kv.put(key, val);
            }
            String mac = kv.getOrDefault("mac-address", "");
            // Builds antigas usam signal-strength; algumas mais novas (6.49+) já
            // expõem signal ou rx-signal. Tenta nessa ordem.
            int signal = firstInt(kv, "signal-strength", "signal", "rx-signal");
            long tx = parseRate(kv.get("tx-rate"));
            long rx = parseRate(kv.get("rx-rate"));
            long[] bytes = parseBytesCsv(kv.get("bytes"));
            if (bytes[0] == 0 && bytes[1] == 0) {
                bytes[0] = parseLong(kv.get("tx-bytes"));
                bytes[1] = parseLong(kv.get("rx-bytes"));
            }
            String name = firstNonBlank(kv, "comment", "radio-name", "name");
            String lastIp = firstNonBlank(kv, "last-ip", "address", "ip-address");
            if (!mac.isBlank()) {
                if (signal == 0) {
                    // Ajuda a diagnosticar firmwares que usam nomes não previstos.
                    Log.warn("Mikrotik v6: vizinho %s sem sinal — campos disponíveis: %s",
                            mac, kv.keySet());
                }
                result.add(new NeighborInfo(mac, signal, tx, rx, bytes[0], bytes[1], name, lastIp));
            }
        }
        return result;
    }

    static String firstNonBlank(Map<String, String> kv, String... keys) {
        for (String k : keys) {
            String v = kv.get(k);
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    private static int firstInt(Map<String, String> kv, String... keys) {
        for (String k : keys) {
            String v = kv.get(k);
            if (v == null) continue;
            try {
                String num = v.split("@", 2)[0].trim();
                return Integer.parseInt(num);
            } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    /** "bytes=12345,67890" -> [12345, 67890]; campos invalidos viram 0. */
    static long[] parseBytesCsv(String s) {
        long[] out = new long[]{0, 0};
        if (s == null || s.isBlank()) return out;
        String[] parts = s.split(",", 2);
        if (parts.length >= 1) out[0] = parseLong(parts[0]);
        if (parts.length >= 2) out[1] = parseLong(parts[1]);
        return out;
    }

    static long parseLong(String s) {
        if (s == null) return 0;
        try { return Long.parseLong(s.replaceAll("[^0-9]", "")); }
        catch (NumberFormatException e) { return 0; }
    }

    private static long parseRate(String s) {
        if (s == null) return 0;
        String num = s.replaceAll("[^0-9]", "");
        if (num.isBlank()) return 0;
        try { return Long.parseLong(num); } catch (NumberFormatException e) { return 0; }
    }
}
