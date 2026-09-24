package com.colmeia.radiomapper.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * Como interpretar o número de altura da antena.
 *
 * As duas formas aparecem na prática e dão resultados muito diferentes:
 *
 * <ul>
 *   <li>{@link #ACIMA_DO_SOLO} — o que se mede com trena na torre: "a antena
 *       está a 30 m do chão". O programa soma a cota do terreno no ponto,
 *       então o topo depende de onde o ponto está no mapa.</li>
 *   <li>{@link #ABSOLUTA} — o que sai de um GPS, de um levantamento ou de uma
 *       planilha do cliente: "a antena está a 712 m". O número já é a cota
 *       final e o terreno NÃO entra na conta.</li>
 * </ul>
 *
 * Confundir os dois é o erro clássico: 712 lido como altura de mastro coloca a
 * antena 712 m acima de um morro que já tem 680, e o perfil passa a mostrar
 * visada livre onde não há. Daí a escolha ser explícita no cadastro.
 */
public enum AltitudeMode {

    /** Padrão: medida do solo até o centro da antena; somada à cota do terreno. */
    @JsonEnumDefaultValue
    ACIMA_DO_SOLO("Altura de instalação (somada ao solo)"),

    /** Cota do centro da antena acima do nível do mar; o solo não é somado. */
    ABSOLUTA("Altitude exata da antena (já inclui o solo)");

    private final String label;

    AltitudeMode(String label) { this.label = label; }

    @Override public String toString() { return label; }
}
