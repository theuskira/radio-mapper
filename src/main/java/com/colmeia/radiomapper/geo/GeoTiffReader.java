package com.colmeia.radiomapper.geo;

import com.colmeia.radiomapper.util.Log;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lê o georreferenciamento embutido num GeoTIFF.
 *
 * O GeoTIFF é um TIFF comum com tags extras na IFD:
 * <ul>
 *   <li>33550 ModelPixelScale — tamanho do pixel no terreno</li>
 *   <li>33922 ModelTiepoint — amarra um pixel a uma coordenada</li>
 *   <li>34264 ModelTransformation — matriz 4x4, alternativa às duas acima</li>
 *   <li>34735 GeoKeyDirectory — diz em que sistema as coordenadas estão</li>
 * </ul>
 *
 * Lemos só os metadados: nenhum pixel é decodificado, então é barato mesmo
 * num arquivo de vários gigabytes.
 *
 * <h3>O que é suportado</h3>
 * Geográfico (graus), Web Mercator e UTM — que cobre praticamente todo
 * aerolevantamento brasileiro. CRS fora disso é recusado com mensagem clara,
 * em vez de posicionar a imagem no lugar errado silenciosamente.
 */
public final class GeoTiffReader {

    // Tags TIFF do GeoTIFF
    private static final int TAG_PIXEL_SCALE = 33550;
    private static final int TAG_TIEPOINT = 33922;
    private static final int TAG_TRANSFORM = 34264;
    private static final int TAG_GEO_KEYS = 34735;

    // Chaves dentro do GeoKeyDirectory
    private static final int KEY_MODEL_TYPE = 1024;
    private static final int KEY_GEOGRAPHIC_CRS = 2048;
    private static final int KEY_PROJECTED_CRS = 3072;

    private static final int MODEL_PROJECTED = 1;
    private static final int MODEL_GEOGRAPHIC = 2;

    private GeoTiffReader() {}

    /** Retângulo geográfico ocupado pela imagem, em graus. */
    public record Bounds(double minLon, double minLat, double maxLon, double maxLat,
                         String crs, String warning) {}

    /**
     * Motivo pelo qual não deu para georreferenciar.
     *
     * Carrega o código EPSG e os cantos em coordenadas do ARQUIVO quando eles
     * são conhecidos. Com isso a interface consegue oferecer ao usuário
     * escolher o sistema à mão, em vez de só recusar — o catálogo EPSG tem
     * milhares de códigos e enumerar todos seria enxugar gelo.
     */
    public static class NotGeoreferenced extends Exception {
        private final int epsg;
        private final double[] modelBounds;   // minX, minY, maxX, maxY

        public NotGeoreferenced(String msg) { this(msg, 0, null); }

        public NotGeoreferenced(String msg, int epsg, double[] modelBounds) {
            super(msg);
            this.epsg = epsg;
            this.modelBounds = modelBounds;
        }

        public int epsg() { return epsg; }
        public double[] modelBounds() { return modelBounds; }

        /**
         * Os números têm cara de UTM? Easting fica entre 100 mil e 1 milhão
         * por definição do sistema, e northing vai de 0 a 10 milhões.
         */
        public boolean looksLikeUtm() {
            if (modelBounds == null) return false;
            double minX = modelBounds[0], maxX = modelBounds[2];
            double minY = modelBounds[1], maxY = modelBounds[3];
            return minX > 100_000 && maxX < 1_000_000 && minY >= 0 && maxY <= 10_500_000;
        }
    }

    /**
     * @return os limites geográficos da imagem, ou vazio se o arquivo não tiver
     *         georreferenciamento algum (TIFF comum, PNG, JPG...)
     * @throws NotGeoreferenced quando HÁ georreferência mas não conseguimos usá-la
     */
    public static Optional<Bounds> read(File file) throws NotGeoreferenced {
        return read(file, null);
    }

