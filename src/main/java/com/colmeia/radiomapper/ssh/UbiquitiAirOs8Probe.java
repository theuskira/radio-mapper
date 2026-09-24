package com.colmeia.radiomapper.ssh;

import com.colmeia.radiomapper.model.Radio;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Ubiquiti AirOS 8 (AirMAX AC - NanoStation AC, Rocket AC, LiteBeam AC, PowerBeam AC, etc.).
 * O comando 'wstalist' continua existindo, mas a estrutura JSON tem campos
 * adicionais e tipos diferentes:
 *   - "tx"/"rx" agora sao numbers (nao strings)
 *   - "chainrssi" array com sinal por cadeia
 *   - "remote" objeto com info do peer (nome, modelo, MAC, etc.)
 *   - "lastip" continua util para validar MAC contra IP
 *
 * Em modo Station, o uplink aparece tambem em wstalist (lado AP do peer),
 * mas se vier vazio usamos 'mca-status' como em AirOS 6.
 */
public class UbiquitiAirOs8Probe implements RadioProbe {

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
            UbiquitiAirOs6Probe.parseMcaStatus(mca).ifPresent(result::add);
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
                long tx = n.path("tx").asLong(0);
                long rx = n.path("rx").asLong(0);
                long txB = n.path("tx_bytes").asLong(0);
                long rxB = n.path("rx_bytes").asLong(0);

                // alguns firmwares AirOS 8 trazem o MAC do peer dentro de "remote"
                JsonNode remote = n.path("remote");
                if (mac.isBlank() && remote.has("mac")) {
                    mac = remote.path("mac").asText("");
                }
                String name = UbiquitiAirOs6Probe.firstNonBlank(n, "name", "hostname");
                if (name.isBlank() && remote.isObject()) {
                    name = UbiquitiAirOs6Probe.firstNonBlank(remote, "hostname", "name", "device");
                }
                String lastIp = UbiquitiAirOs6Probe.firstNonBlank(n, "lastip", "last_ip", "ip");
                if (lastIp.isBlank() && remote.isObject()) {
                    lastIp = UbiquitiAirOs6Probe.firstNonBlank(remote, "lastip", "last_ip", "ip");
                }
                if (!mac.isBlank()) {
                    out.add(new NeighborInfo(mac, signal, tx, rx, txB, rxB, name, lastIp));
                }
            }
        } catch (IOException ignored) {}
        return out;
    }
}
