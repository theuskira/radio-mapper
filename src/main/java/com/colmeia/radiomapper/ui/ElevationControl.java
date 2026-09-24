package com.colmeia.radiomapper.ui;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Cursor;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

/**
 * Plano vertical do feixe, em vista lateral — o par da bússola.
 *
 * A bússola responde "para onde aponta"; isto responde "para cima ou para
 * baixo, e com que abertura". A antena fica à esquerda sobre um mastro, com
 * a linha do horizonte em 0°; arrastar dentro do painel ajusta a inclinação.
 *
 * Convenção: inclinação positiva aponta para CIMA, negativa é downtilt — que
 * é o caso normal de setorial de torre olhando para clientes abaixo.
 */
public class ElevationControl extends Pane {

    private static final double W = 190;
    private static final double H = 130;
    /** Onde fica a antena: à esquerda, na metade da altura. */
    private static final double PIVOT_X = 26;
    private static final double PIVOT_Y = H / 2;
    private static final double RAY = W - PIVOT_X - 14;

    /** Altura acima da qual o mastro para de crescer no desenho. */
    private static final double MAST_FULL_SCALE_M = 60;
    /** Espaço vertical disponível entre a antena e a borda de baixo. */
    private static final double MAST_MAX_PX = H - 12 - PIVOT_Y;

    private final DoubleProperty tilt = new SimpleDoubleProperty(0);
    private final DoubleProperty beamWidth = new SimpleDoubleProperty(0);
    private final javafx.beans.property.DoubleProperty antennaHeight =
            new javafx.beans.property.SimpleDoubleProperty(0);
    /**
     * O número em {@code antennaHeight} é cota absoluta, não altura de mastro.
     *
     * Aqui no diálogo não se sabe a altitude do terreno sob o ponto — ela
     * depende do relevo carregado e da posição no mapa —, então o comprimento
     * do mastro é indeterminado. Em vez de inventar um desenho, o controle
     * assume um mastro neutro e o rótulo diz "cota", deixando claro que o
     * número não é a altura acima do chão.
     */
    private final javafx.beans.property.BooleanProperty absolute =
            new javafx.beans.property.SimpleBooleanProperty(false);

    private final Arc sector;
    private final Line axis;
    private final Text readout;
    private final Line mast;
    private final Line ground;
    private final Text heightLabel;

