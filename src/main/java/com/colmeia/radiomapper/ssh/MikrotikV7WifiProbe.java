package com.colmeia.radiomapper.ssh;

import com.colmeia.radiomapper.model.Radio;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mikrotik RouterOS v7 com wifiwave2 (driver wifi novo, hardware AX/AC moderno):
 *   /interface wifi registration-table print terse
 * Estrutura kv parecida com a v6, mas alguns campos mudaram de nome:
 *   - signal-strength -> signal (e/ou rx-signal)
 *   - tx-rate / rx-rate continuam, mas com "Mbps" embutido como string
 *
 * Fallback: alguns equipamentos mistos ainda respondem com /interface wireless,
 * por isso se a primeira saida estiver vazia tentamos o comando antigo.
 */
public class MikrotikV7WifiProbe implements RadioProbe {

    private static final Pattern KV = Pattern.compile("([a-zA-Z0-9-]+)=(\"[^\"]*\"|\\S+)");

    @Override
    public List<NeighborInfo> probe(Radio radio) throws IOException {
        String out = SshExec.run(radio.getHost(), radio.getSshPort(), radio.getSshUser(),
                radio.getSshPassword(),
                "/interface wifi registration-table print terse");
        List<NeighborInfo> parsed = parse(out);
        if (parsed.isEmpty()) {
            // fallback para hardware antigo ainda no driver wireless
            out = SshExec.run(radio.getHost(), radio.getSshPort(), radio.getSshUser(),
                    radio.getSshPassword(),
                    "/interface wireless registration-table print terse");
            parsed = MikrotikV6Probe.parse(out);
        }
        return parsed;
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
            // v7 wifiwave2: signal, rx-signal, signal-strength (algumas builds)
            int signal = firstInt(kv, "signal", "rx-signal", "signal-strength");
            long tx = parseRate(kv.get("tx-rate"));
            long rx = parseRate(kv.get("rx-rate"));
            long[] bytes = MikrotikV6Probe.parseBytesCsv(kv.get("bytes"));
            if (bytes[0] == 0 && bytes[1] == 0) {
                bytes[0] = MikrotikV6Probe.parseLong(kv.get("tx-bytes"));
                bytes[1] = MikrotikV6Probe.parseLong(kv.get("rx-bytes"));
            }
            String name = MikrotikV6Probe.firstNonBlank(kv, "comment", "radio-name", "name");
            String lastIp = MikrotikV6Probe.firstNonBlank(kv, "last-ip", "address", "ip-address");
            if (!mac.isBlank()) {
                result.add(new NeighborInfo(mac, signal, tx, rx, bytes[0], bytes[1], name, lastIp));
            }
        }
        return result;
    }

    private static int firstInt(Map<String, String> kv, String... keys) {
        for (String k : keys) {
            String v = kv.get(k);
            if (v == null) continue;
            try { return Integer.parseInt(v.split("@", 2)[0].trim()); }
            catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private static long parseRate(String s) {
        if (s == null) return 0;
        String num = s.replaceAll("[^0-9]", "");
        if (num.isBlank()) return 0;
        try { return Long.parseLong(num); } catch (NumberFormatException e) { return 0; }
    }
}