    /**
     * @param utmOverride quando não-nulo, ignora o CRS declarado no arquivo e
     *                    interpreta as coordenadas como UTM no fuso informado.
     *                    É a saída para os milhares de códigos EPSG que não dá
     *                    para enumerar: o usuário diz qual é.
     */
    public static Optional<Bounds> read(File file, UtmOverride utmOverride)
            throws NotGeoreferenced {
        String name = file.getName().toLowerCase();
        if (!name.endsWith(".tif") && !name.endsWith(".tiff")) return Optional.empty();

        try (ImageInputStream iis = ImageIO.createImageInputStream(file)) {
            if (iis == null) return Optional.empty();
            Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
            if (!it.hasNext()) return Optional.empty();

            ImageReader reader = it.next();
            try {
                reader.setInput(iis, true, false);   // false: precisamos dos metadados
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                IIOMetadata meta = reader.getImageMetadata(0);
                if (meta == null) return Optional.empty();

                Map<Integer, double[]> fields = readFields(meta);
                if (fields.isEmpty()) return Optional.empty();

                double[] geoKeys = fields.get(TAG_GEO_KEYS);
                double[] transform = fields.get(TAG_TRANSFORM);
                double[] scale = fields.get(TAG_PIXEL_SCALE);
                double[] tiepoint = fields.get(TAG_TIEPOINT);

                boolean hasPlacement = transform != null
                        || (scale != null && scale.length >= 2 && tiepoint != null && tiepoint.length >= 6);
                if (!hasPlacement) {
                    if (geoKeys == null) return Optional.empty();   // TIFF sem georreferência
                    throw new NotGeoreferenced(
                            "O arquivo tem chaves GeoTIFF mas nao tem ModelTiepoint nem "
                            + "ModelTransformation, entao nao diz onde a imagem fica.");
                }

                // Cantos em coordenadas do CRS do arquivo
                double[] c00 = pixelToModel(0, 0, transform, scale, tiepoint);
                double[] cW0 = pixelToModel(width, 0, transform, scale, tiepoint);
                double[] c0H = pixelToModel(0, height, transform, scale, tiepoint);
                double[] cWH = pixelToModel(width, height, transform, scale, tiepoint);

                double[][] corners = { c00, cW0, c0H, cWH };
                double[] modelBounds = {
                        min(corners, 0), min(corners, 1), max(corners, 0), max(corners, 1) };

                Crs crs;
                if (utmOverride != null) {
                    crs = new Crs();
                    crs.utmZone = utmOverride.zone();
                    crs.utmSouth = utmOverride.south();
                    crs.epsg = 0;
                    crs.description = "UTM " + utmOverride.zone()
                            + (utmOverride.south() ? "S" : "N") + " (informado pelo usuário)";
                    crs.warning = "Sistema informado manualmente — confira se a imagem "
                            + "caiu no lugar certo.";
                } else {
                    try {
                        crs = resolveCrs(geoKeys);
                    } catch (NotGeoreferenced ex) {
                        // Reempacota com os números crus, para a interface poder
                        // oferecer a escolha manual do sistema.
                        throw new NotGeoreferenced(ex.getMessage(),
                                epsgOf(geoKeys), modelBounds);
                    }
                }

                double minLon = Double.MAX_VALUE, minLat = Double.MAX_VALUE;
                double maxLon = -Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
                for (double[] c : corners) {
                    double[] ll = crs.toLatLon(c[0], c[1]);
                    minLon = Math.min(minLon, ll[1]); maxLon = Math.max(maxLon, ll[1]);
                    minLat = Math.min(minLat, ll[0]); maxLat = Math.max(maxLat, ll[0]);
                }

                if (!sane(minLon, maxLon, -180, 180) || !sane(minLat, maxLat, -85.06, 85.06)) {
                    throw new NotGeoreferenced(String.format(
                            "As coordenadas lidas caem fora do planeta (lon %.3f..%.3f, "
                            + "lat %.3f..%.3f). O sistema do arquivo provavelmente nao e o "
                            + "que foi identificado (%s).",
                            minLon, maxLon, minLat, maxLat, crs.description),
                            crs.epsg, modelBounds);
                }

                Log.info("GeoTIFF %s: %s, lon %.6f..%.6f lat %.6f..%.6f",
                        file.getName(), crs.description, minLon, maxLon, minLat, maxLat);

                return Optional.of(new Bounds(minLon, minLat, maxLon, maxLat,
                                              crs.description, crs.warning));
            } finally {
                reader.dispose();
            }
        } catch (NotGeoreferenced ex) {
            throw ex;
        } catch (Exception ex) {
            throw new NotGeoreferenced("Falha ao ler os metadados do TIFF: " + ex.getMessage());
        }
    }

    /** Fuso UTM escolhido à mão, quando o EPSG do arquivo não é reconhecido. */
    public record UtmOverride(int zone, boolean south) {}

