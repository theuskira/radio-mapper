package com.colmeia.radiomapper.rf;

import com.colmeia.radiomapper.geo.ElevationSource;
import com.colmeia.radiomapper.model.Radio;

import java.util.ArrayList;
import java.util.List;

/**
 * Como apontar um ponto de acesso, perguntando ao terreno — sem precisar de
 * estação nenhuma do outro lado.
 *
 * <h3>Por que é uma pergunta diferente da da estação</h3>
 * {@link StationAim} otimiza UM enlace: existe um AP do outro lado, e há uma
 * resposta certa para onde apontar. Um AP multiponto não tem outro lado. O que
 * ele tem é chão: o que se pode escolher é quanto chão ele alcança, e isso só
 * o relevo responde.
 *
 * Por isso o critério aqui é <b>área que realmente recebe sinal</b> — a grade
 * da {@link BeamCoverage}, com os buracos de sombra descontados —, e não
 * alcance no eixo. Alcance no eixo mede uma direção; área mede o setor todo, e
 * é ela que muda quando o morro entra na frente.
 *
 * <h3>Sem estação conectada</h3>
 * Funciona igual. O que a varredura precisa saber do outro lado é a
 * sensibilidade e o ganho de quem recebe, e isso vem do papel do rádio quando
 * não há par cadastrado: um AP multiponto assume um CPE comum. A recomendação
 * então vale para "um cliente típico", que é exatamente a pergunta de quem
 * ainda vai vender a primeira assinatura naquele setor.
 *
 * <h3>O que este método NÃO faz</h3>
 * <ul>
 *   <li><b>Não sabe onde estão os clientes.</b> Ele maximiza área coberta.
 *       Se a maior área for um vale sem ninguém, ele vai apontar para o vale.
 *       Quem conhece a região manda mais que esta conta.</li>
 *   <li><b>Não otimiza tudo junto.</b> Testar todas as combinações de altura,
 *       azimute e inclinação seria o produto dos três, e cada teste é uma
 *       varredura do terreno inteiro. Aqui se escolhe um de cada vez, na
 *       ordem em que um decide o outro: altura primeiro (é ela que diz o que
 *       passa por cima do morro), depois azimute, depois inclinação, cada um
 *       já com os anteriores escolhidos. É guloso, e pode parar num arranjo
 *       bom em vez do ótimo.</li>
 * </ul>
 */
public final class ApAim {

    private ApAim() {}

    /** Ganho de área abaixo do qual subir a torre não se paga. */
    private static final double GANHO_MINIMO = 0.08;

    /**
     * Ganho de área abaixo do qual não vale mandar girar a antena.
     *
     * Girar um setor é subir na torre com chave na mão. Dois por cento de
     * área a mais não paga a viagem, e uma tela que manda mexer por isso
     * ensina o usuário a ignorá-la. Na primeira versão isto faltava, e a
     * recomendação saiu mandando mudar a inclinação de 0° para −2° para
     * ganhar exatamente 0%.
     */
    private static final double GANHO_MINIMO_MIRA = 0.02;

    /** Um valor testado e o que ele cobriu. */
    public record Opcao(double valor, double areaM2, double alcanceMedioM) {}

    /**
     * Um campo varrido, com tudo que se testou nele.
     *
     * @param melhor valor escolhido; igual a {@code atual} quando mexer não paga
     */
    public record Eixo(String campo, String unidade, double atual, double melhor,
                       double areaAtualM2, double areaMelhorM2,
                       List<Opcao> opcoes, String porque) {

        public boolean muda() { return Math.abs(melhor - atual) > 1e-6; }

        /** Quanto a mudança rende, em %. */
        public double ganhoPct() {
            return areaAtualM2 <= 0 ? Double.NaN
                    : 100 * (areaMelhorM2 - areaAtualM2) / areaAtualM2;
        }
    }

    /**
     * @param varreduras quantas vezes o terreno foi varrido — o custo, para a
     *                   tela poder avisar antes em vez de só congelar
     */
    public record Plano(boolean vale, String nota, double areaInicialM2,
                        double areaFinalM2, List<Eixo> eixos, int varreduras) {

        public boolean temMudanca() {
            for (Eixo e : eixos) if (e.muda()) return true;
            return false;
        }
    }

    private static Plano naoDa(String nota) {
        return new Plano(false, nota, 0, 0, List.of(), 0);
    }

