package com.colmeia.radiomapper.geo;

import com.colmeia.radiomapper.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Encadeia as fontes de altitude na ordem de prioridade definida:
 * <b>PLY → GeoTIFF → relevo do mapa</b>.
 *
 * A ordem é por qualidade do dado. Um levantamento de drone tem resolução
 * centimétrica e enxerga o que existe hoje no terreno; um MDT em GeoTIFF é
 * bom mas normalmente mais grosseiro e mais antigo; o relevo global do mapa
 * é a rede de segurança, com ~30 m de resolução, e serve onde nenhum
 * levantamento cobre.
 *
 * Cada fonte devolve {@code null} fora da sua área, então a consulta desce a
 * cadeia naturalmente: dentro da área do drone vale o drone, um metro além
 * dela já vale a próxima fonte, sem buraco no meio.
 */
public final class ElevationChain implements ElevationSource {

    private ElevationSource ply;
    private ElevationSource geotiff;
    private ElevationSource terrain;

    /**
     * O relevo de reserva ja acertado com o levantamento, quando ha os dois.
     *
     * Fica separado de {@link #terrain} de proposito: o original continua
     * intacto, e basta soltar esta referencia para a cadeia voltar a usar a
     * reserva como ela e'.
     */
    private ElevationSource costurado;

    public void setPly(ElevationSource s) {
        this.ply = s;
        // Trocar o levantamento invalida a costura: ela foi medida contra
        // o anterior, e manter seria deslocar a reserva pelo vies de uma
        // nuvem que nao esta mais aqui.
        this.costurado = null;
        announce("PLY", s);
    }
    public void setGeotiff(ElevationSource s) { this.geotiff = s; announce("GeoTIFF", s); }
    public void setTerrain(ElevationSource s) { this.terrain = s; this.costurado = null; }

    /**
     * Costura o relevo de reserva na altura do levantamento.
     *
     * Sem isto a cadeia troca de fonte no meio do terreno e o degrau entre
     * elas vira poco nos vaos da nuvem. Ver {@link Costura}.
     *
     * @param bounds area do levantamento, em coordenadas de mundo
     */
    public void costurar(double[] bounds) {
        costurado = (ply == null || terrain == null) ? null
                : Costura.tecer(ply, terrain, bounds);
    }

    /** Desfaz a costura: a reserva volta a valer como ela e'. */
    public void descosturar() { costurado = null; }

    public ElevationSource ply() { return ply; }
    public ElevationSource geotiff() { return geotiff; }
    public ElevationSource terrain() { return terrain; }

    private void announce(String slot, ElevationSource s) {
        if (s == null) Log.info("Fonte de altitude %s removida", slot);
        else Log.info("Fonte de altitude %s: %s (~%.0f m)",
                slot, s.sourceName(), s.resolutionMeters());
    }

    /** Fontes ativas, na ordem em que são consultadas. */
    public List<ElevationSource> active() {
        List<ElevationSource> out = new ArrayList<>(3);
        if (ply != null) out.add(ply);
        if (geotiff != null) out.add(geotiff);
        if (costurado != null) out.add(costurado);
        else if (terrain != null) out.add(terrain);
        return out;
    }

    public boolean hasAny() { return ply != null || geotiff != null || terrain != null; }

    @Override
    public Double elevationAt(double worldX, double worldY) {
        for (ElevationSource s : active()) {
            Double h = s.elevationAt(worldX, worldY);
            if (h != null) return h;
        }
        return null;
    }

    @Override
    public Double elevationOver(double worldX, double worldY, double raioM) {
        for (ElevationSource s : active()) {
            Double h = s.elevationOver(worldX, worldY, raioM);
            if (h != null) return h;
        }
        return null;
    }

    /** Qual fonte respondeu por este ponto — usado na leitura sob o cursor. */
    public ElevationSource sourceAt(double worldX, double worldY) {
        for (ElevationSource s : active()) {
            if (s.elevationAt(worldX, worldY) != null) return s;
        }
        return null;
    }

    @Override
    public String sourceName() {
        List<ElevationSource> a = active();
        if (a.isEmpty()) return "sem fonte de altitude";
        StringBuilder sb = new StringBuilder();
        for (ElevationSource s : a) {
            if (sb.length() > 0) sb.append(" → ");
            sb.append(s.sourceName());
        }
        return sb.toString();
    }

    @Override
    public double resolutionMeters() {
        List<ElevationSource> a = active();
        return a.isEmpty() ? 0 : a.get(0).resolutionMeters();
    }
}
