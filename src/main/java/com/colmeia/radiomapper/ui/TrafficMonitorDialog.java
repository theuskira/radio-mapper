package com.colmeia.radiomapper.ui;

import com.colmeia.radiomapper.model.Router;
import com.colmeia.radiomapper.ssh.RouterOsProbe;
import com.colmeia.radiomapper.util.Log;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tráfego de uma interface, ao vivo, enquanto a janela estiver aberta.
 *
 * <h3>Como a taxa é obtida</h3>
 * O RouterOS expõe contadores acumulados de bytes, não taxa. A taxa é a
 * diferença entre duas leituras dividida pelo tempo entre elas — a mesma
 * conta que o programa já faz para o throughput dos enlaces. Consequência
 * inevitável: a primeira leitura não produz ponto nenhum, porque não há com o
 * que comparar.
 *
 * <h3>Contador que anda para trás</h3>
 * Equipamento reiniciado zera os contadores, e uma diferença negativa viraria
 * um pico absurdo no gráfico. Amostra assim é descartada, e a seguinte volta
 * a medir normalmente.
 *
 * <h3>Nada é gravado</h3>
 * Fechou a janela, parou de medir e o histórico se perde. É o equivalente ao
 * monitor de tráfego do Winbox: serve para olhar agora, não para saber o que
 * aconteceu de madrugada.
 */
public final class TrafficMonitorDialog {

    private TrafficMonitorDialog() {}

    /** Quantas amostras cabem no gráfico. A 2 s cada, cobre uns 4 minutos. */
    private static final int JANELA = 120;

    private static final Color RX_COR = Color.web("#4fc3f7");
    private static final Color TX_COR = Color.web("#ffb74d");
    private static final Color FUNDO = Color.web("#1f1f1f");
    private static final Color GRADE = Color.web("#3a3a3a");