    /**
     * Varre o cenário e diz o que mexer neste AP.
     *
     * @param tetoSubidaM quanto se admite subir a antena acima de onde está
     * @param origemDoPar de onde saiu a suposição sobre quem recebe, para a
     *                    nota poder dizer em cima de quê a conta foi feita
     */
    public static Plano planejar(Radio r, double cx, double cy, ElevationSource elev,
                                 double worldPerMeter, BeamCoverage.Params prm,
                                 double tetoSubidaM, String origemDoPar) {
        return planejar(r, cx, cy, elev, worldPerMeter, prm, tetoSubidaM, origemDoPar, null);
    }

    /**
     * @param progresso avisado a cada campo varrido, para a tela poder dizer
     *                  em que pe esta. Isto leva dezenas de varreduras do
     *                  terreno -- uns 20 s no projeto de teste --, e uma
     *                  janela muda por 20 s se le como travada.
     */
    public static Plano planejar(Radio r, double cx, double cy, ElevationSource elev,
                                 double worldPerMeter, BeamCoverage.Params prm,
                                 double tetoSubidaM, String origemDoPar,
                                 java.util.function.Consumer<String> progresso) {
        if (r == null) return naoDa("Sem rádio.");
        if (!r.getRole().isAp()) {
            return naoDa("Esta recomendação é de ponto de acesso: ela mede "
                    + "área coberta. Uma estação não cobre área, "
                    + "ela fala com um AP — use \"Recomendar apontamento\".");
        }
        if (!r.hasRfData()) {
            return naoDa("Faltam dados de RF (frequência, ganho, potência): sem "
                    + "eles não há o que varrer.");
        }

        Radio c = new Radio();
        copiarRf(r, c);
        int[] contas = { 0 };

        List<Eixo> eixos = new ArrayList<>();

        // ---------------- 1) altura ----------------
        // Primeiro porque e' ela que decide o que passa por cima do relevo:
        // escolher azimute com a antena baixa e' escolher entre sombras.
        //
        // A conta é a de BeamCoverage.recomendarAltura, e não uma cópia dela:
        // "até onde vale subir esta torre" é uma pergunta só, e duas
        // implementações dela acabariam respondendo diferente.
        avisar(progresso, "Testando alturas...");
        double hAtual = c.getAntennaHeightM();
        int degraus = 6;
        BeamCoverage.Recomendacao rec = BeamCoverage.recomendarAltura(
                c, cx, cy, elev, worldPerMeter, prm, hAtual + tetoSubidaM, degraus);
        contas[0] += degraus;

        List<Opcao> alturas = new ArrayList<>();
        for (BeamCoverage.Degrau d : rec.degraus()) {
            alturas.add(new Opcao(d.alturaM(), d.areaM2(), d.alcanceMedioM()));
        }
        double hEscolhida = rec.valid() ? rec.recomendadaM() : hAtual;
        c.setAntennaHeightM(hEscolhida);
        eixos.add(eixo("Altura", "m", hAtual, hEscolhida, alturas,
                hEscolhida > hAtual + 0.01
                    ? String.format("cada metro até aí ainda rende mais de %.0f%% "
                            + "de área; acima disso, não", GANHO_MINIMO * 100)
                    : "subir rende pouco aqui — o que limita não é a altura"));

        // ---------------- 2) azimute ----------------
        avisar(progresso, "Testando azimutes...");
        double azAtual = c.getBeamAzimuthDeg();
        if (c.getBeamWidthDeg() >= 360) {
            eixos.add(new Eixo("Azimute", "°", azAtual, azAtual,
                    alturas.get(0).areaM2(), alturas.get(0).areaM2(), List.of(),
                    "antena omni: não há para onde apontar"));
        } else {
            List<Opcao> azimutes = new ArrayList<>();
            // O valor ATUAL entra na lista. Sem ele, "a área de hoje" acabava
            // sendo a do vizinho mais próximo testado, e a comparação que
            // decide se vale mexer era feita contra outro arranjo.
            c.setBeamAzimuthDeg(azAtual);
            azimutes.add(medir(azAtual, c, cx, cy, elev, worldPerMeter, prm, contas));
            for (int i = 0; i < 12; i++) {
                double az = i * 30.0;
                if (Math.abs(az - azAtual) < 1) continue;
                c.setBeamAzimuthDeg(az);
                azimutes.add(medir(az, c, cx, cy, elev, worldPerMeter, prm, contas));
            }
            double azGrosso = melhorDe(azimutes, azAtual, 0);
            // Refina em volta do melhor: de 30 em 30 graus se acha o lado
            // certo do vale, nao a direcao.
            for (double d : new double[] { -20, -10, 10, 20 }) {
                double az = ((azGrosso + d) % 360 + 360) % 360;
                c.setBeamAzimuthDeg(az);
                azimutes.add(medir(az, c, cx, cy, elev, worldPerMeter, prm, contas));
            }
            double azEscolhido = melhorDe(azimutes, azAtual, GANHO_MINIMO_MIRA);
            c.setBeamAzimuthDeg(azEscolhido);
            eixos.add(eixo("Azimute", "°", azAtual, azEscolhido, azimutes,
                    Math.abs(azEscolhido - azAtual) < 1e-6
                        ? "já está na melhor direção testada, ou perto o bastante "
                          + "para girar a antena não se pagar"
                        : "é a direção que cobre mais chão daqui — "
                          + "confira se é também onde estão os clientes"));
        }

        // ---------------- 3) inclinacao ----------------
        avisar(progresso, "Testando inclinacoes...");
        double tiltAtual = c.getBeamTiltDeg();
        List<Opcao> tilts = new ArrayList<>();
        c.setBeamTiltDeg(tiltAtual);
        tilts.add(medir(tiltAtual, c, cx, cy, elev, worldPerMeter, prm, contas));
        for (int i = 0; i < 9; i++) {
            double t = -20 + i * 3.0;
            if (Math.abs(t - tiltAtual) < 0.5) continue;
            c.setBeamTiltDeg(t);
            tilts.add(medir(t, c, cx, cy, elev, worldPerMeter, prm, contas));
        }
        double tEscolhido = melhorDe(tilts, tiltAtual, GANHO_MINIMO_MIRA);
        c.setBeamTiltDeg(tEscolhido);
        eixos.add(eixo("Inclinação", "°", tiltAtual, tEscolhido, tilts,
                Math.abs(tEscolhido - tiltAtual) < 1e-6
                    ? "a inclinação de agora já cobre o que dá para cobrir"
                    : tEscolhido < tiltAtual
                        ? "apontar mais para baixo põe o feixe no chão que interessa"
                        : "levantar o feixe alcança mais longe sem perder o perto"));

        // ---------------- 4) potencia, so como informacao ----------------
        avisar(progresso, "Testando potencias...");
        double potAtual = c.getTxPowerDbm();
        List<Opcao> pots = new ArrayList<>();
        for (double d : new double[] { -6, -3, 0, 3, 6 }) {
            c.setTxPowerDbm(potAtual + d);
            pots.add(medir(potAtual + d, c, cx, cy, elev, worldPerMeter, prm, contas));
        }
        c.setTxPowerDbm(potAtual);
        eixos.add(new Eixo("Potência", "dBm", potAtual, potAtual,
                areaDe(pots, potAtual), areaDe(pots, potAtual), pots,
                notaPotencia(pots, potAtual)));

        // A area de partida e' a do primeiro degrau de altura: mesma
        // varredura, mesma configuracao. Refaze-la so para ter o numero seria
        // pagar meio segundo por um dado que ja esta na mao.
        double areaInicial = alturas.isEmpty() ? 0 : alturas.get(0).areaM2();
        double areaFinal = area(c, cx, cy, elev, worldPerMeter, prm, contas);

        return new Plano(true, nota(areaInicial, areaFinal, origemDoPar),
                areaInicial, areaFinal, eixos, contas[0]);
    }

