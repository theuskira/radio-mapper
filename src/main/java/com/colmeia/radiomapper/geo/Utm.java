package com.colmeia.radiomapper.geo;

/**
 * Conversão UTM para latitude/longitude (fórmulas de Snyder, elipsoide WGS84).
 *
 * Existe porque GeoTIFF de aerolevantamento no Brasil quase sempre vem em UTM,
 * não em graus. Sem isto, a imagem só poderia ser posicionada à mão.
 *
 * <h3>Datum</h3>
 * Usa o elipsoide WGS84. SIRGAS 2000 é praticamente idêntico (diferença
 * abaixo de 1 m), então serve sem ressalva. SAD69 difere até ~60 m no Brasil:
 * a conta funciona, mas o deslocamento existe e quem chama avisa no log.
 */
public final class Utm {

    private static final double A = 6378137.0;              // semi-eixo maior WGS84
    private static final double F = 1 / 298.257223563;      // achatamento
    private static final double K0 = 0.9996;                // fator de escala UTM
    private static final double E2 = F * (2 - F);           // excentricidade ao quadrado
    private static final double E2P = E2 / (1 - E2);
    private static final double FALSE_EASTING = 500000.0;
    private static final double FALSE_NORTHING_SOUTH = 10000000.0;

    private Utm() {}

    /**
     * @param easting  coordenada X em metros
     * @param northing coordenada Y em metros
     * @param zone     fuso UTM (1..60)
     * @param south    true para hemisfério sul
     * @return {lat, lon} em graus
     */
    public static double[] toLatLon(double easting, double northing, int zone, boolean south) {
        double x = easting - FALSE_EASTING;
        double y = south ? northing - FALSE_NORTHING_SOUTH : northing;

        double e1 = (1 - Math.sqrt(1 - E2)) / (1 + Math.sqrt(1 - E2));
        double m = y / K0;
        double mu = m / (A * (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2 * E2 * E2 / 256));

        double e1_2 = e1 * e1, e1_3 = e1_2 * e1, e1_4 = e1_3 * e1;
        double phi1 = mu
                + (3 * e1 / 2 - 27 * e1_3 / 32) * Math.sin(2 * mu)
                + (21 * e1_2 / 16 - 55 * e1_4 / 32) * Math.sin(4 * mu)
                + (151 * e1_3 / 96) * Math.sin(6 * mu)
                + (1097 * e1_4 / 512) * Math.sin(8 * mu);

        double sinPhi1 = Math.sin(phi1);
        double cosPhi1 = Math.cos(phi1);
        double tanPhi1 = Math.tan(phi1);

        double c1 = E2P * cosPhi1 * cosPhi1;
        double t1 = tanPhi1 * tanPhi1;
        double n1 = A / Math.sqrt(1 - E2 * sinPhi1 * sinPhi1);
        double r1 = A * (1 - E2) / Math.pow(1 - E2 * sinPhi1 * sinPhi1, 1.5);
        double d = x / (n1 * K0);

        double d2 = d * d, d3 = d2 * d, d4 = d3 * d, d5 = d4 * d, d6 = d5 * d;

        double lat = phi1 - (n1 * tanPhi1 / r1) * (
                d2 / 2
                - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * E2P) * d4 / 24
                + (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * E2P - 3 * c1 * c1) * d6 / 720);

        double lon = (d
                - (1 + 2 * t1 + c1) * d3 / 6
                + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * E2P + 24 * t1 * t1) * d5 / 120) / cosPhi1;

        return new double[] { Math.toDegrees(lat), centralMeridian(zone) + Math.toDegrees(lon) };
    }

    /**
     * Caminho inverso: latitude/longitude para UTM.
     *
     * Necessário para consultar um levantamento que está em UTM a partir de
     * uma coordenada do mapa, que está em Mercator.
     *
     * @return {easting, northing} em metros
     */
    public static double[] fromLatLon(double latDeg, double lonDeg, int zone, boolean south) {
        double phi = Math.toRadians(latDeg);
        double lam = Math.toRadians(lonDeg);
        double lam0 = Math.toRadians(centralMeridian(zone));

        double sin = Math.sin(phi), cos = Math.cos(phi), tan = Math.tan(phi);
        double n = A / Math.sqrt(1 - E2 * sin * sin);
        double t = tan * tan;
        double c = E2P * cos * cos;
        double a1 = cos * (lam - lam0);

        double m = A * ((1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2 * E2 * E2 / 256) * phi
                - (3 * E2 / 8 + 3 * E2 * E2 / 32 + 45 * E2 * E2 * E2 / 1024) * Math.sin(2 * phi)
                + (15 * E2 * E2 / 256 + 45 * E2 * E2 * E2 / 1024) * Math.sin(4 * phi)
                - (35 * E2 * E2 * E2 / 3072) * Math.sin(6 * phi));

        double a2 = a1 * a1, a3 = a2 * a1, a4 = a3 * a1, a5 = a4 * a1, a6 = a5 * a1;

        double easting = K0 * n * (a1 + (1 - t + c) * a3 / 6
                + (5 - 18 * t + t * t + 72 * c - 58 * E2P) * a5 / 120) + FALSE_EASTING;

        double northing = K0 * (m + n * tan * (a2 / 2
                + (5 - t + 9 * c + 4 * c * c) * a4 / 24
                + (61 - 58 * t + t * t + 600 * c - 330 * E2P) * a6 / 720));
        if (south) northing += FALSE_NORTHING_SOUTH;

        return new double[] { easting, northing };
    }

    /** Fuso que contém a longitude informada. */
    public static int zoneFor(double lonDeg) {
        int z = (int) Math.floor((lonDeg + 180) / 6) + 1;
        return Math.max(1, Math.min(60, z));
    }

    /** Meridiano central do fuso, em graus. */
    public static double centralMeridian(int zone) {
        return (zone - 1) * 6 - 180 + 3;
    }

    public static boolean validZone(int zone) {
        return zone >= 1 && zone <= 60;
    }
}
