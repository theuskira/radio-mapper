package com.colmeia.radiomapper.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Um roteador instalado num ponto da rede.
 *
 * <h3>Por que não é um Radio</h3>
 * Roteador não tem antena, azimute, inclinação, alcance nem potência de
 * transmissão. Enfiá-lo em {@link Radio} deixaria a metade desses campos sem
 * significado, e pior: ele passaria a aparecer no desenho de feixes, na
 * simulação de alcance e na lista de candidatos a associação, onde não tem o
 * que fazer. O que os dois de fato compartilham — estar num ponto, ter IP,
 * responder ou não — é pouco perto do que os separa.
 *
 * <h3>O que ele traz de próprio</h3>
 * Interfaces. É a razão de existir deste tipo: saber quais portas o
 * equipamento tem e quanto passa por cada uma. A lista vem do próprio
 * roteador na sondagem e fica guardada aqui para a tela ter o que mostrar
 * antes da primeira leitura da sessão.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Router {

    private String id = UUID.randomUUID().toString();
    private String name = "";
    private RouterVendor vendor = RouterVendor.MIKROTIK;
    private String host = "";
    private int sshPort = 22;
    private String sshUser = "";
    private String sshPassword = "";
    private String notes = "";

    private RadioStatus status = RadioStatus.UNKNOWN;
    private String lastError = "";
    private boolean monitored = true;

    /**
     * Interfaces vistas na última sondagem.
     *
     * Guardadas no projeto por conveniência de tela: abrir o programa e já ver
     * as portas do equipamento evita ter que sondar antes de poder escolher
     * qual monitorar. São cache, não verdade — o estado real vem da próxima
     * leitura.
     */
    private List<RouterInterface> interfaces = new ArrayList<>();

    public Router() {}

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }

    public String getName() { return name == null ? "" : name; }
    public void setName(String v) { this.name = v == null ? "" : v; }

    public RouterVendor getVendor() { return vendor == null ? RouterVendor.MIKROTIK : vendor; }
    public void setVendor(RouterVendor v) { this.vendor = v == null ? RouterVendor.MIKROTIK : v; }

    public String getHost() { return host == null ? "" : host; }
    public void setHost(String v) { this.host = v == null ? "" : v; }

    public int getSshPort() { return sshPort <= 0 ? 22 : sshPort; }
    public void setSshPort(int v) { this.sshPort = v; }

    public String getSshUser() { return sshUser == null ? "" : sshUser; }
    public void setSshUser(String v) { this.sshUser = v == null ? "" : v; }

    public String getSshPassword() { return sshPassword == null ? "" : sshPassword; }
    public void setSshPassword(String v) { this.sshPassword = v == null ? "" : v; }

    public String getNotes() { return notes == null ? "" : notes; }
    public void setNotes(String v) { this.notes = v == null ? "" : v; }

    public RadioStatus getStatus() { return status == null ? RadioStatus.UNKNOWN : status; }
    public void setStatus(RadioStatus v) { this.status = v == null ? RadioStatus.UNKNOWN : v; }

    public String getLastError() { return lastError == null ? "" : lastError; }
    public void setLastError(String v) { this.lastError = v == null ? "" : v; }

    public boolean isMonitored() { return monitored; }
    public void setMonitored(boolean v) { this.monitored = v; }

    public List<RouterInterface> getInterfaces() { return interfaces; }
    public void setInterfaces(List<RouterInterface> v) {
        this.interfaces = v == null ? new ArrayList<>() : v;
    }

    /** Nome para a tela: o nome dado, ou o IP enquanto não houver nome. */
    @JsonIgnore
    public String displayName() {
        return getName().isBlank() ? (getHost().isBlank() ? "roteador" : getHost()) : getName();
    }

    /** Dá para abrir SSH neste equipamento? */
    @JsonIgnore
    public boolean canProbeInterfaces() {
        return getVendor().speaksRouterOs() && !getHost().isBlank() && !getSshUser().isBlank();
    }

    @Override public String toString() { return displayName(); }
}
