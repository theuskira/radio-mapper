package com.colmeia.radiomapper.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.File;
import java.util.UUID;

/**
 * Uma imagem de fundo do projeto — normalmente uma ortofoto de voo.
 *
 * <h3>Por que mais de uma</h3>
 * Um projeto raramente cabe num voo só. A pedreira foi levantada num dia, a
 * expansão no mês seguinte, o acesso saiu de outro contrato — e o que
 * interessa é ver tudo junto, encaixado no mesmo mapa. Com uma imagem apenas,
 * abrir a segunda apagava a primeira.
 *
 * <h3>Cada uma se posiciona sozinha</h3>
 * Cada camada guarda o próprio {@link ImageOverlay}: onde está, que tamanho
 * tem, se está visível, quanto de opacidade e se está travada. São voos
 * diferentes, em datas diferentes, e cada um se encaixa no mapa por conta
 * própria — travar o que já está no lugar enquanto se ajusta o seguinte é
 * justamente o que se quer poder fazer.
 *
 * <h3>Ordem</h3>
 * A ordem da lista no projeto é a ordem de pintura: a última entra por cima.
 * É o que permite pôr o voo mais recente sobre o antigo sem apagar nenhum dos
 * dois.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImageLayer {

    private String id = UUID.randomUUID().toString();

    /** Caminho do arquivo em disco. */
    private String path = "";

    /** Nome curto para a lista. Vazio usa o nome do arquivo. */
    private String name = "";

    /** O posicionamento veio das coordenadas do próprio arquivo? */
    private boolean georeferenced;

    /**
     * Fuso UTM informado à mão, quando o EPSG do arquivo não é reconhecido.
     * Fica por camada porque cada voo pode ter sido entregue num CRS.
     */
    private int utmZone;
    private boolean utmSouth = true;

    private ImageOverlay overlay = new ImageOverlay();

    public ImageLayer() {}

    public ImageLayer(String path) {
        this.path = path == null ? "" : path;
    }

    public String getId() { return id; }
    public void setId(String v) { this.id = v == null || v.isBlank()
            ? UUID.randomUUID().toString() : v; }

    public String getPath() { return path; }
    public void setPath(String v) { this.path = v == null ? "" : v; }

    public String getName() { return name; }
    public void setName(String v) { this.name = v == null ? "" : v; }

    /** O que mostrar na lista: o nome dado, ou o nome do arquivo. */
    @JsonIgnore
    public String displayName() {
        if (!name.isBlank()) return name;
        if (path.isBlank()) return "(sem arquivo)";
        return new File(path).getName();
    }

    public boolean isGeoreferenced() { return georeferenced; }
    public void setGeoreferenced(boolean v) { this.georeferenced = v; }

    public int getUtmZone() { return utmZone; }
    public void setUtmZone(int v) { this.utmZone = v; }

    public boolean isUtmSouth() { return utmSouth; }
    public void setUtmSouth(boolean v) { this.utmSouth = v; }

    public ImageOverlay getOverlay() { return overlay; }
    public void setOverlay(ImageOverlay o) { this.overlay = o == null ? new ImageOverlay() : o; }

    @JsonIgnore
    public boolean hasFile() { return !path.isBlank(); }

    @Override public String toString() { return displayName(); }
}