    public static void show(Window owner, Router router, String interfaceInicial) {
        Stage stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("Tráfego · " + router.displayName());

        // ------------------------ Estado da medição ------------------------
        Deque<double[]> amostras = new ArrayDeque<>();   // {rxBps, txBps}
        AtomicBoolean rodando = new AtomicBoolean(true);
        // Volatile via array: escrito pela thread de sondagem, lido pelo timer.
        final long[] anterior = { -1, -1, -1 };          // rx, tx, instante ms
        final double[] pico = { 0, 0 };
        final String[] erro = { null };
        final String[] alvo = { interfaceInicial };

        // ------------------------ Tela ------------------------
        ComboBox<String> iface = new ComboBox<>();
        for (var i : router.getInterfaces()) iface.getItems().add(i.getName());
        if (!iface.getItems().contains(interfaceInicial)) iface.getItems().add(0, interfaceInicial);
        iface.setValue(interfaceInicial);
        iface.setPrefWidth(200);

        ComboBox<Integer> intervalo = new ComboBox<>();
        intervalo.getItems().addAll(1, 2, 5, 10);
        intervalo.setValue(2);
        intervalo.setPrefWidth(70);

        Label rxLabel = new Label("RX  —");
        rxLabel.setTextFill(RX_COR);
        rxLabel.setFont(Font.font("System", FontWeight.BOLD, 15));
        Label txLabel = new Label("TX  —");
        txLabel.setTextFill(TX_COR);
        txLabel.setFont(Font.font("System", FontWeight.BOLD, 15));
        Label picoLabel = new Label();
        picoLabel.setTextFill(Color.web("#aaa"));
        Label estado = new Label("Primeira leitura: a taxa aparece na próxima.");
        estado.setTextFill(Color.web("#aaa"));
        estado.setWrapText(true);

        Canvas canvas = new Canvas(620, 240);

        Region espaco = new Region();
        HBox.setHgrow(espaco, Priority.ALWAYS);
        HBox topo = new HBox(10,
                new Label("Interface:"), iface,
                new Label("a cada"), intervalo, new Label("s"),
                espaco, rxLabel, txLabel);
        topo.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox raiz = new VBox(8, topo, canvas, picoLabel, estado);
        raiz.setPadding(new Insets(12));

        // Trocar de interface começa de novo: misturar as duas séries no mesmo
        // gráfico daria um degrau que não aconteceu no cabo.
        iface.valueProperty().addListener((o, a, b) -> {
            if (b == null) return;
            alvo[0] = b;
            synchronized (amostras) { amostras.clear(); }
            anterior[0] = anterior[1] = anterior[2] = -1;
            pico[0] = pico[1] = 0;
            erro[0] = null;
            stage.setTitle("Tráfego · " + router.displayName() + " · " + b);
        });
        stage.setTitle("Tráfego · " + router.displayName() + " · " + interfaceInicial);

        // ------------------------ Sondagem ------------------------
        Thread sonda = new Thread(() -> {
            while (rodando.get()) {
                String nome = alvo[0];
                try {
                    long[] c = RouterOsProbe.readCounters(router, nome);
                    long agora = System.currentTimeMillis();
                    if (c == null) {
                        erro[0] = "Interface \"" + nome + "\" não apareceu na leitura.";
                    } else {
                        erro[0] = null;
                        if (anterior[2] > 0) {
                            double[] taxa = taxaEntre(anterior[0], anterior[1], anterior[2],
                                                      c[0], c[1], agora);
                            if (taxa != null) {
                                synchronized (amostras) {
                                    amostras.addLast(taxa);
                                    while (amostras.size() > JANELA) amostras.removeFirst();
                                }
                                pico[0] = Math.max(pico[0], taxa[0]);
                                pico[1] = Math.max(pico[1], taxa[1]);
                            }
                        }
                        anterior[0] = c[0];
                        anterior[1] = c[1];
                        anterior[2] = agora;
                    }
                } catch (IOException ex) {
                    erro[0] = "Sem resposta do roteador: " + ex.getMessage();
                    // Não desiste: o equipamento pode voltar, e a janela existe
                    // justamente para acompanhar isso.
                } catch (RuntimeException ex) {
                    erro[0] = "Falha ao ler: " + ex.getMessage();
                }
                try {
                    Thread.sleep(Math.max(1, intervalo.getValue()) * 1000L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "traffic-monitor");
        sonda.setDaemon(true);

        // ------------------------ Desenho ------------------------
        AnimationTimer timer = new AnimationTimer() {
            private long ultimo;
            @Override public void handle(long now) {
                // 4 quadros por segundo bastam: o dado só muda a cada segundo.
                if (now - ultimo < 250_000_000L) return;
                ultimo = now;

                double[][] dados;
                synchronized (amostras) { dados = amostras.toArray(new double[0][]); }
                desenhar(canvas, dados);

                if (dados.length > 0) {
                    double[] ult = dados[dados.length - 1];
                    rxLabel.setText("RX  " + taxa(ult[0]));
                    txLabel.setText("TX  " + taxa(ult[1]));
                    picoLabel.setText(String.format("pico RX %s  ·  pico TX %s  ·  %d amostras",
                            taxa(pico[0]), taxa(pico[1]), dados.length));
                }
                if (erro[0] != null) {
                    estado.setText(erro[0]);
                    estado.setTextFill(Color.web("#e57373"));
                } else if (dados.length == 0) {
                    estado.setText("Primeira leitura: a taxa aparece na próxima.");
                    estado.setTextFill(Color.web("#aaa"));
                } else {
                    estado.setText("Medindo. Nada é gravado — fechar a janela encerra a medição.");
                    estado.setTextFill(Color.web("#aaa"));
                }
            }
        };

        stage.setOnHidden(e -> {
            rodando.set(false);
            sonda.interrupt();
            timer.stop();
            Log.info("Monitor de tráfego encerrado: %s · %s", router.displayName(), alvo[0]);
        });

        stage.setScene(new Scene(raiz));
        stage.show();
        sonda.start();
        timer.start();
        Log.info("Monitor de tráfego aberto: %s · %s", router.displayName(), interfaceInicial);
    }

    /**
     * Taxa em bits/s entre duas leituras de contador.
     *
     * @return {rxBps, txBps}, ou null quando a amostra nao serve: intervalo
     *         nao positivo, ou contador que andou para tras — o que acontece
     *         quando o equipamento reinicia e zera tudo. Inventar um pico ali
     *         seria pior do que perder uma amostra.
     */
    static double[] taxaEntre(long rxAntes, long txAntes, long msAntes,
                              long rxAgora, long txAgora, long msAgora) {
        double seg = (msAgora - msAntes) / 1000.0;
        if (seg <= 0) return null;
        long dRx = rxAgora - rxAntes;
        long dTx = txAgora - txAntes;
        if (dRx < 0 || dTx < 0) return null;
        return new double[] { dRx * 8.0 / seg, dTx * 8.0 / seg };
    }

    // ------------------------ Gráfico ------------------------

    private static void desenhar(Canvas canvas, double[][] dados) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        gc.setFill(FUNDO);
        gc.fillRect(0, 0, w, h);

        double padL = 62, padR = 8, padT = 10, padB = 18;
        double gw = w - padL - padR, gh = h - padT - padB;

        double max = 0;
        for (double[] d : dados) max = Math.max(max, Math.max(d[0], d[1]));
        // Um piso evita que o ruído de uma interface parada vire montanha.
        double topo = escalaBonita(Math.max(max, 1_000_000));

        gc.setStroke(GRADE);
        gc.setLineWidth(1);
        gc.setFill(Color.web("#999"));
        gc.setFont(Font.font("System", 10));
        for (int i = 0; i <= 4; i++) {
            double y = padT + gh * i / 4.0;
            gc.strokeLine(padL, y, padL + gw, y);
            gc.fillText(taxa(topo * (4 - i) / 4.0), 6, y + 3);
        }

        if (dados.length < 2) {
            gc.setFill(Color.web("#777"));
            gc.fillText("aguardando amostras...", padL + 10, padT + gh / 2);
            return;
        }

        // TX atrás, RX na frente: RX costuma ser o maior num POP, e deixá-lo
        // por cima evita que ele esconda o outro.
        area(gc, dados, 1, TX_COR, padL, padT, gw, gh, topo);
        area(gc, dados, 0, RX_COR, padL, padT, gw, gh, topo);
    }

    private static void area(GraphicsContext gc, double[][] dados, int idx, Color cor,
                             double padL, double padT, double gw, double gh, double topo) {
        int n = dados.length;
        double[] xs = new double[n + 2];
        double[] ys = new double[n + 2];
        for (int i = 0; i < n; i++) {
            xs[i] = padL + gw * i / (double) (n - 1);
            ys[i] = padT + gh * (1 - Math.min(1, dados[i][idx] / topo));
        }
        xs[n] = padL + gw;     ys[n] = padT + gh;
        xs[n + 1] = padL;      ys[n + 1] = padT + gh;

        gc.setFill(cor.deriveColor(0, 1, 1, 0.22));
        gc.fillPolygon(xs, ys, n + 2);
        gc.setStroke(cor);
        gc.setLineWidth(1.6);
        gc.strokePolyline(xs, ys, n);
    }

    /** Arredonda o teto do eixo para 1, 2 ou 5 vezes uma potência de dez. */
    static double escalaBonita(double v) {
        if (v <= 0) return 1;
        double exp = Math.pow(10, Math.floor(Math.log10(v)));
        double m = v / exp;
        double passo = m <= 1 ? 1 : m <= 2 ? 2 : m <= 5 ? 5 : 10;
        return passo * exp;
    }

    /** bits/s em unidade legível. */
    static String taxa(double bps) {
        if (bps >= 1e9) return String.format("%.2f Gbps", bps / 1e9);
        if (bps >= 1e6) return String.format("%.1f Mbps", bps / 1e6);
        if (bps >= 1e3) return String.format("%.0f kbps", bps / 1e3);
        return String.format("%.0f bps", bps);
    }
}