    // ------------------------ apoio ------------------------

    private static void avisar(java.util.function.Consumer<String> progresso, String s) {
        if (progresso != null) progresso.accept(s);
    }

    private static Eixo eixo(String campo, String unidade, double atual, double melhor,
                             List<Opcao> opcoes, String porque) {
        return new Eixo(campo, unidade, atual, melhor,
                areaDe(opcoes, atual), areaDe(opcoes, melhor), opcoes, porque);
    }

    /** Área medida para este valor, ou a do valor mais próximo testado. */
    private static double areaDe(List<Opcao> opcoes, double valor) {
        double melhor = 0, dif = Double.MAX_VALUE;
        for (Opcao o : opcoes) {
            double d = Math.abs(o.valor() - valor);
            if (d < dif) { dif = d; melhor = o.areaM2(); }
        }
        return melhor;
    }

    /**
     * O melhor valor testado — desde que bata o atual por uma margem.
     *
     * @param ganhoMinimo fração de área a mais que o candidato precisa render
     *                    para valer a mexida; 0 devolve o melhor sempre
     */
    private static double melhorDe(List<Opcao> opcoes, double atual, double ganhoMinimo) {
        double valor = atual, area = -1;
        for (Opcao o : opcoes) if (o.areaM2() > area) { area = o.areaM2(); valor = o.valor(); }
        if (ganhoMinimo <= 0) return valor;

        double areaAtual = areaDe(opcoes, atual);
        if (areaAtual <= 0) return area > 0 ? valor : atual;
        return (area - areaAtual) / areaAtual > ganhoMinimo ? valor : atual;
    }

