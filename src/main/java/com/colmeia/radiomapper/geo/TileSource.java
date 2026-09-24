package com.colmeia.radiomapper.geo;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * Provedores de mapa base.
 *
 * Todos são serviços de terceiros com política de uso própria. As regras que
 * respeitamos no código:
 *
 * <ul>
 *   <li>User-Agent identificando o app (exigido pelo OSM; sem isso o servidor
 *       devolve 403) — ver {@link TileCache}.</li>
 *   <li>Poucas conexões simultâneas e cache em disco, para não repetir
 *       download do mesmo tile.</li>
 *   <li>Atribuição visível no mapa — o MapPane mostra {@link #attribution()}
 *       no canto inferior.</li>
 * </ul>
 *
 * O template aceita {z}, {x} e {y}; a ordem varia por provedor (o Esri usa
 * z/y/x), por isso a substituição é por nome e não posicional.
 */
public enum TileSource {

    /** Sem mapa base: o mundo volta a ser o espaço de pixels da imagem. */
    @JsonEnumDefaultValue
    NONE("Nenhum (somente imagem)", null, 0, 0, ""),

    OSM("OpenStreetMap",
        "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        0, 19,
        "© OpenStreetMap contributors"),

    SATELLITE("Satélite",
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        0, 19,
        "Imagery © Esri, Maxar, Earthstar Geographics"),

    TERRAIN("Relevo",
        "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
        0, 17,
        "© OpenTopoMap (CC-BY-SA), © OpenStreetMap contributors");

    private final String label;
    private final String urlTemplate;
    private final int minZoom;
    private final int maxZoom;
    private final String attribution;

    TileSource(String label, String urlTemplate, int minZoom, int maxZoom, String attribution) {
        this.label = label;
        this.urlTemplate = urlTemplate;
        this.minZoom = minZoom;
        this.maxZoom = maxZoom;
        this.attribution = attribution;
    }

    public String label() { return label; }
    public int minZoom() { return minZoom; }
    public int maxZoom() { return maxZoom; }
    public String attribution() { return attribution; }

    /** {@code true} quando há tiles para buscar (ou seja, tudo menos NONE). */
    public boolean isMap() { return urlTemplate != null; }

    public String url(int z, int x, int y) {
        if (urlTemplate == null) return null;
        return urlTemplate
                .replace("{z}", Integer.toString(z))
                .replace("{x}", Integer.toString(x))
                .replace("{y}", Integer.toString(y));
    }

    /** Nome de pasta usado no cache em disco. */
    public String cacheDir() { return name().toLowerCase(); }

    /** Converte nome guardado em preferência, caindo no padrão se for lixo. */
    public static TileSource parse(String name, TileSource fallback) {
        if (name == null || name.isBlank()) return fallback;
        try {
            return valueOf(name.trim());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    @Override
    public String toString() { return label; }
}
