package com.colmeia.radiomapper.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * De onde veio este enlace — e, por consequência, quem manda nele.
 *
 * A origem decide o ciclo de vida. Um enlace descoberto existe enquanto os
 * rádios o confirmam, e a sincronização o remove quando somem. Os outros dois
 * foram afirmados por uma pessoa, e apagá-los por falta de confirmação
 * destruiria justamente a informação que o programa não tinha como obter
 * sozinho.
 */
public enum LinkOrigin {

    /** Os próprios rádios reportaram o vizinho. É o único que a sincronização remove. */
    @JsonEnumDefaultValue
    DISCOVERED("descoberto"),

    /** Desenhado para planejar: ainda não existe nada no ar. */
    PLANNED("planejado"),

    /**
     * O usuário informou que a associação existe.
     *
     * É o caso de firmware que não expõe a tabela de registro, de rádio sem
     * acesso SSH, ou de equipamento de outro fabricante no meio do caminho: a
     * conexão é real, o programa é que não consegue enxergá-la.
     */
    MANUAL("informado à mão");

    private final String label;

    LinkOrigin(String label) { this.label = label; }

    @Override public String toString() { return label; }

    /** Afirmado por uma pessoa: a descoberta não pode apagar. */
    public boolean isUserDefined() { return this != DISCOVERED; }
}
