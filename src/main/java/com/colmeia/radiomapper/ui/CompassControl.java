package com.colmeia.radiomapper.ui;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Cursor;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.transform.Rotate;

/**
 * Bússola interativa: círculo com N/L/S/O e uma agulha que aponta para
 * {@link #azimuthProperty()}. Convenção compass — 0° = norte (cima),
 * sentido horário (90° = leste). Clicar ou arrastar dentro do círculo
 * ajusta o azimute.
 */
public class CompassControl extends Pane {

    private static final double SIZE = 120;
    private static final double R = SIZE / 2;
    private static final double NEEDLE_LEN = R - 18;

    private final DoubleProperty azimuth = new SimpleDoubleProperty(0);
    /** Abertura horizontal, para desenhar o setor coberto. 0 = só a agulha. */
    private final DoubleProperty beamWidth = new SimpleDoubleProperty(0);
    private final Line needle;
    private final Line tail;
    private final javafx.scene.shape.Arc sector;

    public CompassControl() {
        setPrefSize(SIZE, SIZE);
        setMinSize(SIZE, SIZE);
        setMaxSize(SIZE, SIZE);

        Circle bg = new Circle(R, R, R - 2, Color.web("#1f1f1f"));
        bg.setStroke(Color.web("#777"));
        bg.setStrokeWidth(1.5);

        // Recorte no próprio círculo: nada do setor escapa da moldura.
        setClip(new Circle(R, R, R - 1));

        // Marcações de 30 em 30°, com N/L/S/O destacados.
        getChildren().add(bg);
        for (int deg = 0; deg < 360; deg += 30) {
            double rad = Math.toRadians(deg - 90); // compass→math: -90
            double x1 = R + Math.cos(rad) * (R - 4);
            double y1 = R + Math.sin(rad) * (R - 4);
            double inset = (deg % 90 == 0) ? 10 : 5;
            double x2 = R + Math.cos(rad) * (R - 4 - inset);
            double y2 = R + Math.sin(rad) * (R - 4 - inset);
            Line tick = new Line(x1, y1, x2, y2);
            tick.setStroke(Color.web("#aaa"));
            tick.setStrokeWidth(deg % 90 == 0 ? 1.6 : 0.8);
            getChildren().add(tick);
        }

        getChildren().addAll(
                label("N",  0, Color.CRIMSON),
                label("L", 90, Color.WHITE),
                label("S", 180, Color.WHITE),
                label("O", 270, Color.WHITE));

        // Setor do feixe, desenhado ANTES da agulha para ficar por baixo.
        // Mesma conversão do mapa: javafxDeg = 90 − compassDeg, porque o Arc
        // mede 0° às 3 horas e cresce no anti-horário.
        sector = new javafx.scene.shape.Arc(R, R, NEEDLE_LEN, NEEDLE_LEN, 0, 0);
        sector.setType(javafx.scene.shape.ArcType.ROUND);
        sector.setFill(Color.DEEPSKYBLUE.deriveColor(0, 1, 1, 0.30));
        sector.setStroke(Color.DEEPSKYBLUE);
        sector.setStrokeWidth(1.2);
        sector.setMouseTransparent(true);
        getChildren().add(sector);

        Runnable refreshSector = () -> {
            double w = Math.max(0, Math.min(360, beamWidth.get()));
            sector.setVisible(w > 0);
            if (w <= 0) return;
            if (w >= 360) { sector.setStartAngle(0); sector.setLength(360); return; }
            sector.setStartAngle(90 - azimuth.get() - w / 2.0);
            sector.setLength(w);
        };
        azimuth.addListener((o, a, b) -> refreshSector.run());
        beamWidth.addListener((o, a, b) -> refreshSector.run());
        refreshSector.run();

        // Agulha: ponta vermelha (frente do feixe) + cauda branca para
        // referência visual da direção oposta.
        needle = new Line(R, R, R, R - NEEDLE_LEN);
        needle.setStroke(Color.CRIMSON);
        needle.setStrokeWidth(3);
        needle.setStrokeLineCap(StrokeLineCap.ROUND);

        tail = new Line(R, R, R, R + NEEDLE_LEN * 0.45);
        tail.setStroke(Color.web("#ddd"));
        tail.setStrokeWidth(2);
        tail.setStrokeLineCap(StrokeLineCap.ROUND);

        Rotate rot = new Rotate(0, R, R);
        needle.getTransforms().add(rot);
        tail.getTransforms().add(rot);
        azimuth.addListener((o, a, b) -> rot.setAngle(b.doubleValue()));

        Circle hub = new Circle(R, R, 4, Color.web("#eee"));
        getChildren().addAll(tail, needle, hub);

        setCursor(Cursor.HAND);
        setOnMousePressed(this::pickAngle);
        setOnMouseDragged(this::pickAngle);
    }

    private Text label(String s, int compassDeg, Color color) {
        double rad = Math.toRadians(compassDeg - 90);
        double x = R + Math.cos(rad) * (R - 14);
        double y = R + Math.sin(rad) * (R - 14);
        Text t = new Text(s);
        t.setFont(Font.font("System", FontWeight.BOLD, 12));
        t.setFill(color);
        // centraliza o texto no ponto calculado
        t.applyCss();
        double w = t.getLayoutBounds().getWidth();
        double h = t.getLayoutBounds().getHeight();
        t.setX(x - w / 2);
        t.setY(y + h / 4);
        return t;
    }

    private void pickAngle(javafx.scene.input.MouseEvent ev) {
        double dx = ev.getX() - R;
        double dy = ev.getY() - R;
        if (dx == 0 && dy == 0) return;
        // atan2 dá ângulo em math (CCW de +X). Converte para compass.
        double mathDeg = Math.toDegrees(Math.atan2(dy, dx));
        // Meio grau de resolucao: o arrasto fica util sem ficar nervoso.
        double compass = Math.round((mathDeg + 90) * 2) / 2.0;
        compass = ((compass % 360) + 360) % 360;
        azimuth.set(compass);
        ev.consume();
    }

    public DoubleProperty beamWidthProperty() { return beamWidth; }
    public void setBeamWidth(double deg) { beamWidth.set(deg); }

    public DoubleProperty azimuthProperty() { return azimuth; }
    public double getAzimuth() { return azimuth.get(); }
    public void setAzimuth(double deg) { azimuth.set(((deg % 360) + 360) % 360); }
}
