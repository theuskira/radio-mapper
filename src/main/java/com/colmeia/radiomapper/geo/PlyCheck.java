package com.colmeia.radiomapper.geo;

import com.colmeia.radiomapper.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Confere o levantamento contra o relevo de referência, antes de confiar nele.
 *
 * <h3>Por que isto precisa existir</h3>
 * O {@link ElevationChain} põe o .PLY no topo da ordem porque um levantamento
 * de drone costuma ser o melhor dado disponível. "Costuma" não é "sempre".
 * Um bloco fotogramétrico ajustado só pelo GPS das fotos, sem ponto de
 * controle e sem PPK, fica localmente lisinho e globalmente entortado: a
 * superfície é limpa em cada metro quadrado e mesmo assim arqueia dezenas de
 * metros ao longo de um quilômetro. Nenhuma checagem interna da nuvem pega
 * isso — a nuvem é coerente consigo mesma. Só comparando com um relevo
 * independente é que o erro aparece.
 *
 * E ele importa: altitude errada em dezenas de metros vira morro que não
 * existe no perfil do enlace, e o projeto sai dimensionado contra um
 * obstáculo imaginário — ou, pior, sem o obstáculo real.
 *
 * <h3>O que a comparação vale, e o que não vale</h3>
 * O relevo global tem ~30 m de resolução e erro vertical na casa de 16 m,
 * então discordância pequena não diz nada: quem está errado pode ser a
 * referência. O que a aferição procura é o que não cabe nessa folga — viés
 * grande, discordância de dezenas de metros, e sobretudo AMPLITUDE de relevo
 * e CORRELAÇÃO fora do lugar. Se a nuvem desenha 280 m de desnível onde a
 * referência vê 60, e as duas formas nem se parecem, não é a referência que
 * está grosseira: é a nuvem que está torta.
 *
 * O viés constante é tratado à parte de propósito. Nuvem em altura
 * elipsoidal contra referência em altura ortométrica difere por uma constante
 * (a ondulação do geoide, ~5 m no Nordeste) sem que nada esteja errado — isso
 * desloca tudo junto e não inventa obstáculo. Por isso o RMS é medido DEPOIS
 * de tirar o viés: o que sobra é deformação de verdade.
 *
 * Nada aqui altera a nuvem. A aferição só mede e conta o que achou.
 */
public final class PlyCheck {

    private PlyCheck() {}

    /** Diferença a partir da qual um ponto conta como discordante. */
    private static final double LIMITE_M = 20.0;

    public enum Veredito {
        COMPATIVEL("compatível com o relevo de referência"),
        SUSPEITO("discorda do relevo de referência mais do que o esperado"),
        INCOMPATIVEL("incompatível com o relevo de referência");

        private final String texto;
        Veredito(String t) { this.texto = t; }
        @Override public String toString() { return texto; }
    }

    /**
     * @param amostras    pontos onde as duas fontes responderam
     * @param vies        mediana de (nuvem − referência), em metros
     * @param rms         dispersão da diferença depois de tirar o viés
     * @param correlacao  o quanto as duas formas de relevo se parecem, -1..1
     * @param amplitudePly  desnível que a nuvem desenha
     * @param amplitudeRef  desnível que a referência desenha
     * @param fracaoFora  fração das amostras além de {@value #LIMITE_M} m, já sem o viés
     * @param piorM       maior discordância encontrada, já sem o viés
     * @param piorLat     onde ela está
     * @param piorLon     onde ela está
     */
    public record Resultado(int amostras, double vies, double rms, double correlacao,
                            double amplitudePly, double amplitudeRef, double fracaoFora,
                            double piorM, double piorLat, double piorLon,
                            String referencia) {

        public Veredito veredito() {
            if (amostras < 30) return Veredito.COMPATIVEL;      // pouco para julgar
            // A forma do relevo e' o sinal mais forte: nuvem que desenha muito
            // mais desnivel do que existe, sem parecer com o que existe, esta
            // torta mesmo que a diferenca media pareca aceitavel.
            boolean formaErrada = correlacao < 0.5
                    && amplitudePly > amplitudeRef * 1.8 + 20;
            if (formaErrada || rms > 20 || fracaoFora > 0.12) return Veredito.INCOMPATIVEL;
            // 8 m de RMS ja e mais do que a referencia erra entre pontos
            // vizinhos, e num enlace de alguns quilometros isso e a diferenca
            // entre a Fresnel abrir e nao abrir. Abaixo disso a discordancia
            // cabe no erro da propria referencia e nao acusa ninguem.
            if (rms > 8 || fracaoFora > 0.03) return Veredito.SUSPEITO;
            return Veredito.COMPATIVEL;
        }

        public boolean aprovado() { return veredito() == Veredito.COMPATIVEL; }
    }