    private static Opcao medir(double valor, Radio c, double cx, double cy,
                               ElevationSource elev, double wpm,
                               BeamCoverage.Params prm, int[] contas) {
        contas[0]++;
        BeamCoverage.Result res = BeamCoverage.simulate(c, cx, cy, elev, wpm, prm);
        if (!res.valid()) return new Opcao(valor, 0, 0);
        return new Opcao(valor, areaM2(res), res.meanReachM());
    }

    private static double area(Radio c, double cx, double cy, ElevationSource elev,
                               double wpm, BeamCoverage.Params prm, int[] contas) {
        return medir(0, c, cx, cy, elev, wpm, prm, contas).areaM2();
    }

    private static double areaM2(BeamCoverage.Result res) {
        BeamCoverage.Cobertura g = res.cobertura();
        if (g == null) return 0;
        double ladoM = 2.0 * res.maxReachM() / g.cols();
        return g.celulasCobertas() * ladoM * ladoM;
    }

    private static String notaPotencia(List<Opcao> pots, double atual) {
        double aqui = areaDe(pots, atual), mais = areaDe(pots, atual + 3);
        if (aqui <= 0) return "sem área coberta para comparar";
        double ganho = 100 * (mais - aqui) / aqui;
        if (ganho < 3) {
            return String.format("mexer aqui não resolve: +3 dB só acrescentam "
                    + "%.0f%% de área, porque quem limita é o relevo e não "
                    + "a potência", ganho);
        }
        return String.format("+3 dB acrescentam %.0f%% de área; confira o limite de "
                + "EIRP da sua faixa antes de subir", ganho);
    }

    private static String nota(double antes, double depois, String origemDoPar) {
        StringBuilder sb = new StringBuilder();
        if (antes <= 0 && depois > 0) {
            sb.append(String.format("Como está agora, nada alcança o sinal "
                    + "mínimo pedido. Com os ajustes acima, %.2f km² passam a "
                    + "receber.", depois / 1e6));
        } else if (antes > 0) {
            sb.append(String.format("Área com sinal: %.2f km² agora, %.2f km² "
                    + "com os ajustes (%+.0f%%).", antes / 1e6, depois / 1e6,
                    100 * (depois - antes) / antes));
        } else {
            sb.append("Nenhum arranjo testado alcança o sinal mínimo pedido: "
                    + "baixe a exigência, suba a antena ou reveja o ponto.");
        }
        sb.append("\n\nA área é a que REALMENTE recebe: os buracos de sombra do "
                + "relevo não entram. Quem recebe do outro lado é ");
        sb.append(origemDoPar == null || origemDoPar.isBlank() ? "o que a tela assume"
                                                               : origemDoPar);
        sb.append(".\n\nOs campos foram escolhidos um de cada vez, nesta ordem, e não "
                + "todos juntos — e o critério é chão coberto, que "
                + "não sabe onde estão os seus clientes.");
        return sb.toString();
    }

    /** Só o que entra na conta de RF, para a varredura não mexer no rádio real. */
    private static void copiarRf(Radio de, Radio para) {
        para.setTxPowerDbm(de.getTxPowerDbm());
        para.setAntennaGainDbi(de.getAntennaGainDbi());
        para.setCableLossDb(de.getCableLossDb());
        para.setFrequencyMhz(de.getFrequencyMhz());
        para.setBeamWidthDeg(de.getBeamWidthDeg());
        para.setBeamVerticalWidthDeg(de.getBeamVerticalWidthDeg());
        para.setBeamAzimuthDeg(de.getBeamAzimuthDeg());
        para.setBeamTiltDeg(de.getBeamTiltDeg());
        para.setAltitudeMode(de.getAltitudeMode());
        para.setAntennaHeightM(de.getAntennaHeightM());
        para.setRole(de.getRole());
    }
}
