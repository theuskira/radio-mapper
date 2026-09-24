package com.colmeia.radiomapper.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Uma porta do roteador, como ele mesmo a descreve.
 *
 * Os contadores de bytes são acumulados desde que o equipamento subiu; o que
 * interessa na tela é a diferença entre duas leituras dividida pelo tempo.
 * Por isso eles ficam aqui crus, e quem calcula taxa é quem tem duas amostras
 * em mãos.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RouterInterface {

    private String name = "";
    private String type = "";
    private String comment = "";
    private String macAddress = "";
    private boolean running;
    private boolean disabled;

    /** Contadores acumulados na última leitura. 0 = não informado. */
    private long rxBytes;
    private long txBytes;

    public RouterInterface() {}

    public RouterInterface(String name, String type, boolean running, boolean disabled) {
        this.name = name == null ? "" : name;
        this.type = type == null ? "" : type;
        this.running = running;
        this.disabled = disabled;
    }

    public String getName() { return name == null ? "" : name; }
    public void setName(String v) { this.name = v == null ? "" : v; }

    public String getType() { return type == null ? "" : type; }
    public void setType(String v) { this.type = v == null ? "" : v; }

    public String getComment() { return comment == null ? "" : comment; }
    public void setComment(String v) { this.comment = v == null ? "" : v; }

    public String getMacAddress() { return macAddress == null ? "" : macAddress; }
    public void setMacAddress(String v) { this.macAddress = v == null ? "" : v; }

    public boolean isRunning() { return running; }
    public void setRunning(boolean v) { this.running = v; }

    public boolean isDisabled() { return disabled; }
    public void setDisabled(boolean v) { this.disabled = v; }

    public long getRxBytes() { return rxBytes; }
    public void setRxBytes(long v) { this.rxBytes = v; }

    public long getTxBytes() { return txBytes; }
    public void setTxBytes(long v) { this.txBytes = v; }

    /** Como a porta aparece numa lista: nome, tipo e por que não está passando tráfego. */
    @JsonIgnore
    public String describe() {
        StringBuilder sb = new StringBuilder(getName());
        if (!getType().isBlank()) sb.append("  (").append(getType()).append(')');
        if (disabled) sb.append("  — desabilitada");
        else if (!running) sb.append("  — sem link");
        if (!getComment().isBlank()) sb.append("  · ").append(getComment());
        return sb.toString();
    }

    @Override public String toString() { return describe(); }
}
