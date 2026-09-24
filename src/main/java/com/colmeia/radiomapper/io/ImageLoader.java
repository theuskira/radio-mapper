package com.colmeia.radiomapper.io;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.spi.IIORegistry;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;

public final class ImageLoader {

    static {
        IIORegistry.getDefaultInstance().registerApplicationClasspathSpis();
    }

    /**
     * Maior lado (em pixels) da imagem efetivamente carregada em memória.
     * Imagens maiores são subamostradas NA LEITURA — nunca chegamos a alocar
     * o bitmap em resolução total. Um GeoTIFF de 25600x32768 (838 MP) geraria
     * ~3,35 GB por cópia; a decodificação + SwingFXUtils.toFXImage faziam DUAS
     * cópias (>7 GB), travando a aplicação. Com o teto abaixo ele cai para
     * ~6400x8192 (~52 MP), continuando nítido o bastante para um mapa de fundo.
     * As coordenadas dos pontos permanecem em pixels da imagem ORIGINAL: o
     * MapPane estica o ImageView de volta ao tamanho lógico (worldWidth/Height).
     */
    public static final int MAX_DISPLAY_DIM = 8192;

    /**
     * Op\u00e7\u00f5es de qualidade oferecidas ao usu\u00e1rio.
     *
     * O n\u00famero \u00e9 o maior lado, em pixels, da imagem que fica na
     * mem\u00f3ria. Cada degrau dobra o lado e portanto QUADRUPLICA a
     * mem\u00f3ria: um voo de 25600x32768 ocupa ~200 MB a 4096 e ~800 MB a
     * 8192. Com v\u00e1rias ortofotos sobrepostas isso soma, e \u00e9 por
     * isso que virou escolha em vez de constante.
     */
    public enum Qualidade {
        RASCUNHO("Rascunho (r\u00e1pido)", 2048),
        MEDIA("M\u00e9dia", 4096),
        ALTA("Alta", 8192),
        MAXIMA("M\u00e1xima (pesado)", 16384);

        private final String rotulo;
        private final int lado;

        Qualidade(String rotulo, int lado) { this.rotulo = rotulo; this.lado = lado; }

        public int lado() { return lado; }

        @Override public String toString() { return rotulo; }
    }

    private ImageLoader() {}

    /**
     * Imagem já pronta para exibição, junto com as dimensões LÓGICAS (da imagem
     * original em disco), que definem o espaço de coordenadas do mundo.
     */
    public record Loaded(Image image, double worldWidth, double worldHeight) {}

    public static Loaded load(File file) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(file)) {
            if (iis == null) throw new IOException("Não foi possível abrir a imagem: " + file);
            Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
            if (!it.hasNext()) throw new IOException("Formato de imagem não suportado: " + file);
            ImageReader reader = it.next();
            try {
                reader.setInput(iis, true, true);
                int fullW = reader.getWidth(0);
                int fullH = reader.getHeight(0);
                int sub = subsamplingFor(fullW, fullH);

                ImageReadParam param = reader.getDefaultReadParam();
                if (sub > 1) param.setSourceSubsampling(sub, sub, 0, 0);

                BufferedImage bi = reader.read(0, param);
                if (bi == null) throw new IOException("Falha ao decodificar a imagem: " + file);
                Image fx = SwingFXUtils.toFXImage(bi, null);
                return new Loaded(fx, fullW, fullH);
            } finally {
                reader.dispose();
            }
        }
    }

    /** Fator de subamostragem para manter o maior lado dentro do teto escolhido. */
    private static int subsamplingFor(int w, int h) {
        int teto = com.colmeia.radiomapper.util.Settings.imageQuality().lado();
        int max = Math.max(w, h);
        int sub = 1;
        while (max / sub > teto) sub++;
        return sub;
    }
}
