package com.colmeia.radiomapper.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Link {
    private String radioAId;
    private String radioBId;
    private int signalDbm;
    private int signalDbmReverse;
    /** PHY rate atual (link speed) em Mbps. */
    private long txMbps;
    private long rxMbps;
    private Instant lastSeen = Instant.now();
    private boolean stale;

    /**
     * @deprecated substituido por {@link #origin}. Fica so para ler .rmap
     *             gravado antes de {@link LinkOrigin} existir.
     *
     * Enlace desenhado a mao para planejamento, nao descoberto pelos radios.
     *
     * Muda o ciclo de vida do enlace. Um enlace descoberto so existe enquanto
     * os radios o confirmam: a sincronizacao remove o que nao apareceu na
     * rodada. Um enlace planejado e o contrario — ele existe JUSTAMENTE
     * porque ainda nao ha nada no ar, e sumir na primeira sincronizacao
     * destruiria o trabalho de planejamento.
     *
     * Quando os radios finalmente sobem e se enxergam, a descoberta confirma
     * o par e a marca cai: o plano virou enlace de verdade, com sinal medido,
     * e dali em diante segue as regras dos outros.
     */
    @Deprecated
    private transient boolean planned;

    /** Quem criou este enlace, e portanto quem pode remove-lo. */
    private LinkOrigin origin = LinkOrigin.DISCOVERED;

    /** Throughput real em bits/s (calculado entre duas amostras consecutivas). */
    private long txBps;
    private long rxBps;

    /** Snapshot anterior dos contadores acumulados, usado para o calculo. Transient. */
    @JsonIgnore private long lastTxBytes;
    @JsonIgnore private long lastRxBytes;
    @JsonIgnore private Instant lastBytesAt;

    public Link() {}

    public Link(String radioAId, String radioBId, int signalDbm) {
        this.radioAId = radioAId;
        this.radioBId = radioBId;
        this.signalDbm = signalDbm;
    }

    public String getRadioAId() { return radioAId; }
    public void setRadioAId(String radioAId) { this.radioAId = radioAId; }

    public String getRadioBId() { return radioBId; }
    public void setRadioBId(String radioBId) { this.radioBId = radioBId; }

    public int getSignalDbm() { return signalDbm; }
    public void setSignalDbm(int signalDbm) { this.signalDbm = signalDbm; }

    public int getSignalDbmReverse() { return signalDbmReverse; }
    public void setSignalDbmReverse(int signalDbmReverse) { this.signalDbmReverse = signalDbmReverse; }

    /**
     * Sinal "efetivo" para exibir no rótulo do enlace: se apenas uma das duas
     * direções foi reportada, retorna essa; se ambas, retorna a pior (mais
     * negativa), que é a margem de segurança do enlace. Sinal 0 = sem dado.
     */
    @JsonIgnore
    public int getDisplaySignalDbm() {
        if (signalDbm == 0) return signalDbmReverse;
        if (signalDbmReverse == 0) return signalDbm;
        return Math.min(signalDbm, signalDbmReverse);
    }

    public long getTxMbps() { return txMbps; }
    public void setTxMbps(long txMbps) { this.txMbps = txMbps; }

    public long getRxMbps() { return rxMbps; }
    public void setRxMbps(long rxMbps) { this.rxMbps = rxMbps; }

    public Instant getLastSeen() { return lastSeen; }
    public void setLastSeen(Instant lastSeen) { this.lastSeen = lastSeen; }

    public boolean isStale() { return stale; }
    public void setStale(boolean stale) { this.stale = stale; }

    public LinkOrigin getOrigin() { return origin == null ? LinkOrigin.DISCOVERED : origin; }
    public void setOrigin(LinkOrigin o) { this.origin = o == null ? LinkOrigin.DISCOVERED : o; }

    @JsonIgnore public boolean isPlanned() { return getOrigin() == LinkOrigin.PLANNED; }
    @JsonIgnore public boolean isManual() { return getOrigin() == LinkOrigin.MANUAL; }

    /** Afirmado por uma pessoa: a sincronizacao nao remove nem marca obsoleto. */
    @JsonIgnore public boolean isUserDefined() { return getOrigin().isUserDefined(); }

    /**
     * Compatibilidade: .rmap gravado quando "planned" era um booleano.
     *
     * Só sobe a marca, nunca desce — um arquivo antigo sem o campo não deve
     * sobrescrever a origem que veio de {@code origin}.
     */
    @com.fasterxml.jackson.annotation.JsonSetter("planned")
    public void setPlannedLegacy(boolean v) {
        if (v) this.origin = LinkOrigin.PLANNED;
    }

    /**
     * Enlace afirmado à mão pode ainda não ter leitura nenhuma: o número que
     * aparece nele é estimativa, não medição.
     */
    @JsonIgnore
    public boolean hasMeasurement() {
        return !isPlanned() && getDisplaySignalDbm() != 0;
    }

    public long getTxBps() { return txBps; }
    public void setTxBps(long txBps) { this.txBps = txBps; }

    public long getRxBps() { return rxBps; }
    public void setRxBps(long rxBps) { this.rxBps = rxBps; }

    @JsonIgnore public long getLastTxBytes() { return lastTxBytes; }
    public void setLastTxBytes(long v) { this.lastTxBytes = v; }

    @JsonIgnore public long getLastRxBytes() { return lastRxBytes; }
    public void setLastRxBytes(long v) { this.lastRxBytes = v; }

    @JsonIgnore public Instant getLastBytesAt() { return lastBytesAt; }
    public void setLastBytesAt(Instant v) { this.lastBytesAt = v; }
}
