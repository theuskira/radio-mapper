package com.colmeia.radiomapper.ssh;

public class NeighborInfo {
    public final String mac;
    public final int signalDbm;
    /** PHY rate atual (link speed), em Mbps. */
    public final long txMbps;
    public final long rxMbps;
    /** Contadores acumulados de bytes (para calculo de throughput entre 2 amostras). */
    public final long txBytes;
    public final long rxBytes;
    /** Nome / hostname do dispositivo vizinho, como informado pelo rádio (pode vir vazio). */
    public final String name;
    /** Último IP visto do dispositivo vizinho, como informado pelo rádio (pode vir vazio). */
    public final String lastIp;

    public NeighborInfo(String mac, int signalDbm, long txMbps, long rxMbps) {
        this(mac, signalDbm, txMbps, rxMbps, 0L, 0L, "", "");
    }

    public NeighborInfo(String mac, int signalDbm, long txMbps, long rxMbps,
                        long txBytes, long rxBytes) {
        this(mac, signalDbm, txMbps, rxMbps, txBytes, rxBytes, "", "");
    }

    public NeighborInfo(String mac, int signalDbm, long txMbps, long rxMbps,
                        long txBytes, long rxBytes, String name, String lastIp) {
        this.mac = mac == null ? "" : mac.toLowerCase();
        this.signalDbm = signalDbm;
        this.txMbps = txMbps;
        this.rxMbps = rxMbps;
        this.txBytes = txBytes;
        this.rxBytes = rxBytes;
        this.name = name == null ? "" : name;
        this.lastIp = lastIp == null ? "" : lastIp;
    }
}
