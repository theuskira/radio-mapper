package com.colmeia.radiomapper.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Radio {
    private String id = UUID.randomUUID().toString();
    private String name = "";
    private RadioVendor vendor = RadioVendor.MIKROTIK_V6;

    /**
     * O que este radio faz na rede. Ver {@link RadioRole}.
     *
     * Projeto antigo nao tem o campo e cai em AP: e o papel que mais aparece
     * em cadastro existente e o que menos muda comportamento — continua
     * sondando e continua aceitando associacoes, como antes.
     */
    private RadioRole role = RadioRole.AP;

    /**
     * AP em que esta estacao esta associada, informado a mao.
     *
     * Existe porque nem todo equipamento entrega a tabela de registro: ha
     * firmware que nao expoe, radio sem SSH liberado, e marca que o programa
     * nem sonda. Nesses casos a associacao e real e so o operador sabe dela.
     * Vazio = ninguem informou, e ai vale o que a descoberta encontrar.
     */
    private String uplinkRadioId = "";
    private String host = "";
    private int sshPort = 22;
    private String sshUser = "";
    private String sshPassword = "";
    private String mac = "";
    private String notes = "";
    private RadioStatus status = RadioStatus.UNKNOWN;
    private String lastError = "";

    /**
     * Se este rádio entra nas notificações por e-mail de queda.
     *
     * Padrão true: ao ligar as notificações, o esperado é vigiar a rede
     * inteira. Quem tem rádio de teste ou cliente que cai direito desmarca
     * pontualmente em Ferramentas &gt; Rádios monitorados.
     */
    private boolean monitored = true;

    // Feixe da antena. Convenção compass: 0° = norte (visualmente para cima),
    // 90° = leste, sentido horário. width = abertura horizontal em graus
    // (360 = omni). distance é em pixels do mapa (mesmas unidades de x/y do
    // ponto). beamVisible permite ocultar um feixe específico mesmo com o
    // toggle global ligado.
    // Ângulos em double: abertura de setorial raramente é inteira — 5,5° e
    // 7,5° são valores de catálogo, e arredondar distorce o perfil vertical.
    private double beamAzimuthDeg = 0;
    private double beamWidthDeg = 0;

    /**
     * Plano vertical do feixe.
     *
     * {@code beamTiltDeg} é a inclinação do eixo: negativo aponta para baixo
     * (downtilt), que é o caso comum de setorial de torre; positivo aponta para
     * cima. {@code beamVerticalWidthDeg} é a abertura vertical (HPBW), quase
     * sempre bem menor que a horizontal numa antena setorial.
     *
     * {@code antennaHeightM} é a altura do centro da antena acima do solo — sem
     * ela não dá para cruzar o feixe com o relevo.
     */
    private double beamTiltDeg = 0;
    private double beamVerticalWidthDeg = 0;
    private double antennaHeightM = 0;

    /**
     * O que {@code antennaHeightM} significa: altura acima do solo (padrao) ou
     * cota absoluta. Ver {@link AltitudeMode}.
     *
     * Projetos salvos antes deste campo existir nao o trazem, e cai no padrao
     * ACIMA_DO_SOLO — que e como o numero sempre foi interpretado ate aqui,
     * entao nada muda de lugar ao abrir um projeto antigo.
     */
    private AltitudeMode altitudeMode = AltitudeMode.ACIMA_DO_SOLO;

    // ---- RF: o necessário para estimar sinal (link budget) ----
    /** Ganho da antena em dBi. 0 = não informado. */
    private double antennaGainDbi = 0;
    /** Potência de transmissão em dBm (na saída do rádio, antes do cabo). */
    private double txPowerDbm = 0;
    /** Frequência de operação em MHz. 0 = não informada. */
    private double frequencyMhz = 0;
    /** Perda de cabo/conectores entre rádio e antena, em dB. */
    private double cableLossDb = 0;
    /**
     * Alcance do feixe em METROS NO CHÃO.
     *
     * Metro de chão não é unidade de mundo em nenhum dos dois modos: em modo
     * mapa o mundo está em metros de Mercator, que esticam com 1/cos(latitude)
     * (~9% no sul do Brasil); em modo imagem o mundo está em pixels. Quem
     * desenha converte — ver {@code MapPane.groundMetersToWorld}.
     */
    @com.fasterxml.jackson.annotation.JsonAlias("beamDistance")
    private double beamRangeM = 0;
    private boolean beamVisible = true;
    /** Hex (#rrggbb) ou vazio para usar a cor padrão do status (UP/DOWN/UNKNOWN). */
    private String beamColor = "";
    /** Opacidade do preenchimento (0..1). 0.25 fica visível sem cobrir o mapa. */
    private double beamOpacity = 0.25;

    public Radio() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public RadioVendor getVendor() { return vendor; }
    public void setVendor(RadioVendor vendor) { this.vendor = vendor; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getSshPort() { return sshPort; }
    public void setSshPort(int sshPort) { this.sshPort = sshPort; }

    public String getSshUser() { return sshUser; }
    public void setSshUser(String sshUser) { this.sshUser = sshUser; }

    public String getSshPassword() { return sshPassword; }
    public void setSshPassword(String sshPassword) { this.sshPassword = sshPassword; }

    public String getMac() { return mac; }
    public void setMac(String mac) { this.mac = mac == null ? "" : mac.toLowerCase(); }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public RadioStatus getStatus() { return status == null ? RadioStatus.UNKNOWN : status; }
    public void setStatus(RadioStatus status) { this.status = status; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError == null ? "" : lastError; }

    public boolean isMonitored() { return monitored; }
    public void setMonitored(boolean monitored) { this.monitored = monitored; }

    public double getBeamAzimuthDeg() { return ((beamAzimuthDeg % 360) + 360) % 360; }
    public void setBeamAzimuthDeg(double v) { this.beamAzimuthDeg = v; }

    public double getBeamWidthDeg() { return Math.max(0, Math.min(360, beamWidthDeg)); }
    public void setBeamWidthDeg(double v) { this.beamWidthDeg = v; }

    /** Inclinação do eixo do feixe: negativo = downtilt. */
    public double getBeamTiltDeg() { return Math.max(-90, Math.min(90, beamTiltDeg)); }
    public void setBeamTiltDeg(double v) { this.beamTiltDeg = v; }

    /** Abertura vertical do feixe, em graus. 0 = não especificada. */
    public double getBeamVerticalWidthDeg() { return Math.max(0, Math.min(180, beamVerticalWidthDeg)); }
    public void setBeamVerticalWidthDeg(double v) { this.beamVerticalWidthDeg = v; }

    /**
     * O numero informado no cadastro, em metros. O SIGNIFICADO depende de
     * {@link #getAltitudeMode()}: altura acima do solo ou cota absoluta.
     *
     * Quem precisa do topo da antena deve chamar {@link #antennaTopM(double)}
     * em vez de somar por conta propria — e o que garante que os dois modos
     * sejam tratados certo em todo lugar.
     */
    public double getAntennaHeightM() {
        // O piso em zero vale so para altura de mastro: cota absoluta negativa
        // e rara mas possivel, e zerar silenciosamente seria mentir.
        return altitudeMode == AltitudeMode.ABSOLUTA ? antennaHeightM : Math.max(0, antennaHeightM);
    }
    public void setAntennaHeightM(double v) { this.antennaHeightM = v; }

    public RadioRole getRole() { return role == null ? RadioRole.AP : role; }
    public void setRole(RadioRole r) { this.role = r == null ? RadioRole.AP : r; }

    public String getUplinkRadioId() { return uplinkRadioId == null ? "" : uplinkRadioId; }
    public void setUplinkRadioId(String v) { this.uplinkRadioId = v == null ? "" : v; }

    /** Tem associacao informada a mao? */
    @JsonIgnore
    public boolean hasManualUplink() { return !getUplinkRadioId().isBlank(); }

    public AltitudeMode getAltitudeMode() {
        return altitudeMode == null ? AltitudeMode.ACIMA_DO_SOLO : altitudeMode;
    }
    public void setAltitudeMode(AltitudeMode m) {
        this.altitudeMode = m == null ? AltitudeMode.ACIMA_DO_SOLO : m;
    }

    /** {@code true} se o numero informado ja e a cota final da antena. */
    public boolean isAbsoluteAltitude() { return getAltitudeMode() == AltitudeMode.ABSOLUTA; }

    /**
     * Cota do centro da antena, em metros acima do nivel do mar.
     *
     * @param groundM altitude do terreno sob o ponto. Ignorada no modo
     *                absoluto — inclusive se vier NaN, porque nesse modo o
     *                perfil continua valendo sem dado de relevo na ponta.
     */
    public double antennaTopM(double groundM) {
        return isAbsoluteAltitude() ? getAntennaHeightM() : groundM + getAntennaHeightM();
    }

    /** Comprimento aparente do mastro: quanto a antena esta acima do solo. */
    public double heightAboveGroundM(double groundM) {
        return isAbsoluteAltitude() ? getAntennaHeightM() - groundM : getAntennaHeightM();
    }

    /** Tem plano vertical definido o bastante para desenhar um perfil? */
    public boolean hasVerticalBeam() { return getBeamVerticalWidthDeg() > 0; }

    public double getAntennaGainDbi() { return antennaGainDbi; }
    public void setAntennaGainDbi(double v) { this.antennaGainDbi = v; }

    public double getTxPowerDbm() { return txPowerDbm; }
    public void setTxPowerDbm(double v) { this.txPowerDbm = v; }

    public double getFrequencyMhz() { return Math.max(0, frequencyMhz); }
    public void setFrequencyMhz(double v) { this.frequencyMhz = v; }

    public double getCableLossDb() { return Math.max(0, cableLossDb); }
    public void setCableLossDb(double v) { this.cableLossDb = v; }

    /** Tem dados suficientes para entrar num cálculo de link budget? */
    public boolean hasRfData() {
        return getFrequencyMhz() > 0 && antennaGainDbi != 0 && txPowerDbm != 0;
    }

    /** Alcance do feixe em metros no chão. */
    public double getBeamRangeM() { return Math.max(0, beamRangeM); }
    public void setBeamRangeM(double v) { this.beamRangeM = v; }

    public boolean isBeamVisible() { return beamVisible; }
    public void setBeamVisible(boolean beamVisible) { this.beamVisible = beamVisible; }

    public String getBeamColor() { return beamColor == null ? "" : beamColor; }
    public void setBeamColor(String beamColor) { this.beamColor = beamColor == null ? "" : beamColor; }

    public double getBeamOpacity() {
        // Clamp para a faixa válida e trata 0 negativo herdado de projetos
        // antigos como o default 0.25 (0 explícito vira invisível, ok).
        if (beamOpacity < 0) return 0.25;
        if (beamOpacity > 1) return 1.0;
        return beamOpacity;
    }
    public void setBeamOpacity(double beamOpacity) { this.beamOpacity = beamOpacity; }

    public boolean hasBeam() { return getBeamWidthDeg() > 0 && getBeamRangeM() > 0; }

    @Override
    public String toString() {
        return (name == null || name.isBlank() ? host : name) + " (" + vendor + ")";
    }
}
