package com.colmeia.radiomapper.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Descobre em que sistema de coordenadas um levantamento .PLY está.
 *
 * <h3>Por que isto existe</h3>
 * O PLY não tem campo para sistema de coordenadas: o arquivo diz
 * {@code x=466415, y=8782370} e nada mais. Até aqui o usuário tinha que
 * escolher o fuso à mão, e errar joga o levantamento para outro lugar do
 * planeta — a altitude simplesmente some do mapa e não há sinal do porquê.
 *
 * Só que, na prática, quem gera a nuvem quase sempre <i>escreve</i> o
 * sistema em algum lugar. Dois lugares cobrem a maioria dos casos:
 *
 * <ul>
 *   <li><b>Comentários do próprio cabeçalho.</b> Linhas como
 *       {@code comment CRS SIRGAS 2000 / UTM zone 24S EPSG:31984} são
 *       comuns em saída de pipeline de fotogrametria.</li>
 *   <li><b>Um .json ao lado do arquivo</b>, com o EPSG do processamento —
 *       o relatório de georreferenciamento que acompanha a nuvem.</li>
 * </ul>
 *
 * O que sai daqui é sugestão, não decisão: o diálogo mostra o que foi
 * encontrado e de onde veio, e o usuário confirma. Detectar errado em
 * silêncio seria pior do que perguntar.
 */
public final class PlyGeoref {

    private PlyGeoref() {}

    /**
     * O que se descobriu sobre o arquivo.
     *
     * @param crs      como interpretar os X/Y
     * @param utmZone  fuso, só faz sentido com {@code crs == UTM}
     * @param south    hemisfério sul, idem
     * @param epsg     código EPSG encontrado, ou 0
     * @param evidence de onde veio a informação, em português, para mostrar
     *                 ao usuário — ele precisa poder conferir
     * @param warning  ressalva relevante (datum antigo, por exemplo), ou null
     */
    public record Detected(PlyElevation.Crs crs, int utmZone, boolean south,
                           int epsg, String evidence, String warning) {}

    /**
     * Procura a declaração de CRS no cabeçalho do PLY e num .json ao lado.
     *
     * @return o que foi encontrado, ou {@code null} se o arquivo não declara
     *         nada — aí o palpite pela ordem de grandeza dos números
     *         ({@link PlyElevation.Summary#guessCrs()}) continua valendo
     */
    public static Detected detect(File plyFile, PlyReader.Header header) {
        // O cabeçalho vem primeiro: é o próprio arquivo falando de si.
        if (header != null) {
            Detected d = fromText(String.join(" | ", header.comments()),
                    "declarado no cabeçalho do .PLY");
            if (d != null) return d;
        }
        return fromSidecar(plyFile);
    }

    // ------------------------ .json ao lado ------------------------

    /**
     * Relatório de georreferenciamento que acompanha a nuvem.
     *
     * Procura primeiro o .json de mesmo nome. Não achando, aceita um .json
     * único na pasta: o pipeline costuma gerar um relatório por voo e várias
     * nuvens dele (densa e esparsa, por exemplo). Com mais de um .json a
     * escolha seria adivinhação, então desiste.
     */
    private static Detected fromSidecar(File plyFile) {
        if (plyFile == null) return null;
        File dir = plyFile.getParentFile();
        if (dir == null) return null;

        String base = plyFile.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);

        File exact = new File(dir, base + ".json");
        if (exact.isFile()) return readSidecar(exact);