    private static double min(double[][] corners, int idx) {
        double m = Double.MAX_VALUE;
        for (double[] c : corners) m = Math.min(m, c[idx]);
        return m;
    }

    private static double max(double[][] corners, int idx) {
        double m = -Double.MAX_VALUE;
        for (double[] c : corners) m = Math.max(m, c[idx]);
        return m;
    }

    /** Só o código EPSG declarado, sem tentar interpretá-lo. */
    private static int epsgOf(double[] geoKeys) {
        if (geoKeys == null) return 0;
        Map<Integer, Integer> keys = parseGeoKeys(geoKeys);
        Integer proj = keys.get(KEY_PROJECTED_CRS);
        if (proj != null && proj != 0) return proj;
        Integer geog = keys.get(KEY_GEOGRAPHIC_CRS);
        return geog == null ? 0 : geog;
    }

    private static boolean sane(double min, double max, double lo, double hi) {
        return min >= lo && max <= hi && max > min;
    }

    /** Converte um pixel (coluna, linha) para a coordenada do CRS do arquivo. */
    private static double[] pixelToModel(double i, double j,
                                         double[] transform, double[] scale, double[] tiepoint) {
        if (transform != null && transform.length >= 16) {
            return new double[] {
                    transform[0] * i + transform[1] * j + transform[3],
                    transform[4] * i + transform[5] * j + transform[7]
            };
        }
        // tiepoint = (i, j, k, X, Y, Z): o pixel (i,j) corresponde a (X,Y).
        double pi = tiepoint[0], pj = tiepoint[1];
        double px = tiepoint[3], py = tiepoint[4];
        // Y do terreno cresce para o norte, a linha da imagem cresce para o sul.
        return new double[] { px + (i - pi) * scale[0], py - (j - pj) * scale[1] };
    }

    // ------------------------ CRS ------------------------

    private static final class Crs {
        int epsg;
        String description = "desconhecido";
        String warning;
        boolean geographic;
        int utmZone;
        boolean utmSouth;
        boolean webMercator;

        double[] toLatLon(double x, double y) {
            if (geographic) return new double[] { y, x };        // x=lon, y=lat
            if (webMercator) return new double[] { Mercator.yToLat(y), Mercator.xToLon(x) };
            return Utm.toLatLon(x, y, utmZone, utmSouth);
        }
    }

    private static Crs resolveCrs(double[] geoKeys) throws NotGeoreferenced {
        Crs crs = new Crs();
        if (geoKeys == null) {
            throw new NotGeoreferenced("O arquivo nao traz GeoKeyDirectory, entao nao diz "
                    + "em que sistema de coordenadas as posicoes estao.");
        }

        Map<Integer, Integer> keys = parseGeoKeys(geoKeys);
        int modelType = keys.getOrDefault(KEY_MODEL_TYPE, 0);

        if (modelType == MODEL_GEOGRAPHIC) {
            int code = keys.getOrDefault(KEY_GEOGRAPHIC_CRS, 4326);
            crs.geographic = true;
            crs.epsg = code;
            crs.description = "geografico EPSG:" + code + " (graus)";
            if (code != 4326 && code != 4674 && code != 4326) {
                crs.warning = "Datum EPSG:" + code + " tratado como WGS84.";
            }
            return crs;
        }

        if (modelType != MODEL_PROJECTED) {
            throw new NotGeoreferenced("Tipo de modelo GeoTIFF nao suportado (codigo "
                    + modelType + "). Suportamos geografico (graus), UTM e Web Mercator.");
        }

        int code = keys.getOrDefault(KEY_PROJECTED_CRS, 0);

        if (code == 3857 || code == 900913 || code == 3785) {
            crs.webMercator = true;
            crs.epsg = code;
            crs.description = "Web Mercator EPSG:" + code;
            return crs;
        }

        // Faixas UTM conhecidas. Cada familia e um datum diferente; o calculo
        // usa WGS84, entao so SAD69 merece aviso (ate ~60 m de deslocamento).
        Integer zone = null;
        Boolean south = null;
        String datum = null;

        if (code >= 32601 && code <= 32660) { zone = code - 32600; south = false; datum = "WGS84"; }
        else if (code >= 32701 && code <= 32760) { zone = code - 32700; south = true; datum = "WGS84"; }
        else if (code >= 31965 && code <= 31974) { zone = code - 31954; south = false; datum = "SIRGAS 2000"; }
        else if (code >= 31976 && code <= 31985) { zone = code - 31960; south = true; datum = "SIRGAS 2000"; }
        else if (code >= 29177 && code <= 29185) { zone = code - 29160; south = false; datum = "SAD69"; }
        else if (code >= 29187 && code <= 29195) { zone = code - 29170; south = true; datum = "SAD69"; }

        if (zone == null || !Utm.validZone(zone)) {
            throw new NotGeoreferenced("Sistema de coordenadas EPSG:" + code + " nao suportado. "
                    + "Suportamos geografico (graus), Web Mercator e UTM (WGS84, SIRGAS 2000 e SAD69). "
                    + "Converta o arquivo, ou posicione a imagem manualmente.");
        }

        crs.epsg = code;
        crs.utmZone = zone;
        crs.utmSouth = south;
        crs.description = "UTM " + zone + (south ? "S" : "N") + " " + datum + " (EPSG:" + code + ")";
        if ("SAD69".equals(datum)) {
            crs.warning = "Arquivo em SAD69: o calculo usa WGS84, entao pode haver "
                    + "deslocamento de algumas dezenas de metros. Ajuste fino na mao se precisar.";
        }
        return crs;
    }

