package com.colmeia.radiomapper.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/** Com que equipamento estamos falando — e portanto em que língua. */
public enum RouterVendor {

    /**
     * RouterOS, v6 ou v7.
     *
     * Os comandos que interessam aqui — listar interfaces e ler contadores —
     * têm a mesma sintaxe nas duas versões, então não vale separar como foi
     * preciso no wireless, onde v6 e v7 divergem de verdade.
     */
    @JsonEnumDefaultValue
    MIKROTIK("Mikrotik RouterOS (v6 ou v7)"),

    /** Sem SSH: só confirma se o IP responde. */
    OTHER("Outro (somente ping)");

    private final String label;

    RouterVendor(String label) { this.label = label; }

    /** Aceita os comandos do RouterOS? */
    public boolean speaksRouterOs() { return this == MIKROTIK; }

    @Override public String toString() { return label; }
}
