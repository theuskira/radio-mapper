package com.colmeia.radiomapper.ssh;

import com.colmeia.radiomapper.model.Radio;

import java.io.IOException;
import java.util.List;

public interface RadioProbe {
    List<NeighborInfo> probe(Radio radio) throws IOException;

    static RadioProbe forRadio(Radio radio) {
        // O papel manda sobre o fabricante: marcar "somente ping" e' dizer
        // que nao se quer SSH neste equipamento, seja ele qual for.
        if (radio.getRole().monitorOnly()) return new PingProbe();
        return switch (radio.getVendor()) {
            case MIKROTIK_V6        -> new MikrotikV6Probe();
            case MIKROTIK_V7_WIFI   -> new MikrotikV7WifiProbe();
            case UBIQUITI_AIROS_6   -> new UbiquitiAirOs6Probe();
            case UBIQUITI_AIROS_8   -> new UbiquitiAirOs8Probe();
            case OTHER              -> new PingProbe();
        };
    }
}