    public ElevationControl() {
        setPrefSize(W, H);
        setMinSize(W, H);
        setMaxSize(W, H);

        Rectangle bg = new Rectangle(0, 0, W, H);
        bg.setFill(Color.web("#1f1f1f"));
        bg.setStroke(Color.web("#777"));
        bg.setStrokeWidth(1.5);
        getChildren().add(bg);

        // Sem isto, um feixe de abertura larga (o setor tem raio maior que a
        // metade da altura do painel) vazava para fora da moldura e se
        // sobrepunha ao resto do diálogo. Recortado, ele apenas "sai de cena",
        // que é a leitura correta: o feixe continua além do que cabe aqui.
        Rectangle clip = new Rectangle(1, 1, W - 2, H - 2);
        setClip(clip);

        // Horizonte: a referência de 0°.
        Line horizon = new Line(PIVOT_X, PIVOT_Y, W - 6, PIVOT_Y);
        horizon.setStroke(Color.web("#888"));
        horizon.setStrokeWidth(1);
        horizon.getStrokeDashArray().setAll(4.0, 4.0);
        getChildren().add(horizon);

        // Guias de 15 em 15°, para dar noção de escala sem poluir.
        for (int deg = -60; deg <= 60; deg += 15) {
            if (deg == 0) continue;
            double rad = Math.toRadians(deg);
            Line g = new Line(PIVOT_X, PIVOT_Y,
                    PIVOT_X + Math.cos(rad) * RAY, PIVOT_Y - Math.sin(rad) * RAY);
            g.setStroke(Color.web(deg % 30 == 0 ? "#4a4a4a" : "#333"));
            g.setStrokeWidth(0.8);
            getChildren().add(g);
        }

        // Setor coberto pelo feixe no plano vertical. No Arc do JavaFX o
        // ângulo cresce no anti-horário a partir das 3 horas, que já é
        // "para cima" — então aqui não há conversão a fazer.
        sector = new Arc(PIVOT_X, PIVOT_Y, RAY, RAY, 0, 0);
        sector.setType(ArcType.ROUND);
        sector.setFill(Color.DEEPSKYBLUE.deriveColor(0, 1, 1, 0.30));
        sector.setStroke(Color.DEEPSKYBLUE);
        sector.setStrokeWidth(1.2);
        sector.setMouseTransparent(true);
        getChildren().add(sector);

        // Eixo do feixe.
        axis = new Line(PIVOT_X, PIVOT_Y, PIVOT_X + RAY, PIVOT_Y);
        axis.setStroke(Color.CRIMSON);
        axis.setStrokeWidth(2.5);
        axis.setStrokeLineCap(StrokeLineCap.ROUND);
        axis.setMouseTransparent(true);
        getChildren().add(axis);

        // Mastro e solo. O comprimento do mastro segue a altura instalada da
        // antena, então dá para ver de relance se ela está num poste baixo ou
        // numa torre — é o que faz o desenho corresponder ao rádio real.
        mast = new Line(PIVOT_X, PIVOT_Y, PIVOT_X, PIVOT_Y);
        mast.setStroke(Color.web("#bbb"));
        mast.setStrokeWidth(2);
        ground = new Line(6, PIVOT_Y, W - 6, PIVOT_Y);
        ground.setStroke(Color.web("#6b8e23"));
        ground.setStrokeWidth(2);
        heightLabel = new Text(0, 0, "");
        heightLabel.setFill(Color.web("#9bbf5a"));
        heightLabel.setFont(Font.font("System", 10));
        heightLabel.setMouseTransparent(true);
        Circle hub = new Circle(PIVOT_X, PIVOT_Y, 4, Color.web("#eee"));
        getChildren().addAll(mast, ground, heightLabel, hub);

        getChildren().add(sideLabel("cima", W - 34, 14));
        getChildren().add(sideLabel("baixo", W - 36, H - 20));

        readout = new Text(8, 16, "");
        readout.setFill(Color.web("#ddd"));
        readout.setFont(Font.font("System", FontWeight.BOLD, 11));
        readout.setMouseTransparent(true);
        getChildren().add(readout);

        Runnable refresh = () -> {
            double t = clampTilt(tilt.get());
            double w = Math.max(0, Math.min(180, beamWidth.get()));

            double rad = Math.toRadians(t);
            axis.setEndX(PIVOT_X + Math.cos(rad) * RAY);
            axis.setEndY(PIVOT_Y - Math.sin(rad) * RAY);

            sector.setVisible(w > 0);
            if (w > 0) {
                sector.setStartAngle(t - w / 2.0);
                sector.setLength(w);
            }
            readout.setText(w > 0
                    ? String.format("%+.1f°   abertura %.1f°", t, w)
                    : String.format("%+.1f°", t));

            // Solo desce conforme a antena sobe. Escala linear até 60 m, que
            // cobre a quase totalidade das instalações; acima disso satura e o
            // número no rótulo continua dizendo a verdade.
            double hm = antennaHeight.get();
            boolean abs = absolute.get();
            double drop = abs ? MAST_MAX_PX * 0.55
                              : Math.min(MAST_MAX_PX, Math.max(0, hm) / MAST_FULL_SCALE_M * MAST_MAX_PX);
            double groundY = PIVOT_Y + drop;
            mast.setEndY(groundY);
            ground.setStartY(groundY);
            ground.setEndY(groundY);
            heightLabel.setText(abs ? String.format("cota %.0f m", hm)
                                    : (hm > 0 ? String.format("%.0f m", hm) : "no solo"));
            heightLabel.setX(6);
            heightLabel.setY(groundY - 3);
        };
        tilt.addListener((o, a, b) -> refresh.run());
        beamWidth.addListener((o, a, b) -> refresh.run());
        antennaHeight.addListener((o, a, b) -> refresh.run());
        absolute.addListener((o, a, b) -> refresh.run());
        refresh.run();

        setCursor(Cursor.HAND);
        setOnMousePressed(this::pickAngle);
        setOnMouseDragged(this::pickAngle);
    }

    private Text sideLabel(String s, double x, double y) {
        Text t = new Text(x, y, s);
        t.setFill(Color.web("#777"));
        t.setFont(Font.font("System", 10));
        t.setMouseTransparent(true);
        return t;
    }

    private void pickAngle(javafx.scene.input.MouseEvent ev) {
        double dx = ev.getX() - PIVOT_X;
        double dy = ev.getY() - PIVOT_Y;
        if (dx == 0 && dy == 0) return;
        // Y da tela cresce para baixo; invertemos para "para cima = positivo".
        double deg = Math.toDegrees(Math.atan2(-dy, Math.max(dx, 0.001)));
        tilt.set(clampTilt(Math.round(deg * 2) / 2.0));
        ev.consume();
    }

    private static double clampTilt(double v) { return Math.max(-90, Math.min(90, v)); }

    public DoubleProperty tiltProperty() { return tilt; }
    public double getTilt() { return tilt.get(); }
    public void setTilt(double deg) { tilt.set(clampTilt(deg)); }

    public DoubleProperty beamWidthProperty() { return beamWidth; }
    public void setBeamWidth(double deg) { beamWidth.set(deg); }

    /** Altura instalada da antena, em metros — muda o comprimento do mastro. */
    public javafx.beans.property.DoubleProperty antennaHeightProperty() { return antennaHeight; }
    public void setAntennaHeight(double m) { antennaHeight.set(m); }

    /** Interpreta o número como cota absoluta em vez de altura de mastro. */
    public void setAbsoluteAltitude(boolean v) { absolute.set(v); }
}
