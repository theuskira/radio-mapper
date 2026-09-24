package com.colmeia.radiomapper.ui;

import com.colmeia.radiomapper.geo.ElevationChain;
import com.colmeia.radiomapper.geo.Mercator;
import com.colmeia.radiomapper.model.Link;
import com.colmeia.radiomapper.model.NetworkPoint;
import com.colmeia.radiomapper.model.Project;
import com.colmeia.radiomapper.model.Radio;
import com.colmeia.radiomapper.rf.LinkBudget;
import com.colmeia.radiomapper.util.Log;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * Desenha um enlace à mão, para planejar antes de existir rádio no ar.
 *
 * <h3>Por que isto existe</h3>
 * Até aqui um enlace só nascia da descoberta por SSH: o rádio era sondado,
 * dizia quem eram seus vizinhos, e a linha aparecia no mapa. Isso cobre a
 * rede que já está de pé e não cobre a pergunta que vem antes dela —
 * <i>"se eu puser uma antena naquele morro, o enlace fecha?"</i>. Pior: a
 * sincronização REMOVE todo enlace que os rádios não confirmam, então nem
 * adiantaria forjar um à mão sem marcá-lo como planejado.
 *
 * O cálculo, esse já existia inteiro: o perfil contra o relevo e o
 * {@link LinkBudget} nunca precisaram de conexão nenhuma, só de dois rádios
 * com coordenada e dados de RF. O que faltava era poder dizer quais são os
 * dois.
 *
 * <h3>O que o diálogo faz e o que não faz</h3>
 * Ele só cria e remove o par. A simulação — terreno, Fresnel, alinhamento
 * das antenas, sinal estimado — aparece no painel de perfil ao selecionar
 * qualquer um dos dois rádios, exatamente como num enlace real. Assim há um
 * lugar só onde se lê um enlace, e não dois que podem discordar.
 */
public final class PlannedLinkDialog {

    private PlannedLinkDialog() {}

    /** Um rádio do projeto, com o ponto a que pertence, pronto para a lista. */
    private record Entry(NetworkPoint point, Radio radio) {
        @Override public String toString() {
            String r = radio.getName() == null || radio.getName().isBlank()
                    ? (radio.getHost() == null || radio.getHost().isBlank() ? "rádio sem nome" : radio.getHost())
                    : radio.getName();
            return point.getName() + "  ·  " + r;
        }
    }

