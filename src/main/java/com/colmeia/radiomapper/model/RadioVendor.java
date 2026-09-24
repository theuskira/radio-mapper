package com.colmeia.radiomapper.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

public enum RadioVendor {
    MIKROTIK_V6("Mikrotik RouterOS v6 (wireless)"),
    MIKROTIK_V7_WIFI("Mikrotik RouterOS v7 (wifiwave2)"),
    UBIQUITI_AIROS_6("Ubiquiti AirOS 6 (AirMAX M)"),
    UBIQUITI_AIROS_8("Ubiquiti AirOS 8 (AirMAX AC)"),
    @JsonEnumDefaultValue
    OTHER("Outro (somente ping de status)");

    private final String label;

    RadioVendor(String label) { this.label = label; }

    public String getLabel() { return label; }

    @Override
    public String toString() { return label; }
}
