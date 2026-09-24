package com.colmeia.radiomapper.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class NetworkPoint {
    private String id = UUID.randomUUID().toString();
    private String name = "";
    private double x;
    private double y;
    private List<Radio> radios = new ArrayList<>();

    /**
     * Roteadores instalados neste ponto.
     *
     * Lista separada dos radios de proposito: roteador nao tem antena, nem
     * azimute, nem alcance, e misturar os dois obrigaria metade dos campos de
     * {@link Radio} a ficar sem sentido. O que os dois compartilham — estar
     * num ponto, ter IP, responder ou nao — e pouco perto do que os separa.
     */
    private List<Router> routers = new ArrayList<>();

    public NetworkPoint() {}

    public NetworkPoint(String name, double x, double y) {
        this.name = name;
        this.x = x;
        this.y = y;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public double getX() { return x; }
    public void setX(double x) { this.x = x; }

    public double getY() { return y; }
    public void setY(double y) { this.y = y; }

    public List<Radio> getRadios() { return radios; }
    public void setRadios(List<Radio> radios) { this.radios = radios == null ? new ArrayList<>() : radios; }

    public List<Router> getRouters() { return routers; }
    public void setRouters(List<Router> v) { this.routers = v == null ? new ArrayList<>() : v; }

    @Override
    public String toString() {
        return name == null || name.isBlank() ? ("Ponto " + id.substring(0, 4)) : name;
    }
}
