package com.colmeia.radiomapper.geo;

/**
 * Web Mercator (EPSG:3857) — a projeção que todos os provedores de tile usam.
 *
 * <h3>Espaço de coordenadas do mundo</h3>
 * O MapPane desenha num espaço cartesiano onde Y cresce para BAIXO (convenção
 * do JavaFX), enquanto o Mercator tem Y crescendo para o NORTE. Para não
 * espalhar sinais trocados pelo código, as conversões de/para o "mundo" ficam
 * todas aqui: {@link #worldX(double)} / {@link #worldY(double)} já entregam
 * coordenadas prontas para o MapPane, e {@link #lonOfWorldX(double)} /
 * {@link #latOfWorldY(double)} fazem o caminho de volta.
 *
 * Em modo mapa, 1 unidade de mundo = 1 metro de Mercator. Atenção: metro de
 * Mercator NÃO é metro no chão — a escala infla com o cosseno da latitude
 * (~1,16x no sul do Brasil). Para distâncias reais use {@link #groundMeters}.
 */
public final class Mercator {

    /** Raio da esfera usada pelo Web Mercator (WGS84 semi-eixo maior). */
    public static final double R = 6378137.0;

    /** Meia-largura do mundo em metros de Mercator: pi*R. */
    public static final double MAX = Math.PI * R;

    /** Largura total do mundo projetado. */
    public static final double WORLD = 2 * MAX;

    /** Lado do tile em pixels — 256 em todos os provedores que usamos. */
    public static final int TILE = 256;

    /** Latitude onde o Mercator estoura (o mundo vira um quadrado exato). */
    public static final double MAX_LAT = 85.05112878;

    private Mercator() {}

    // ------------------------ lat/lon <-> Mercator ------------------------

    public static double lonToX(double lon) {
        return Math.toRadians(lon) * R;
    }

    public static double latToY(double lat) {
        double phi = Math.toRadians(clampLat(lat));
        return R * Math.log(Math.tan(Math.PI / 4 + phi / 2));
    }

    public static double xToLon(double x) {
        return Math.toDegrees(x / R);
    }

    public static double yToLat(double y) {
        return Math.toDegrees(2 * Math.atan(Math.exp(y / R)) - Math.PI / 2);
    }

    public static double clampLat(double lat) {
        return Math.max(-MAX_LAT, Math.min(MAX_LAT, lat));
    }

    public static double clampLon(double lon) {
        return Math.max(-180.0, Math.min(180.0, lon));
    }

    // ------------------------ lat/lon <-> mundo do MapPane ------------------------

    public static double worldX(double lon) { return lonToX(lon); }

    /** Y do mundo cresce para o sul, por isso o sinal invertido. */
    public static double worldY(double lat) { return -latToY(lat); }

    public static double lonOfWorldX(double worldX) { return xToLon(worldX); }

    public static double latOfWorldY(double worldY) { return yToLat(-worldY); }

    // ------------------------ Tiles ------------------------

    /** Lado do tile, em metros de Mercator, no zoom informado. */
    public static double tileSpan(int z) {
        return WORLD / (1 << z);
    }

    /** Quantidade de tiles por eixo no zoom informado. */
    public static int tileCount(int z) {
        return 1 << z;
    }

    /** Canto noroeste do tile, em X do mundo. */
    public static double tileWorldX(int tx, int z) {
        return -MAX + tx * tileSpan(z);
    }

    /**
     * Canto noroeste do tile, em Y do mundo. Sai simétrico ao X porque o
     * mundo já está com Y para baixo, igual à numeração de linhas dos tiles.
     */
    public static double tileWorldY(int ty, int z) {
        return -MAX + ty * tileSpan(z);
    }

    /** Índice da coluna de tile que contém o X de mundo informado. */
    public static int tileXOf(double worldX, int z) {
        return (int) Math.floor((worldX + MAX) / tileSpan(z));
    }

    /** Índice da linha de tile que contém o Y de mundo informado. */
    public static int tileYOf(double worldY, int z) {
        return (int) Math.floor((worldY + MAX) / tileSpan(z));
    }

    /**
     * Zoom de tile cuja resolução nativa mais se aproxima da escala de tela
     * atual, evitando pedir tiles maiores (borrado) ou menores (desperdício)
     * que o necessário.
     *
     * @param pxPerWorldUnit escala do MapPane: pixels de tela por metro de Mercator
     */
    public static int zoomForScale(double pxPerWorldUnit, int minZoom, int maxZoom) {
        if (pxPerWorldUnit <= 0) return minZoom;
        // px/m no zoom z e TILE*2^z/WORLD  =>  2^z = px/m * WORLD / TILE
        double z = Math.log(pxPerWorldUnit * WORLD / TILE) / Math.log(2);
        long rounded = Math.round(z);
        return (int) Math.max(minZoom, Math.min(maxZoom, rounded));
    }

    /** Escala (px por metro de Mercator) que exibe o zoom informado em resolução nativa. */
    public static double scaleForZoom(int z) {
        return (double) TILE * (1 << z) / WORLD;
    }

    // ------------------------ Distâncias ------------------------

    /**
     * Fator para converter metros de Mercator em metros no chão, na latitude
     * informada. O Mercator estica tudo por 1/cos(lat).
     */
    public static double groundScaleAt(double lat) {
        return Math.cos(Math.toRadians(clampLat(lat)));
    }

    /** Distância aproximada no chão, em metros, entre dois pontos do mundo. */
    public static double groundMeters(double wx1, double wy1, double wx2, double wy2) {
        double latMid = latOfWorldY((wy1 + wy2) / 2);
        double dx = wx2 - wx1;
        double dy = wy2 - wy1;
        return Math.hypot(dx, dy) * groundScaleAt(latMid);
    }
}
