package com.colmeia.radiomapper.ui;

import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.stage.WindowEvent;

/**
 * Um menu de contexto por vez, em toda a aplicação.
 *
 * <h3>O que isto resolve</h3>
 * Cada pedido de menu monta um {@link ContextMenu} novo. Mostrar o novo não
 * fecha o antigo, então eles se empilham na tela: no terceiro clique havia
 * três menus abertos, um por cima do outro.
 *
 * O incômodo visual é o de menos. Os menus do mapa são <b>por objeto</b> — o
 * deste ponto, o daquele feixe — e um menu velho continua carregando as ações
 * do ponto onde foi aberto. Com dois na tela, clicar no que está por cima pode
 * apagar ou mover o ponto errado, e nada na tela avisa disso.
 *
 * <h3>Por que um só, e estático</h3>
 * Não existe situação em que dois menus de contexto abertos ao mesmo tempo
 * sejam úteis: o gesto que abre o segundo é o mesmo que deveria dispensar o
 * primeiro. Guardar isso num lugar só faz o mapa e as listas laterais se
 * comportarem igual, inclusive entre si — abrir o menu de um rádio na lista
 * fecha o que ficou aberto no mapa.
 */
public final class Menus {

    private static ContextMenu aberto;

    private Menus() {}

    /**
     * Mostra este menu, fechando o que estiver aberto.
     *
     * @param ancora nó a que o menu se prende; a posição vem em coordenadas
     *               de TELA, como o evento entrega
     */
    public static void mostrar(ContextMenu menu, Node ancora, double telaX, double telaY) {
        fechar();
        if (menu == null || ancora == null) return;
        aberto = menu;
        // Quem fecha pode ser o proprio JavaFX (clique fora, Esc, escolha de
        // um item). Sem ouvir isso, a referencia ficaria apontando para um
        // menu ja fechado e o proximo fechar() nao teria efeito nenhum.
        menu.addEventHandler(WindowEvent.WINDOW_HIDDEN, e -> {
            if (aberto == menu) aberto = null;
        });
        menu.show(ancora, telaX, telaY);
    }

    /** Fecha o menu aberto, se houver. */
    public static void fechar() {
        ContextMenu m = aberto;
        aberto = null;
        if (m != null && m.isShowing()) m.hide();
    }

    /** Há menu de contexto aberto? Serve a quem precisa não competir com ele. */
    public static boolean aberto() {
        return aberto != null && aberto.isShowing();
    }
}
