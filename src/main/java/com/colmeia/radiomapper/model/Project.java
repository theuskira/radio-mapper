package com.colmeia.radiomapper.model;

import com.colmeia.radiomapper.geo.PlyElevation;
import com.colmeia.radiomapper.geo.TileSource;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Um projeto guarda pontos, enlaces e como o mapa deve ser exibido.
 *
 * <h3>Dois espaços de coordenadas</h3>
 * {@code NetworkPoint.x/y} significam coisas diferentes conforme o
 * {@link #basemap}:
 *
 * <ul>
 *   <li>{@code basemap == NONE} — modo imagem: x/y são pixels da imagem de
 *       fundo original. É o formato histórico; projetos .rmap antigos caem
 *       aqui naturalmente, porque o enum desserializa para NONE quando o
 *       campo não existe.</li>
 *   <li>{@code basemap != NONE} — modo mapa: x/y são metros de Web Mercator
 *       (EPSG:3857), com Y crescendo para o sul. Ver
 *       {@code com.colmeia.radiomapper.geo.Mercator}.</li>
 * </ul>
 *
 * Trocar de modo com pontos já marcados reinterpreta as coordenadas, por isso
 * a UI confirma antes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Project {
    private String name = "Projeto sem nome";
    private TileSource basemap = TileSource.NONE;

    /**
     * Imagens de fundo, da mais ao fundo para a mais à frente.
     *
     * Lista, e não uma só: um projeto raramente cabe num voo. A ordem é a de
     * pintura, então a última cobre as anteriores onde se sobrepõem.
     */
    private List<ImageLayer> images = new ArrayList<>();
    /** Escala da imagem em modo imagem. Ver getMetersPerPixel(). */
    private double metersPerPixel = 1.0;
    /** Levantamento .PLY usado como fonte de relevo, se houver. */
    private String plyPath = "";

    /**
     * Como o .PLY foi interpretado na carga.
     *
     * Guardar só o caminho não bastaria: o PLY não declara sistema de
     * coordenadas de forma obrigatória, então reabrir o projeto teria que
     * perguntar tudo de novo — ou, pior, adivinhar diferente da vez anterior
     * e colocar o relevo em outro lugar. Com estes campos a reabertura
     * reproduz exatamente a carga que o usuário confirmou.
     */
    private PlyElevation.Crs plyCrs = PlyElevation.Crs.UTM;
    private int plyUtmZone;
    private boolean plyUtmSouth = true;
    /** Resolução da grade, em metros. 0 = usar o padrão do diálogo. */
    private double plyCellSizeM;

    // Última visão do mapa, para reabrir o projeto onde o usuário parou.
    private double viewLat = -15.78;   // Brasília, só como ponto de partida
    private double viewLon = -47.93;
    private int viewZoom = 4;

    private List<NetworkPoint> points = new ArrayList<>();
    private List<Link> links = new ArrayList<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<ImageLayer> getImages() { return images; }
    public void setImages(List<ImageLayer> v) {
        this.images = v == null ? new ArrayList<>() : v;
    }

    /** A primeira imagem, ou null. Atalho para quem só precisa de uma. */
    @JsonIgnore
    public ImageLayer firstImage() { return images.isEmpty() ? null : images.get(0); }

    @JsonIgnore
    public boolean hasImages() { return !images.isEmpty(); }

    /** A camada com este id, ou null. */
    @JsonIgnore
    public ImageLayer imageById(String id) {
        if (id == null) return null;
        for (ImageLayer l : images) {
            if (l.getId().equals(id)) return l;
        }
        return null;
    }

    // ---------------- compatibilidade com .rmap de uma imagem só ----------------
    //
    // Os campos abaixo existiam quando o projeto tinha UMA imagem. Continuam
    // sendo lidos para que projeto antigo abra igual, e caem na primeira
    // camada. Não são mais gravados: a lista é que passa a valer, e um arquivo
    // com as duas formas acabaria com elas discordando.

    @JsonIgnore
    private ImageLayer legado() {
        if (images.isEmpty()) images.add(new ImageLayer());
        return images.get(0);
    }

    @JsonSetter("imagePath")
    public void setImagePath(String v) {
        if (v != null && !v.isBlank()) legado().setPath(v);
    }

    @JsonSetter("imageGeoreferenced")
    public void setImageGeoreferenced(boolean v) { legado().setGeoreferenced(v); }

    @JsonSetter("imageOverlay")
    public void setImageOverlay(ImageOverlay o) {
        if (o != null) legado().setOverlay(o);
    }

    @JsonSetter("imageUtmZone")
    public void setImageUtmZone(int v) { legado().setUtmZone(v); }

    @JsonSetter("imageUtmSouth")
    public void setImageUtmSouth(boolean v) { legado().setUtmSouth(v); }

    /**
     * Quantos metros de chão cabem num pixel da imagem. Só usado em modo
     * imagem (sem mapa base), onde não há como deduzir a escala: o programa
     * não sabe se a foto cobre um quarteirão ou uma cidade.
     *
     * O padrão 1.0 faz metro e pixel coincidirem, que é como os alcances de
     * feixe se comportavam antes de passarem a ser medidos em metros.
     */
    public double getMetersPerPixel() { return metersPerPixel <= 0 ? 1.0 : metersPerPixel; }
    public void setMetersPerPixel(double v) { this.metersPerPixel = v; }

    public String getPlyPath() { return plyPath == null ? "" : plyPath; }
    public void setPlyPath(String v) { this.plyPath = v == null ? "" : v; }

    public PlyElevation.Crs getPlyCrs() { return plyCrs == null ? PlyElevation.Crs.UTM : plyCrs; }
    public void setPlyCrs(PlyElevation.Crs v) { this.plyCrs = v == null ? PlyElevation.Crs.UTM : v; }

    public int getPlyUtmZone() { return plyUtmZone; }
    public void setPlyUtmZone(int v) { this.plyUtmZone = v; }

    public boolean isPlyUtmSouth() { return plyUtmSouth; }
    public void setPlyUtmSouth(boolean v) { this.plyUtmSouth = v; }

    public double getPlyCellSizeM() { return plyCellSizeM; }
    public void setPlyCellSizeM(double v) { this.plyCellSizeM = v; }

    public TileSource getBasemap() { return basemap; }
    public void setBasemap(TileSource basemap) { this.basemap = basemap == null ? TileSource.NONE : basemap; }

    /** {@code true} quando x/y dos pontos são metros de Mercator. */
    @JsonIgnore
    public boolean isMapMode() { return basemap != null && basemap.isMap(); }

    public double getViewLat() { return viewLat; }
    public void setViewLat(double viewLat) { this.viewLat = viewLat; }

    public double getViewLon() { return viewLon; }
    public void setViewLon(double viewLon) { this.viewLon = viewLon; }

    public int getViewZoom() { return viewZoom; }
    public void setViewZoom(int viewZoom) { this.viewZoom = viewZoom; }

    public List<NetworkPoint> getPoints() { return points; }
    public void setPoints(List<NetworkPoint> points) { this.points = points == null ? new ArrayList<>() : points; }

    public List<Link> getLinks() { return links; }
    public void setLinks(List<Link> links) { this.links = links == null ? new ArrayList<>() : links; }

    public Optional<Radio> findRadioById(String id) {
        if (id == null) return Optional.empty();
        for (NetworkPoint p : points) {
            for (Radio r : p.getRadios()) {
                if (id.equals(r.getId())) return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    public Optional<Radio> findRadioByMac(String mac) {
        if (mac == null || mac.isBlank()) return Optional.empty();
        String needle = mac.toLowerCase();
        for (NetworkPoint p : points) {
            for (Radio r : p.getRadios()) {
                if (needle.equals(r.getMac())) return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    public Optional<NetworkPoint> findPointOfRadio(String radioId) {
        if (radioId == null) return Optional.empty();
        for (NetworkPoint p : points) {
            for (Radio r : p.getRadios()) {
                if (radioId.equals(r.getId())) return Optional.of(p);
            }
        }
        return Optional.empty();
    }
}
