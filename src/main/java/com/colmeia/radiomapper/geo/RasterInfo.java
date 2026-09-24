package com.colmeia.radiomapper.geo;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.Raster;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;

/**
 * Diz o que um arquivo raster é de verdade: foto aérea ou modelo de elevação.
 *
 * A distinção importa porque só um deles tem altitude. Uma ortofoto traz três
 * bandas de cor (8 bits cada) e nenhum dado de terreno; um MDT traz uma banda
 * só, em 16 bits ou ponto flutuante, com metros acima do nível do mar.
 * Olhando o arquivo no Windows os dois são "um TIFF" — daí a necessidade disto.
 *
 * <h3>Por que lê o raster e não a imagem</h3>
 * {@code ImageIO.read()} converte tudo para uma imagem exibível, normalizando
 * os valores e destruindo justamente a altitude. {@code readRaster()} devolve
 * as amostras cruas, que é o que precisamos medir.
 */
public final class RasterInfo {

    /** Valores que costumam significar "sem dado" em MDT. */
    private static final double[] COMMON_NODATA = { -9999, -32768, -32767, 32767, -99999, -3.4028235E38 };

    private RasterInfo() {}

    public record Result(
            int width, int height, int bands, int bitDepth,
            boolean floatingPoint, boolean signed,
            double min, double max, long sampled, long nodataCount,
            boolean georeferenced, String crs,
            Kind kind, String explanation) {}

    public enum Kind {
        ELEVATION("Modelo de elevação (tem altitude)"),
        PHOTO("Foto aérea / ortofoto (não tem altitude)"),
        GRAYSCALE("Imagem em tons de cinza"),
        UNKNOWN("Não identificado");

        private final String label;
        Kind(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    /** Analisa o arquivo sem carregá-lo inteiro: amostra no máximo ~250 mil pixels. */
    public static Result inspect(File file) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(file)) {
            if (iis == null) throw new IOException("Não consegui abrir o arquivo.");
            Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
            if (!it.hasNext()) throw new IOException("Formato não reconhecido por nenhum leitor.");

            ImageReader reader = it.next();
            try {
                reader.setInput(iis, true, false);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);

                // Subamostra para a leitura ser barata mesmo num arquivo enorme.
                int step = Math.max(1, (int) Math.ceil(Math.sqrt((double) w * h / 250_000)));
                ImageReadParam param = reader.getDefaultReadParam();
                if (step > 1) param.setSourceSubsampling(step, step, 0, 0);

                Raster raster = reader.readRaster(0, param);
                int bands = raster.getNumBands();
                var sm = raster.getSampleModel();
                int bitDepth = sm.getSampleSize(0);
                int dataType = sm.getDataType();
                boolean floating = dataType == java.awt.image.DataBuffer.TYPE_FLOAT
                        || dataType == java.awt.image.DataBuffer.TYPE_DOUBLE;
                boolean signed = dataType == java.awt.image.DataBuffer.TYPE_SHORT || floating;

                double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
                long sampled = 0, nodata = 0;
                int rw = raster.getWidth(), rh = raster.getHeight();
                for (int y = 0; y < rh; y++) {
                    for (int x = 0; x < rw; x++) {
                        double v = raster.getSampleDouble(raster.getMinX() + x,
                                                          raster.getMinY() + y, 0);
                        if (Double.isNaN(v) || isNoData(v)) { nodata++; continue; }
                        min = Math.min(min, v);
                        max = Math.max(max, v);
                        sampled++;
                    }
                }
                if (sampled == 0) { min = 0; max = 0; }

                boolean geo = false;
                String crs = "sem georreferência";
                try {
                    var b = GeoTiffReader.read(file);
                    if (b.isPresent()) { geo = true; crs = b.get().crs(); }
                } catch (GeoTiffReader.NotGeoreferenced ex) {
                    crs = "georreferência presente mas não utilizável: " + ex.getMessage();
                    // Sem isto a explicação só aparecia no diálogo e sumia ao
                    // fechá-lo, sem deixar rastro para diagnosticar depois.
                    com.colmeia.radiomapper.util.Log.warn(
                            "Analise de %s: georreferencia nao utilizavel (EPSG=%d) — %s",
                            file.getName(), ex.epsg(), ex.getMessage());
                }

                Kind kind = classify(bands, bitDepth, floating, min, max);
                return new Result(w, h, bands, bitDepth, floating, signed,
                        min, max, sampled, nodata, geo, crs, kind,
                        explain(kind, bands, bitDepth, floating, min, max));
            } finally {
                reader.dispose();
            }
        }
    }

    private static boolean isNoData(double v) {
        for (double nd : COMMON_NODATA) {
            if (Math.abs(v - nd) < 0.001) return true;
        }
        return false;
    }

    private static Kind classify(int bands, int bitDepth, boolean floating, double min, double max) {
        if (bands >= 3) return Kind.PHOTO;
        if (bands == 1) {
            // Altitude plausivel na Terra: do mar Morto ao Everest, com folga.
            boolean plausivel = min > -600 && max < 9000 && max > min;
            if ((floating || bitDepth >= 16) && plausivel) return Kind.ELEVATION;
            if (bitDepth <= 8) return Kind.GRAYSCALE;
            return plausivel ? Kind.ELEVATION : Kind.UNKNOWN;
        }
        return Kind.UNKNOWN;
    }

    private static String explain(Kind kind, int bands, int bitDepth, boolean floating,
                                  double min, double max) {
        return switch (kind) {
            case PHOTO -> bands + " bandas de cor, " + bitDepth + " bits cada. "
                    + "Isto é uma imagem: serve de fundo visual, mas não contém altitude. "
                    + "Para o perfil de relevo você precisa de um MDT separado.";
            case ELEVATION -> "Uma banda, " + (floating ? "ponto flutuante" : bitDepth + " bits")
                    + ", valores de " + Math.round(min) + " a " + Math.round(max) + ". "
                    + "A faixa é compatível com altitude em metros.";
            case GRAYSCALE -> "Uma banda de " + bitDepth + " bits, valores de "
                    + Math.round(min) + " a " + Math.round(max) + ". "
                    + "Faixa típica de imagem em tons de cinza, não de altitude em metros. "
                    + "Se for um MDT, ele foi convertido para 8 bits e perdeu a escala real.";
            case UNKNOWN -> "Uma banda com valores de " + Math.round(min) + " a " + Math.round(max)
                    + ", fora da faixa esperada para altitude. Pode ser um MDT em outra "
                    + "unidade (pés?) ou um dado de outro tipo.";
        };
    }
}