    /**
     * Amostra as duas fontes numa malha regular sobre o levantamento.
     *
     * @param lado lado da malha de amostragem; o custo é o quadrado disso
     * @return {@code null} se a referência não respondeu em lugar nenhum
     *         (sem rede, ou área sem cobertura) — o que não é reprovação,
     *         é ausência de segunda opinião
     */
    public static Resultado aferir(PlyElevation ply, ElevationSource referencia, int lado) {
        if (ply == null || referencia == null) return null;
        double[] b = ply.worldBounds();
        int n = Math.max(8, lado);

        List<double[]> pares = new ArrayList<>();   // {nuvem, referencia, wx, wy}
        for (int r = 0; r < n; r++) {
            double wy = b[1] + (b[3] - b[1]) * (r + 0.5) / n;
            for (int c = 0; c < n; c++) {
                double wx = b[0] + (b[2] - b[0]) * (c + 0.5) / n;
                Double a = ply.elevationAt(wx, wy);
                if (a == null) continue;
                Double v = referencia.elevationAt(wx, wy);
                if (v == null) continue;
                pares.add(new double[] { a, v, wx, wy });
            }
        }
        if (pares.size() < 8) {
            Log.info("Afericao do PLY: referencia respondeu em %d ponto(s) — sem "
                    + "segunda opiniao suficiente para julgar", pares.size());
            return null;
        }

        double[] dif = new double[pares.size()];
        for (int i = 0; i < dif.length; i++) dif[i] = pares.get(i)[0] - pares.get(i)[1];
        double vies = mediana(dif.clone());

        double soma2 = 0, pior = 0, piorLat = 0, piorLon = 0;
        int fora = 0;
        double minA = Double.MAX_VALUE, maxA = -Double.MAX_VALUE;
        double minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
        double somaA = 0, somaV = 0;
        for (int i = 0; i < dif.length; i++) {
            double d = dif[i] - vies;
            soma2 += d * d;
            if (Math.abs(d) > LIMITE_M) fora++;
            if (Math.abs(d) > Math.abs(pior)) {
                pior = d;
                piorLat = Mercator.latOfWorldY(pares.get(i)[3]);
                piorLon = Mercator.lonOfWorldX(pares.get(i)[2]);
            }
            double a = pares.get(i)[0], v = pares.get(i)[1];
            minA = Math.min(minA, a); maxA = Math.max(maxA, a);
            minV = Math.min(minV, v); maxV = Math.max(maxV, v);
            somaA += a; somaV += v;
        }
        double rms = Math.sqrt(soma2 / dif.length);

        double mediaA = somaA / dif.length, mediaV = somaV / dif.length;
        double sAA = 0, sVV = 0, sAV = 0;
        for (double[] p : pares) {
            double da = p[0] - mediaA, dv = p[1] - mediaV;
            sAA += da * da; sVV += dv * dv; sAV += da * dv;
        }
        double corr = (sAA > 0 && sVV > 0) ? sAV / Math.sqrt(sAA * sVV) : 0;

        Resultado res = new Resultado(dif.length, vies, rms, corr,
                maxA - minA, maxV - minV, fora / (double) dif.length,
                pior, piorLat, piorLon, referencia.sourceName());

        Log.info("Afericao do PLY contra %s: %d amostras, vies %.1f m, RMS %.1f m, "
                + "correlacao %.2f, desnivel %.0f m contra %.0f m, %.1f%% alem de %.0f m -> %s",
                referencia.sourceName(), res.amostras(), res.vies(), res.rms(),
                res.correlacao(), res.amplitudePly(), res.amplitudeRef(),
                100 * res.fracaoFora(), LIMITE_M, res.veredito());
        return res;
    }

    /** Texto pronto para a tela, em linhas. */
    public static String descrever(Resultado r) {
        if (r == null) {
            return "Sem segunda opiniao: o relevo de referencia nao respondeu nesta area.\n"
                 + "A nuvem sera usada sem afericao.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Comparado com %s, em %d pontos:%n%n", r.referencia(), r.amostras()));
        sb.append(String.format("  Desnivel desenhado    nuvem %.0f m   referencia %.0f m%n",
                r.amplitudePly(), r.amplitudeRef()));
        sb.append(String.format("  Semelhanca das formas %.2f  (1,00 = mesmo relevo)%n", r.correlacao()));
        sb.append(String.format("  Deslocamento medio    %+.1f m  (constante, nao inventa obstaculo)%n", r.vies()));
        sb.append(String.format("  Discordancia (RMS)    %.1f m  depois de tirar o deslocamento%n", r.rms()));
        sb.append(String.format("  Area alem de %.0f m      %.1f%%%n", LIMITE_M, 100 * r.fracaoFora()));
        sb.append(String.format("  Pior ponto            %+.0f m  em %.5f, %.5f%n",
                r.piorM(), r.piorLat(), r.piorLon()));
        return sb.toString();
    }

    private static double mediana(double[] v) {
        java.util.Arrays.sort(v);
        int n = v.length;
        return n % 2 == 1 ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2;
    }
}