        File[] jsons = dir.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".json"));
        if (jsons != null && jsons.length == 1) return readSidecar(jsons[0]);
        return null;
    }

    private static Detected readSidecar(File json) {
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            int epsg = 0;
            for (String key : new String[] { "epsg", "EPSG", "srid", "epsg_code" }) {
                JsonNode n = root.get(key);
                if (n != null && n.isIntegralNumber()) { epsg = n.asInt(); break; }
                if (n != null && n.isTextual()) {
                    Matcher m = Pattern.compile("(\\d{4,6})").matcher(n.asText());
                    if (m.find()) { epsg = Integer.parseInt(m.group(1)); break; }
                }
            }
            String crsText = root.hasNonNull("crs") ? root.get("crs").asText() : "";

            String evidence = "declarado em " + json.getName();
            if (epsg > 0) {
                Detected d = fromEpsg(epsg, evidence);
                if (d != null) return d;
            }
            if (!crsText.isBlank()) return fromText(crsText, evidence);
        } catch (Exception ignored) {
            // Sidecar ilegível não é erro: só significa que não há dica aqui.
        }
        return null;
    }

    // ------------------------ Interpretação do texto ------------------------

    private static final Pattern EPSG = Pattern.compile("EPSG[:\\s]*(\\d{4,6})", Pattern.CASE_INSENSITIVE);
    private static final Pattern UTM_ZONE =
            Pattern.compile("UTM\\s*(?:zone|fuso)?\\s*(\\d{1,2})\\s*([NS])\\b", Pattern.CASE_INSENSITIVE);

    /** Lê um texto livre ("SIRGAS 2000 / UTM zone 24S EPSG:31984") procurando o CRS. */
    static Detected fromText(String text, String evidence) {
        if (text == null || text.isBlank()) return null;

        Matcher me = EPSG.matcher(text);
        if (me.find()) {
            Detected d = fromEpsg(Integer.parseInt(me.group(1)), evidence);
            if (d != null) return d;
        }

        Matcher mz = UTM_ZONE.matcher(text);
        if (mz.find()) {
            int zone = Integer.parseInt(mz.group(1));
            boolean south = mz.group(2).equalsIgnoreCase("S");
            if (Utm.validZone(zone)) {
                return new Detected(PlyElevation.Crs.UTM, zone, south, 0, evidence,
                        datumWarning(text));
            }
        }

        String low = text.toLowerCase();
        if (low.contains("pseudo-mercator") || low.contains("web mercator")) {
            return new Detected(PlyElevation.Crs.WEB_MERCATOR, 0, true, 0, evidence, null);
        }
        return null;
    }

    /**
     * Traduz o código EPSG para o que o programa precisa saber.
     *
     * Só os blocos que aparecem em levantamento brasileiro e nos genéricos
     * WGS84 — uma tabela completa de EPSG seria um banco de dados inteiro, e
     * o resto cai no palpite de sempre.
     */
    static Detected fromEpsg(int epsg, String evidence) {
        String label = "EPSG:" + epsg;
        String ev = evidence + " (" + label + ")";

        // SIRGAS 2000 / UTM: 31965..31976 = 11N..22N, 31977..31985 = 17S..25S
        if (epsg >= 31965 && epsg <= 31976) return utm(epsg - 31965 + 11, false, epsg, ev, null);
        if (epsg >= 31977 && epsg <= 31985) return utm(epsg - 31977 + 17, true, epsg, ev, null);

        // WGS84 / UTM: 326xx norte, 327xx sul, onde xx é o fuso.
        if (epsg >= 32601 && epsg <= 32660) return utm(epsg - 32600, false, epsg, ev, null);
        if (epsg >= 32701 && epsg <= 32760) return utm(epsg - 32700, true, epsg, ev, null);

        // SAD69 / UTM: 29168..29172 = 18N..22N, 29177..29185 = 17S..25S.
        // O elipsoide aqui não é o WGS84 que o programa usa nas contas.
        if (epsg >= 29168 && epsg <= 29172) return utm(epsg - 29168 + 18, false, epsg, ev, SAD69);
        if (epsg >= 29177 && epsg <= 29185) return utm(epsg - 29177 + 17, true, epsg, ev, SAD69);

        // Geográficos: SIRGAS 2000, WGS84, SAD69.
        if (epsg == 4674 || epsg == 4326) {
            return new Detected(PlyElevation.Crs.GEOGRAPHIC, 0, true, epsg, ev, null);
        }
        if (epsg == 4618) {
            return new Detected(PlyElevation.Crs.GEOGRAPHIC, 0, true, epsg, ev, SAD69);
        }

        if (epsg == 3857 || epsg == 3785 || epsg == 900913) {
            return new Detected(PlyElevation.Crs.WEB_MERCATOR, 0, true, epsg, ev, null);
        }
        return null;
    }

    private static final String SAD69 =
            "O levantamento está em SAD69. As contas do programa usam o elipsoide "
            + "WGS84, então o relevo pode aparecer deslocado em algumas dezenas de "
            + "metros em relação ao mapa.";

    private static Detected utm(int zone, boolean south, int epsg, String evidence, String warning) {
        return Utm.validZone(zone)
                ? new Detected(PlyElevation.Crs.UTM, zone, south, epsg, evidence, warning)
                : null;
    }

    private static String datumWarning(String text) {
        String low = text.toLowerCase();
        return (low.contains("sad69") || low.contains("sad 69")) ? SAD69 : null;
    }

    /** Descrição curta do que foi detectado, para log e para a interface. */
    public static String describe(Detected d) {
        if (d == null) return "não declarado";
        return switch (d.crs()) {
            case UTM -> "UTM fuso " + d.utmZone() + (d.south() ? "S" : "N");
            case GEOGRAPHIC -> "geográfico (graus)";
            case WEB_MERCATOR -> "Web Mercator";
        };
    }

    /** Só para o log: os comentários do cabeçalho em uma linha. */
    public static String headerHint(List<String> comments) {
        return comments == null || comments.isEmpty() ? "" : String.join(" | ", comments);
    }
}