    /**
     * @return true se o projeto mudou (enlace criado ou removido)
     */
    public static boolean show(Window owner, Project project, ElevationChain elevation) {
        List<Entry> radios = new ArrayList<>();
        for (NetworkPoint p : project.getPoints()) {
            for (Radio r : p.getRadios()) radios.add(new Entry(p, r));
        }

        Dialog<ButtonType> dlg = new Dialog<>();
        if (owner != null) dlg.initOwner(owner);
        dlg.setTitle("Enlaces planejados");
        dlg.setHeaderText("Simular um enlace antes de existir rádio no ar");
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        if (radios.size() < 2) {
            Label vazio = new Label("Este projeto tem menos de dois rádios.\n\n"
                    + "Para planejar um enlace, crie os dois pontos e ponha um rádio "
                    + "em cada um, com frequência, ganho e potência preenchidos. "
                    + "Eles não precisam existir de verdade nem estar acessíveis.");
            vazio.setWrapText(true);
            vazio.setMaxWidth(430);
            VBox box = new VBox(vazio);
            box.setPadding(new Insets(14));
            box.setPrefWidth(470);
            dlg.getDialogPane().setContent(box);
            dlg.showAndWait();
            return false;
        }

        // ------------------------ Criar ------------------------
        ComboBox<Entry> ladoA = new ComboBox<>();
        ComboBox<Entry> ladoB = new ComboBox<>();
        ladoA.getItems().setAll(radios);
        ladoB.getItems().setAll(radios);
        ladoA.setPrefWidth(250);
        ladoB.setPrefWidth(250);
        ladoA.getSelectionModel().select(0);
        ladoB.getSelectionModel().select(1);

        Label previa = new Label();
        previa.setWrapText(true);
        previa.setMaxWidth(440);
        previa.setStyle("-fx-font-size: 11;");

        Button criar = new Button("Criar enlace planejado");

        // ------------------------ Lista dos existentes ------------------------
        ListView<Link> lista = new ListView<>();
        lista.setPrefHeight(130);
        lista.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(Link l, boolean empty) {
                super.updateItem(l, empty);
                setText(empty || l == null ? null : descreve(project, l));
            }
        });
        Button remover = new Button("Remover");
        remover.setDisable(true);
        lista.getSelectionModel().selectedItemProperty().addListener(
                (o, a, b) -> remover.setDisable(b == null));

        boolean[] mudou = { false };

        Runnable recarregaLista = () -> {
            List<Link> planejados = new ArrayList<>();
            for (Link l : project.getLinks()) if (l.isPlanned()) planejados.add(l);
            lista.getItems().setAll(planejados);
        };
        recarregaLista.run();

        Runnable atualizaPrevia = () -> {
            Entry a = ladoA.getValue(), b = ladoB.getValue();
            if (a == null || b == null) { previa.setText(""); criar.setDisable(true); return; }
            if (a.radio() == b.radio()) {
                previa.setText("Escolha dois rádios diferentes.");
                criar.setDisable(true);
                return;
            }
            Link jaExiste = achaLink(project, a.radio().getId(), b.radio().getId());
            if (jaExiste != null) {
                previa.setText(jaExiste.isPlanned()
                        ? "Estes dois já têm um enlace planejado."
                        : "Estes dois já têm um enlace descoberto pelos rádios — "
                          + "não faz sentido planejar o que já está no ar.");
                criar.setDisable(true);
                return;
            }
            criar.setDisable(false);
            previa.setText(resumo(project, a, b, elevation));
        };
        ladoA.valueProperty().addListener((o, x, y) -> atualizaPrevia.run());
        ladoB.valueProperty().addListener((o, x, y) -> atualizaPrevia.run());
        atualizaPrevia.run();

        criar.setOnAction(e -> {
            Entry a = ladoA.getValue(), b = ladoB.getValue();
            if (a == null || b == null || a.radio() == b.radio()) return;
            String ida = a.radio().getId(), idb = b.radio().getId();
            // Mesma ordenação que a descoberta usa, para que ela reconheça o
            // par e promova o plano a enlace real quando os rádios subirem.
            Link l = new Link(ida.compareTo(idb) < 0 ? ida : idb,
                              ida.compareTo(idb) < 0 ? idb : ida, 0);
            l.setOrigin(com.colmeia.radiomapper.model.LinkOrigin.PLANNED);
            l.setStale(false);
            project.getLinks().add(l);
            mudou[0] = true;
            recarregaLista.run();
            atualizaPrevia.run();
            Log.info("Enlace planejado criado: %s <-> %s", a, b);
        });

        remover.setOnAction(e -> {
            Link l = lista.getSelectionModel().getSelectedItem();
            if (l == null) return;
            project.getLinks().remove(l);
            mudou[0] = true;
            recarregaLista.run();
            atualizaPrevia.run();
            Log.info("Enlace planejado removido: %s", descreve(project, l));
        });

        GridPane g = new GridPane();
        g.setHgap(8); g.setVgap(6);
        int r = 0;
        g.add(new Label("De:"), 0, r); g.add(ladoA, 1, r++);
        g.add(new Label("Para:"), 0, r); g.add(ladoB, 1, r++);
        g.add(criar, 1, r++);

        Label ajuda = new Label("O enlace planejado aparece tracejado no mapa e sobrevive à "
                + "sincronização. Selecione um dos dois rádios para ver o perfil contra o "
                + "relevo, a zona de Fresnel e o sinal estimado. Quando os rádios subirem e "
                + "se enxergarem, ele vira um enlace normal sozinho.");
        ajuda.setWrapText(true);
        ajuda.setMaxWidth(440);
        ajuda.setStyle("-fx-text-fill: #666; -fx-font-size: 11;");

        HBox linhaLista = new HBox(8, lista, remover);
        HBox.setHgrow(lista, Priority.ALWAYS);

        VBox box = new VBox(10,
                g, previa,
                new Separator(),
                new Label("Enlaces planejados neste projeto:"), linhaLista,
                new Separator(), ajuda);
        box.setPadding(new Insets(12));
        box.setPrefWidth(500);
        dlg.getDialogPane().setContent(box);
        dlg.showAndWait();
        return mudou[0];
    }

    // ------------------------ Apoio ------------------------

    static Link achaLink(Project project, String idA, String idB) {
        for (Link l : project.getLinks()) {
            boolean mesmoPar = (idA.equals(l.getRadioAId()) && idB.equals(l.getRadioBId()))
                            || (idB.equals(l.getRadioAId()) && idA.equals(l.getRadioBId()));
            if (mesmoPar) return l;
        }
        return null;
    }

    private static String descreve(Project project, Link l) {
        String a = nome(project, l.getRadioAId());
        String b = nome(project, l.getRadioBId());
        return a + "  ↔  " + b;
    }

    private static String nome(Project project, String radioId) {
        return project.findRadioById(radioId)
                .map(rr -> {
                    String n = rr.getName();
                    if (n != null && !n.isBlank()) return n;
                    return rr.getHost() == null || rr.getHost().isBlank() ? radioId : rr.getHost();
                })
                .orElse("(rádio removido)");
    }

    /**
     * Prévia do que o enlace seria: distância e, quando há dados de RF nos
     * dois lados, o sinal que a física permite.
     *
     * Serve para não criar o enlace às cegas. A análise completa fica no
     * painel de perfil, que tem o relevo em mãos.
     */
    private static String resumo(Project project, Entry a, Entry b, ElevationChain elevation) {
        double dx = b.point().getX() - a.point().getX();
        double dy = b.point().getY() - a.point().getY();
        double mundo = Math.hypot(dx, dy);
        if (mundo <= 0) return "Os dois rádios estão no mesmo ponto do mapa.";

        // Em Mercator a distância infla com a latitude; sem corrigir, 10 km no
        // sul do Brasil viram quase 11.
        double k = project.isMapMode()
                ? Mercator.groundScaleAt(Mercator.latOfWorldY((a.point().getY() + b.point().getY()) / 2))
                : project.getMetersPerPixel();
        double metros = mundo * (k <= 0 ? 1 : k);

        double azimute = (Math.toDegrees(Math.atan2(dx / mundo, -dy / mundo)) + 360) % 360;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Distância %.2f km, azimute %.0f°.", metros / 1000, azimute));

        if (a.radio().hasRfData() && b.radio().hasRfData()) {
            LinkBudget.Result rb = LinkBudget.compute(a.radio(), b.radio(), metros, 0, 0, 0, 0);
            if (rb.valid()) {
                sb.append(String.format("  Sinal estimado em visada limpa: %.0f dBm (%s).",
                        rb.rssiAtoB(), LinkBudget.quality(rb.rssiAtoB())));
            }
        } else {
            sb.append("  Falta ganho, potência ou frequência em um dos rádios — "
                    + "dá para criar assim mesmo, mas sem estimativa de sinal.");
        }
        if (!elevation.hasAny()) {
            sb.append("  Sem fonte de relevo carregada, o perfil não vai mostrar o terreno.");
        }
        return sb.toString();
    }
}
