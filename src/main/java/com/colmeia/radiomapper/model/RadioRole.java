package com.colmeia.radiomapper.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * O que este rádio faz na rede.
 *
 * <h3>Por que o papel importa</h3>
 * Antes, um rádio era só "um rádio com um feixe". Mas AP e estação não são a
 * mesma coisa para nenhuma das contas que o programa faz:
 *
 * <ul>
 *   <li><b>Simulação de alcance.</b> Alcance só existe em relação a quem está
 *       do outro lado. Num ponto a ponto as duas pontas são iguais, e espelhar
 *       a antena é o palpite certo. Num setor servindo clientes, o outro lado
 *       é um CPE — antena bem menor, alcance bem menor. Sem o papel, o
 *       programa tinha que perguntar isso toda vez.</li>
 *   <li><b>Topologia.</b> Estação se associa a um AP; AP aceita várias
 *       estações. Saber quem é quem permite apontar um enlace planejado entre
 *       dois APs como provavelmente errado, e permite oferecer só os APs
 *       plausíveis quando se informa a associação de uma estação à mão.</li>
 *   <li><b>Sondagem.</b> {@link #MONITOR_ONLY} não tenta SSH nem procura
 *       vizinhos: só confirma que o IP responde.</li>
 * </ul>
 */
public enum RadioRole {

    PTP_AP("Ponto a ponto — AP"),
    PTP_STATION("Ponto a ponto — Estação"),
    AP("Ponto de acesso (multiponto)"),
    STATION("Estação"),

    /**
     * Não é rádio para efeito de topologia: existe só para saber se responde.
     *
     * Padrão ao desserializar um valor desconhecido, porque um papel que o
     * programa não entende não deve virar silenciosamente um AP e passar a
     * aparecer como candidato de associação.
     */
    @JsonEnumDefaultValue
    MONITOR_ONLY("Outro (somente ping)");

    /**
     * Ganho típico de CPE de 5 GHz, em dBi.
     *
     * Serve de palpite para a outra ponta quando ela não é conhecida. É a
     * ordem de grandeza de um cliente comum; quem souber o equipamento exato
     * corrige na tela da simulação.
     */
    private static final double GANHO_CPE_TIPICO = 19;

    /** Ganho típico de setorial de torre, em dBi. */
    private static final double GANHO_SETOR_TIPICO = 17;

    private final String label;

    RadioRole(String label) { this.label = label; }

    @Override public String toString() { return label; }

    /** Lado que aceita associações. */
    public boolean isAp() { return this == PTP_AP || this == AP; }

    /** Lado que se associa a um AP. */
    public boolean isStation() { return this == PTP_STATION || this == STATION; }

    /** Enlace dedicado entre duas pontas, em vez de um setor com vários clientes. */
    public boolean isPointToPoint() { return this == PTP_AP || this == PTP_STATION; }

    /** Só confirma que o IP responde — sem SSH, sem vizinhos, sem topologia. */
    public boolean monitorOnly() { return this == MONITOR_ONLY; }

    /** Entra na topologia da rede sem fio? */
    public boolean participatesInTopology() { return this != MONITOR_ONLY; }

    /**
     * Palpite de ganho para a antena do outro lado, usado como valor inicial
     * na simulação de alcance.
     *
     * @param ownGainDbi ganho deste rádio, usado quando as pontas são simétricas
     */
    public double peerGainGuessDbi(double ownGainDbi) {
        return switch (this) {
            // Ponto a ponto: par casado, quase sempre o mesmo modelo dos dois lados.
            case PTP_AP, PTP_STATION -> ownGainDbi;
            // Setor falando com cliente, e cliente falando com setor.
            case AP -> GANHO_CPE_TIPICO;
            case STATION -> GANHO_SETOR_TIPICO;
            case MONITOR_ONLY -> ownGainDbi;
        };
    }

    /** Frase curta explicando o palpite acima, para a tela não parecer mágica. */
    public String peerGuessNote() {
        return switch (this) {
            case PTP_AP, PTP_STATION -> "ponto a ponto: a outra ponta começa igual a esta";
            case AP -> "setor servindo clientes: a outra ponta começa como um CPE comum";
            case STATION -> "estação: a outra ponta começa como uma setorial de torre";
            case MONITOR_ONLY -> "papel sem topologia: a outra ponta começa igual a esta";
        };
    }
}