    /**
     * O GeoKeyDirectory é um vetor de shorts em blocos de 4: os 4 primeiros são
     * cabeçalho, e cada bloco seguinte é (chave, local, tamanho, valor).
     * Só nos interessam as chaves com valor inline (local == 0).
     */
    private static Map<Integer, Integer> parseGeoKeys(double[] raw) {
        Map<Integer, Integer> out = new HashMap<>();
        if (raw.length < 8) return out;
        int count = (int) raw[3];
        for (int i = 0; i < count; i++) {
            int base = 4 + i * 4;
            if (base + 3 >= raw.length) break;
            int key = (int) raw[base];
            int location = (int) raw[base + 1];
            int value = (int) raw[base + 3];
            if (location == 0) out.put(key, value);
        }
        return out;
    }

    // ------------------------ Leitura dos metadados TIFF ------------------------

    /**
     * Extrai as tags que interessam da árvore de metadados nativos.
     *
     * Os valores viram double sempre, mesmo os inteiros: unifica o tratamento
     * de TIFFShort/TIFFLong/TIFFDouble, e nenhuma dessas tags tem inteiro
     * grande o bastante para perder precisão em double.
     */
    private static Map<Integer, double[]> readFields(IIOMetadata meta) {
        Map<Integer, double[]> out = new HashMap<>();
        String[] formats = meta.getMetadataFormatNames();
        for (String format : formats) {
            Node root;
            try {
                root = meta.getAsTree(format);
            } catch (Exception ex) {
                continue;
            }
            collectFields(root, out);
            if (!out.isEmpty()) break;
        }
        return out;
    }

    private static void collectFields(Node node, Map<Integer, double[]> out) {
        if (node == null) return;
        if ("TIFFField".equals(node.getNodeName()) && node.getAttributes() != null) {
            Node numAttr = node.getAttributes().getNamedItem("number");
            if (numAttr != null) {
                try {
                    int number = Integer.parseInt(numAttr.getNodeValue().trim());
                    if (number == TAG_PIXEL_SCALE || number == TAG_TIEPOINT
                            || number == TAG_TRANSFORM || number == TAG_GEO_KEYS) {
                        double[] vals = readValues(node);
                        if (vals.length > 0) out.put(number, vals);
                    }
                } catch (NumberFormatException ignored) { /* atributo estranho */ }
            }
        }
        NodeList kids = node.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) collectFields(kids.item(i), out);
    }

    /** Junta todos os atributos "value" descendentes, seja qual for o tipo. */
    private static double[] readValues(Node field) {
        List<Double> vals = new ArrayList<>();
        collectValues(field, vals);
        double[] out = new double[vals.size()];
        for (int i = 0; i < out.length; i++) out[i] = vals.get(i);
        return out;
    }

    private static void collectValues(Node node, List<Double> acc) {
        if (node.getAttributes() != null) {
            Node v = node.getAttributes().getNamedItem("value");
            if (v != null) {
                try {
                    acc.add(Double.valueOf(v.getNodeValue().trim()));
                } catch (NumberFormatException ignored) { /* ascii, por exemplo */ }
            }
        }
        NodeList kids = node.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) collectValues(kids.item(i), acc);
    }
}
