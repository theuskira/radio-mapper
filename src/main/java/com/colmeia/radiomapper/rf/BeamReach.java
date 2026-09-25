package com.colmeia.radiomapper.rf;

import com.colmeia.radiomapper.model.Radio;

/**
 * Até onde desenhar o feixe de um rádio, e de onde esse número saiu.
 *
 * <h3>O problema</h3>
 * O alcance do cadastro é um número que alguém digitou. Quando existe, manda —
 * é a intenção declarada de quem montou o projeto. Mas um ponto de acesso
 * recém-cadastrado costuma vir com zero ali, e até agora isso o fazia
 * desaparecer: sem estação associada não havia enlace para perfilar, e sem
 * alcance digitado não havia setor para desenhar. O rádio existia no projeto e
 * não aparecia em lugar nenhum.
 *
 * <h3>De onde tirar o número quando ninguém digitou</h3>
 * Em ordem de qualidade, a mesma lógica da cadeia de altitude:
 *
 * <ol>
 *   <li><b>Simulado</b> — a varredura que já olhou o terreno. É o número bom, e
 *       existe quando alguém rodou "Alcance" para este rádio.</li>
 *   <li><b>Orçamento</b> — potência, ganhos e frequência, sem terreno nenhum.
 *       É um teto: no projeto de teste, um AP que o orçamento levava a 909 m
 *       alcançava 48 m depois de o relevo entrar. Serve para dizer "por aqui",
 *       e é por isso que quem desenha a partir daqui deve marcar que é
 *       estimativa.</li>
 * </ol>
 *
 * Sem dado de RF não há o que estimar, e aí o rádio continua sem feixe — o que
 * está certo: um rádio sem frequência, ganho e potência não permite afirmar
 * nada sobre alcance.
 */
public final class BeamReach {

    private BeamReach() {}

    /** De onde veio o alcance — muda o que a tela pode afirmar. */
    public enum Origem {
        /** Digitado no cadastro. */
        CADASTRO("do cadastro"),
        /** Da varredura que considerou o relevo. */
        SIMULADO("simulado, com relevo"),
        /** Só do orçamento de enlace, sem relevo. */
        ORCAMENTO("estimado pelo orçamento, sem relevo"),
        /** Não há como saber. */
        NENHUM("sem alcance");

        private final String rotulo;

        Origem(String rotulo) { this.rotulo = rotulo; }

        /** {@code true} quando o número NÃO considerou o terreno. */
        public boolean otimista() { return this == ORCAMENTO; }

        @Override public String toString() { return rotulo; }
    }

    /**
     * @param metros alcance a desenhar, em metros de chão. 0 = não desenhar.
     */
    public record Alcance(double metros, Origem origem) {
        public boolean vale() { return metros > 0; }
    }

    /**
     * Quanto desenhar para este rádio.
     *
     * @param simuladoM alcance já apurado pela varredura, ou {@code null} se
     *                  ninguém simulou este rádio ainda
     */
    public static Alcance de(Radio r, Double simuladoM) {
        if (r == null || r.getBeamWidthDeg() <= 0) {
            return new Alcance(0, Origem.NENHUM);
        }
        // O cadastro tem a palavra: e' a intencao declarada, e trocar por uma
        // conta faria o desenho de projetos existentes mudar sozinho.
        if (r.getBeamRangeM() > 0) {
            return new Alcance(r.getBeamRangeM(), Origem.CADASTRO);
        }
        if (simuladoM != null && simuladoM > 0) {
            return new Alcance(simuladoM, Origem.SIMULADO);
        }
        if (!r.hasRfData()) return new Alcance(0, Origem.NENHUM);

        // O palpite do orcamento so vale para quem IRRADIA para procurar
        // cliente. Uma estacao aponta para o seu AP, e o que interessa nela e'
        // o enlace, nao um setor: no projeto de teste, estimar a estacao daria
        // uma cunha de 16 km atravessando o mapa para dizer o que a linha do
        // enlace ja dizia melhor.
        if (!r.getRole().isAp()) return new Alcance(0, Origem.NENHUM);

        double orc = BeamCoverage.reachEstimateM(r, BeamCoverage.Params.padrao(r));
        return orc > 0 ? new Alcance(orc, Origem.ORCAMENTO) : new Alcance(0, Origem.NENHUM);
    }

    /**
     * Até onde o SINAL vai — para desenhar o feixe, e não o setor do cadastro.
     *
     * <h3>Por que a ordem aqui é outra</h3>
     * {@link #de} dá prioridade ao cadastro, e com razão: no mapa ele é a
     * intenção declarada de quem montou o projeto, e trocá-la por uma conta
     * mudaria o desenho de projetos existentes sem ninguém pedir.
     *
     * O feixe em 3D é outra pergunta. Ali se está olhando o sinal atravessar o
     * relevo, e o número do cadastro não tem nada a ver com isso: neste
     * projeto um AP cadastrado com 40 m alcança 2496 m quando a conta é feita.
     * Um cone de 40 m saindo de uma antena que cobre 2,5 km não é um desenho
     * conservador — é um desenho errado.
     *
     * Então aqui vale o melhor número DE SINAL disponível: a varredura, que
     * olhou o terreno; senão o orçamento de enlace, que é um teto e vai ser
     * cortado pelo relevo na hora de desenhar. O cadastro só entra quando não
     * há dado de RF para calcular coisa alguma.
     */
    public static Alcance paraSinal(Radio r, Double simuladoM) {
        if (r == null || r.getBeamWidthDeg() <= 0) {
            return new Alcance(0, Origem.NENHUM);
        }
        if (simuladoM != null && simuladoM > 0) {
            return new Alcance(simuladoM, Origem.SIMULADO);
        }
        if (r.hasRfData()) {
            double orc = BeamCoverage.reachEstimateM(r, BeamCoverage.Params.padrao(r));
            if (orc > 0) return new Alcance(orc, Origem.ORCAMENTO);
        }
        if (r.getBeamRangeM() > 0) {
            return new Alcance(r.getBeamRangeM(), Origem.CADASTRO);
        }
        return new Alcance(0, Origem.NENHUM);
    }

    /** Há como desenhar algum feixe para este rádio? */
    public static boolean temAlgum(Radio r, Double simuladoM) {
        return de(r, simuladoM).vale();
    }
}
