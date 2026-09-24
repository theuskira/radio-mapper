package com.colmeia.radiomapper.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

public enum RadioStatus {
    @JsonEnumDefaultValue
    UNKNOWN,
    UP,
    DOWN
}
