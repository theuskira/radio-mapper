package com.colmeia.radiomapper.ui;

import com.colmeia.radiomapper.geo.Mercator;
import com.colmeia.radiomapper.geo.TileCache;
import com.colmeia.radiomapper.geo.TileSource;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Camada de mapa base. Vive dentro do {@code worldLayer} do MapPane, então
 * herda o zoom/pan dele e trabalha em coordenadas de mundo (metros de
 * Mercator) — não precisa saber nada sobre a transformação de tela.
 *
 * A cada {@link #update} recalcula quais tiles cobrem a viewport no zoom
 * adequado, pede os que faltam e descarta os que saíram. Tiles do zoom
 * anterior só somem quando os novos chegam, para o mapa não piscar em branco
 * durante o zoom.
 */
public class TileLayer extends Group {

    /**
     * Teto de segurança. Com o zoom escolhido por {@link Mercator#zoomForScale}
     * a conta real fica na casa de algumas dezenas; isso aqui só evita que um
     * estado inesperado (viewport gigante, escala degenerada) tente alocar
     * milhares de ImageViews.
     */
    private static final int MAX_TILES = 600;

    /** Sobreposição mínima entre tiles vizinhos para fechar as costuras de 1px. */
    private static final double SEAM_OVERLAP = 1.002;

    private TileSource source = TileSource.NONE;
    private int currentZoom = -1;

    /** Tiles no palco, indexados por "z/x/y". */
    private final Map<String, ImageView> live = new HashMap<>();

    /** Tiles pedidos e ainda não chegados no zoom atual. */
    private int pending;

    public TileLayer() {
        setMouseTransparent(true);   // o mapa base nunca rouba clique dos pontos
    }

    public TileSource getSource() { return source; }

    public void setSource(TileSource s) {
        TileSource next = s == null ? TileSource.NONE : s;
        if (next == source) return;
        source = next;
        clearAll();
    }

    public void clearAll() {
        getChildren().clear();
        live.clear();
        currentZoom = -1;
        pending = 0;
    }

    /**
     * Recalcula a cobertura de tiles.
     *
     * @param pxPerWorldUnit escala atual do MapPane (px de tela por metro)
     * @param x0 x1 y0 y1    retângulo visível, em coordenadas de mundo
     */
    public void update(double pxPerWorldUnit, double x0, double y0, double x1, double y1) {
        if (!source.isMap() || pxPerWorldUnit <= 0) {
            if (!getChildren().isEmpty()) clearAll();
            return;
        }

        int z = Mercator.zoomForScale(pxPerWorldUnit, source.minZoom(), source.maxZoom());
        double span = Mercator.tileSpan(z);
        int n = Mercator.tileCount(z);

        int txMin = Math.max(0, Mercator.tileXOf(x0, z));
        int txMax = Math.min(n - 1, Mercator.tileXOf(x1, z));
        int tyMin = Math.max(0, Mercator.tileYOf(y0, z));
        int tyMax = Math.min(n - 1, Mercator.tileYOf(y1, z));

        if (txMax < txMin || tyMax < tyMin) return;   // viewport fora do mundo

        long want = (long) (txMax - txMin + 1) * (tyMax - tyMin + 1);
        if (want > MAX_TILES) return;                 // estado degenerado: não faz nada

        if (z != currentZoom) {
            currentZoom = z;
            pending = 0;
        }

        Set<String> needed = new HashSet<>();
        for (int tx = txMin; tx <= txMax; tx++) {
            for (int ty = tyMin; ty <= tyMax; ty++) {
                String k = z + "/" + tx + "/" + ty;
                needed.add(k);
                if (live.containsKey(k)) continue;

                final int fx = tx, fy = ty, fz = z;
                Image hit = TileCache.INSTANCE.cached(source, fz, fx, fy);
                if (hit != null) {
                    place(k, hit, fz, fx, fy, span);
                } else {
                    pending++;
                    TileSource requested = source;
                    TileCache.INSTANCE.request(source, fz, fx, fy, img -> {
                        // Enquanto o tile viajava, o usuário pode ter trocado de
                        // provedor ou de zoom — aí ele não serve mais.
                        if (requested != source || fz != currentZoom) return;
                        pending--;
                        if (!live.containsKey(k)) place(k, img, fz, fx, fy, span);
                        if (pending <= 0) dropOtherZooms(fz);
                    });
                }
            }
        }

        // Remove o que saiu de vista no zoom atual.
        live.entrySet().removeIf(e -> {
            if (!e.getKey().startsWith(currentZoom + "/")) return false;
            if (needed.contains(e.getKey())) return false;
            getChildren().remove(e.getValue());
            return true;
        });

        if (pending <= 0) dropOtherZooms(z);
    }

    private void place(String key, Image img, int z, int tx, int ty, double span) {
        ImageView iv = new ImageView(img);
        iv.setX(Mercator.tileWorldX(tx, z));
        iv.setY(Mercator.tileWorldY(ty, z));
        iv.setFitWidth(span * SEAM_OVERLAP);
        iv.setFitHeight(span * SEAM_OVERLAP);
        iv.setSmooth(true);
        iv.setPreserveRatio(false);
        live.put(key, iv);
        getChildren().add(iv);   // novos por cima dos do zoom antigo
    }

    /** Descarta tiles de zooms que não são mais o atual (só depois que o novo cobriu). */
    private void dropOtherZooms(int keepZoom) {
        String prefix = keepZoom + "/";
        live.entrySet().removeIf(e -> {
            if (e.getKey().startsWith(prefix)) return false;
            getChildren().remove(e.getValue());
            return true;
        });
        // Defesa contra vazamento: nada fora do mapa de controle deve sobrar.
        if (getChildren().size() > live.size()) {
            Set<Node> keep = new HashSet<>(live.values());
            getChildren().removeIf(nd -> !keep.contains(nd));
        }
    }
}
