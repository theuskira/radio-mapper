package com.colmeia.radiomapper.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Como a imagem de fundo se posiciona SOBRE o mapa base.
 *
 * Só tem sentido em modo mapa. Sem mapa base, a imagem é o próprio mundo e
 * ocupa de (0,0) até (largura, altura) — não há o que posicionar.
 *
 * As coordenadas são metros de Web Mercator, iguais às dos pontos. Guardar em
 * metros (e não em lat/lon dos cantos) mantém o retângulo alinhado aos eixos
 * da projeção, que é como o desenho acontece; converter para graus só na hora
 * de mostrar ao usuário evita erro de arredondamento acumulado a cada arrasto.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImageOverlay {

    /** Canto noroeste, em coordenadas de mundo. */
    private double x;
    private double y;
    /** Tamanho em metros de Mercator. */
    private double width;
    private double height;

    /** 0 = invisível, 1 = opaca. O padrão deixa o mapa aparecer por baixo. */
    private double opacity = 0.7;

    private boolean visible = true;

    /**
     * Travada contra arrasto acidental. Depois de encaixar a imagem no lugar
     * certo, um clique distraído não deveria desfazer o trabalho.
     */
    private boolean locked;

    public double getX() { return x; }
    public void setX(double x) { this.x = x; }

    public double getY() { return y; }
    public void setY(double y) { this.y = y; }

    public double getWidth() { return width; }
    public void setWidth(double width) { this.width = Math.max(0, width); }

    public double getHeight() { return height; }
    public void setHeight(double height) { this.height = Math.max(0, height); }

    public double getOpacity() {
        if (opacity < 0) return 0;
        return Math.min(opacity, 1.0);
    }
    public void setOpacity(double opacity) { this.opacity = opacity; }

    public boolean isVisible() { return visible; }
    public void setVisible(boolean visible) { this.visible = visible; }

    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }

    /** Já foi posicionada alguma vez? Largura zero significa "ainda não". */
    @JsonIgnore
    public boolean isPlaced() { return width > 0 && height > 0; }

    /**
     * Posiciona a imagem centrada em (cx, cy), ocupando {@code spanX} metros de
     * largura e mantendo a proporção original. Usado no primeiro encaixe, para
     * a imagem nascer visível no meio da tela em vez de num canto do planeta.
     */
    public void placeCentered(double cx, double cy, double spanX, double aspectRatio) {
        double w = Math.max(1, spanX);
        double h = aspectRatio > 0 ? w / aspectRatio : w;
        this.width = w;
        this.height = h;
        this.x = cx - w / 2;
        this.y = cy - h / 2;
    }
}
