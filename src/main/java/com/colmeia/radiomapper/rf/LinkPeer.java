package com.colmeia.radiomapper.rf;

import com.colmeia.radiomapper.geo.ElevationSource;
import com.colmeia.radiomapper.model.Link;
import com.colmeia.radiomapper.model.NetworkPoint;
import com.colmeia.radiomapper.model.Project;
import com.colmeia.radiomapper.model.Radio;

/**
 * Quem está do outro lado deste rádio — perguntando ao projeto em vez de ao usuário.
 *
 * <h3>Por que isto existe</h3>
 * O alcance de uma antena só faz sentido em relação a quem recebe, e a tela da
 * simulação começava pedindo esse número. Só que, na maior parte dos casos, o
 * programa já sabe: a estação tem o AP informado no cadastro, o enlace ponto a
 * ponto tem o par do outro lado, e o AP tem as estações associadas. Perguntar
 * o que já está no projeto é fazer o usuário digitar de novo um dado que ele
 * já deu — e, pior, digitar errado.
 *
 * <h3>Qual par escolher quando há vários</h3>
 * Um setor pode atender dezenas de estações com antenas diferentes. A
 * escolhida é a de MENOR ganho: a cobertura útil de um AP é a que alcança o
 * cliente mais fraco que ele precisa atender. Usar a melhor antena da lista
 * daria um mapa de cobertura que só vale para um cliente privilegiado.
 */
public final class LinkPeer {

    private LinkPeer() {}

    /**
     * Altura de receptor assumida quando não se sabe quem está do outro lado.
     *
     * Cinco metros é a ordem de um CPE em telhado residencial. Serve de chute
     * e só de chute: num enlace entre torres ela erra por dezenas de metros,
     * e altura de receptor é o que mais mexe no alcance, porque decide o que
     * enxerga por cima do morro.
     */
    public static final double ALTURA_PADRAO_M = 5;

    /**
     * @param nome    como chamar o par na tela
     * @param gainDbi ganho da antena dele
     * @param cableDb perda de cabo dele
     * @param alturaM altura da antena dele acima do solo
     * @param origem  de onde saiu esta informação, em português
     * @param real    true quando veio de um rádio do projeto; false quando é
     *                apenas o palpite pelo papel
     */
    public record Peer(String nome, double gainDbi, double cableDb, double alturaM,
                       String origem, boolean real) {}

    /**
     * Acha o par deste rádio, ou devolve o palpite do papel.
     *
     * Nunca devolve null: sempre há uma resposta, o que muda é se ela veio do
     * projeto ou de uma suposição — e {@link Peer#real()} diz qual foi.
     */
    public static Peer of(Radio r, Project project) {
        return of(r, project, null);
    }

    /**
     * @param elev fonte de relevo, para resolver a altura de um par cadastrado
     *             com altitude absoluta. Sem ela, esse caso cai na altura
     *             padrão em vez de inventar um número.
     */
    public static Peer of(Radio r, Project project, ElevationSource elev) {
        if (project != null) {
            Radio escolhido = buscar(r, project);
            if (escolhido != null) {
                return new Peer(nomeDe(escolhido),
                        escolhido.getAntennaGainDbi(), escolhido.getCableLossDb(),
                        alturaAcimaDoSolo(escolhido, project, elev),
                        origemDe(r, escolhido, project), true);
            }
        }
        double g = r.getRole().peerGainGuessDbi(r.getAntennaGainDbi());
        return new Peer("—", g, r.getCableLossDb(), ALTURA_PADRAO_M,
                "sem par no projeto; " + r.getRole().peerGuessNote(), false);
    }

    /**
     * Quanto a antena do par está acima do chão dela.
     *
     * É o número que a simulação precisa: ela caminha pelo terreno e pergunta
     * "a que altura estaria o receptor aqui". Num rádio cadastrado com altura
     * de instalação a resposta é direta; com altitude absoluta é preciso
     * descontar o solo sob ele, e sem relevo não há como — aí vale o padrão,
     * que é o que menos mente.
     */
    private static double alturaAcimaDoSolo(Radio par, Project project, ElevationSource elev) {
        if (!par.isAbsoluteAltitude()) {
            double h = par.getAntennaHeightM();
            return h > 0 ? h : ALTURA_PADRAO_M;
        }
        if (elev == null) return ALTURA_PADRAO_M;
        var ponto = project.findPointOfRadio(par.getId()).orElse(null);
        if (ponto == null) return ALTURA_PADRAO_M;
        Double solo = elev.elevationAt(ponto.getX(), ponto.getY());
        if (solo == null) return ALTURA_PADRAO_M;
        double h = par.heightAboveGroundM(solo);
        return h > 0 ? h : ALTURA_PADRAO_M;
    }

    /**
     * O rádio do outro lado em pessoa, ou null.
     *
     * {@link #of} devolve os números do par — ganho, cabo, altura — que é
     * o bastante para a varredura de alcance. Quem precisa apontar uma antena
     * PARA ele precisa de mais: onde ele está, a que cota, com que
     * potência. Em vez de duplicar a busca lá fora (e arriscar escolher
     * outro par que o desta tela), o mesmo critério atende os dois.
     */
    public static Radio radioDe(Radio r, Project project) {
        return project == null ? null : buscar(r, project);
    }

    /**
     * O rádio do outro lado, se o projeto souber.
     *
     * Ordem: o AP informado à mão vale mais que qualquer inferência, porque
     * alguém afirmou. Depois vêm os enlaces, e entre eles o par mais fraco.
     */
    private static Radio buscar(Radio r, Project project) {
        if (r.hasManualUplink()) {
            Radio ap = project.findRadioById(r.getUplinkRadioId()).orElse(null);
            if (ap != null && temAntena(ap)) return ap;
        }

        Radio pior = null;
        for (Link l : project.getLinks()) {
            String outroId = null;
            if (r.getId().equals(l.getRadioAId())) outroId = l.getRadioBId();
            else if (r.getId().equals(l.getRadioBId())) outroId = l.getRadioAId();
            if (outroId == null) continue;

            Radio outro = project.findRadioById(outroId).orElse(null);
            if (outro == null || !temAntena(outro)) continue;
            if (pior == null || outro.getAntennaGainDbi() < pior.getAntennaGainDbi()) pior = outro;
        }
        return pior;
    }

    /** Sem ganho informado o rádio não serve de referência — seria assumir 0 dBi. */
    private static boolean temAntena(Radio r) {
        return r.getAntennaGainDbi() != 0;
    }

    private static String origemDe(Radio r, Radio par, Project project) {
        if (r.hasManualUplink() && par.getId().equals(r.getUplinkRadioId())) {
            return "AP informado no cadastro desta estação";
        }
        int quantos = 0;
        for (Link l : project.getLinks()) {
            if (r.getId().equals(l.getRadioAId()) || r.getId().equals(l.getRadioBId())) quantos++;
        }
        return quantos > 1
                ? "par mais fraco entre os " + quantos + " enlaces deste rádio"
                : "o rádio do outro lado do enlace";
    }

    private static String nomeDe(Radio r) {
        if (r.getName() != null && !r.getName().isBlank()) return r.getName();
        return r.getHost() == null || r.getHost().isBlank() ? "rádio" : r.getHost();
    }

    /** Ponto onde este rádio está, para compor o rótulo. */
    public static String pontoDe(Radio r, Project project) {
        if (project == null) return "";
        return project.findPointOfRadio(r.getId()).map(NetworkPoint::getName).orElse("");
    }
}
