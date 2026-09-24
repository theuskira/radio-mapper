package com.colmeia.radiomapper.rf;

import com.colmeia.radiomapper.model.Radio;

/**
 * Estimativa de sinal de um enlace — o "link budget".
 *
 * <pre>
 * RSSI = Ptx + Gtx − Lcabo_tx − Ldesalinhamento_tx
 *              − FSPL
 *        + Grx − Lcabo_rx − Ldesalinhamento_rx
 * </pre>
 *
 * <h3>O que isto NÃO é</h3>
 * Não é o Ubiquiti Design Center. Aquele usa o diagrama de irradiação real de
 * cada antena, a sensibilidade do rádio por modulação e um modelo de
 * difração sobre o terreno. Aqui a antena é aproximada pela abertura de meia
 * potência, e a perda de percurso é a de espaço livre: <b>vale para visada
 * limpa</b>. Havendo obstrução, o sinal real será pior que o estimado — o
 * quanto pior, este cálculo não diz.
 *
 * Mesmo assim serve para o que mais importa na prática: comparar o que a
 * física permite com o que o rádio está de fato entregando. Uma diferença
 * grande entre estimado e medido aponta antena desalinhada, conector com
 * água, cabo ruim ou obstrução que ninguém tinha notado.
 */
public final class LinkBudget {

    private LinkBudget() {}

    /**
     * Atenuação máxima aplicada fora do feixe. Antena real não cai
     * indefinidamente: os lóbulos laterais estabelecem um piso.
     */
    private static final double MAX_OFF_AXIS_LOSS_DB = 25;

    public record Result(
            double distanceKm,
            double freqMhz,
            double fspl,
            double offAxisA, double offAxisB,
            double rssiAtoB, double rssiBtoA,
            double fresnelAtMidM,
            String note) {

        public boolean valid() { return !Double.isNaN(rssiAtoB); }
    }

    /**
     * @param angAtoB ângulo vertical de A para B, em graus (do perfil)
     * @param angBtoA idem, de B para A
     * @param offAxisHorizA desalinhamento horizontal de A, em graus (0 se apontada)
     */
    public static Result compute(Radio a, Radio b, double distanceM,
                                 double angAtoB, double angBtoA,
                                 double offAxisHorizA, double offAxisHorizB) {

        double freq = a.getFrequencyMhz() > 0 ? a.getFrequencyMhz() : b.getFrequencyMhz();
        if (freq <= 0 || distanceM <= 0) {
            return new Result(distanceM / 1000, freq, Double.NaN, 0, 0,
                    Double.NaN, Double.NaN, Double.NaN,
                    "Informe a frequência para estimar o sinal.");
        }

        double dKm = distanceM / 1000.0;
        double fspl = fspl(dKm, freq);

        // Desalinhamento: quanto o alvo está fora do eixo, nos dois planos.
        double offA = offAxisLoss(offAxisHorizA, a.getBeamWidthDeg())
                    + offAxisLoss(angAtoB - a.getBeamTiltDeg(), a.getBeamVerticalWidthDeg());
        double offB = offAxisLoss(offAxisHorizB, b.getBeamWidthDeg())
                    + offAxisLoss(angBtoA - b.getBeamTiltDeg(), b.getBeamVerticalWidthDeg());
        offA = Math.min(offA, MAX_OFF_AXIS_LOSS_DB);
        offB = Math.min(offB, MAX_OFF_AXIS_LOSS_DB);

        double aToB = a.getTxPowerDbm() + a.getAntennaGainDbi() - a.getCableLossDb() - offA
                    - fspl
                    + b.getAntennaGainDbi() - b.getCableLossDb() - offB;

        double bToA = b.getTxPowerDbm() + b.getAntennaGainDbi() - b.getCableLossDb() - offB
                    - fspl
                    + a.getAntennaGainDbi() - a.getCableLossDb() - offA;

        String note = null;
        if (!a.hasRfData() || !b.hasRfData()) {
            note = "Faltam dados de RF em um dos rádios — a estimativa está incompleta.";
        }

        return new Result(dKm, freq, fspl, offA, offB, aToB, bToA,
                fresnelRadius(dKm / 2, dKm / 2, freq / 1000.0), note);
    }

    /**
     * Perda de espaço livre (Friis), em dB.
     *
     * A constante 32,44 vale para distância em km e frequência em MHz.
     *
     * {@code freqMhz} é a frequência de OPERAÇÃO — a central do canal, como
     * 5800 ou 5180 — e não a largura do canal. Largura de canal afeta piso de
     * ruído e sensibilidade do receptor, que não entram neste cálculo.
     */
    public static double fspl(double distanceKm, double freqMhz) {
        if (distanceKm <= 0 || freqMhz <= 0) return Double.NaN;
        return 32.44 + 20 * Math.log10(distanceKm) + 20 * Math.log10(freqMhz);
    }

    /**
     * Perda por estar fora do eixo, aproximada pela parábola usada em modelos
     * de setor: 3 dB exatamente na borda da abertura de meia potência, subindo
     * quadraticamente a partir daí.
     *
     * É aproximação: antena real tem lóbulos e nulos que isto não representa.
     * Sem abertura informada, devolve 0 em vez de chutar.
     */
    public static double offAxisLoss(double offsetDeg, double beamWidthDeg) {
        if (beamWidthDeg <= 0) return 0;
        if (beamWidthDeg >= 360) return 0;              // omni: sem direção preferencial
        double half = beamWidthDeg / 2.0;
        if (half <= 0) return 0;
        double ratio = Math.abs(offsetDeg) / half;
        return Math.min(MAX_OFF_AXIS_LOSS_DB, 3.0 * ratio * ratio);
    }

    /**
     * Raio da primeira zona de Fresnel, em metros.
     *
     * @param d1 d2 distâncias aos dois extremos, em km
     * @param freqGhz frequência em GHz
     */
    public static double fresnelRadius(double d1, double d2, double freqGhz) {
        double total = d1 + d2;
        if (total <= 0 || freqGhz <= 0) return Double.NaN;
        return 17.32 * Math.sqrt((d1 * d2) / (freqGhz * total));
    }

    /**
     * Qualidade do sinal em palavras. As faixas são as usadas na prática em
     * enlaces 5 GHz; não são norma, são o que operador de campo considera.
     */
    public static String quality(double rssiDbm) {
        if (Double.isNaN(rssiDbm)) return "";
        if (rssiDbm >= -55) return "excelente";
        if (rssiDbm >= -65) return "bom";
        if (rssiDbm >= -75) return "aceitável";
        if (rssiDbm >= -83) return "fraco";
        return "inviável";
    }
}
