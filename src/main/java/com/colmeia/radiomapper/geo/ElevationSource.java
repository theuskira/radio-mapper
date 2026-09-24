package com.colmeia.radiomapper.geo;

/**
 * De onde vem a altitude do terreno.
 *
 * Cada fonte responde em metros acima do nível do mar para uma coordenada de
 * mundo, ou {@code null} onde não tiver dado — o que é diferente de zero.
 * Devolver zero faria um ponto sem cobertura parecer estar na praia.
 */
public interface ElevationSource {

    /**
     * @return altitude em metros, ou {@code null} se esta fonte não cobre o
     *         ponto (ou ainda não tem o dado em mãos)
     */
    Double elevationAt(double worldX, double worldY);

    /**
     * Altura representativa de um quadrado de terreno, para desenho.
     *
     * {@link #elevationAt} responde pela célula exata, porque para obstrução
     * o que vale é o obstáculo daquele lugar preciso. Um vértice de malha 3D
     * é outra coisa: ele representa vários metros de lado, e ler um ponto só
     * custa caro duas vezes — herda os furos de amostragem da fonte (numa
     * nuvem de drone, onde caiu menos de um ponto por m² a célula fica vazia
     * mesmo com a área voada) e troca relevo por serrilhado.
     *
     * @param raioM metade do lado do quadrado, em metros
     * @return altura média do que houver no quadrado, ou {@code null} se não
     *         houver nada nele
     */
    default Double elevationOver(double worldX, double worldY, double raioM) {
        return elevationAt(worldX, worldY);
    }

    /** Nome curto para mostrar na interface e no log. */
    String sourceName();

    /** Resolução aproximada no terreno, em metros. 0 se desconhecida. */
    default double resolutionMeters() { return 0; }
}
