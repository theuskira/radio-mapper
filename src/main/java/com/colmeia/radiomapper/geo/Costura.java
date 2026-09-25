package com.colmeia.radiomapper.geo;

import com.colmeia.radiomapper.util.Log;

/**
 * Costura o relevo de reserva na altura do levantamento, para a emenda não
 * virar poço.
 *
 * <h3>O poço que ninguém mediu</h3>
 * A cadeia de altitude troca de fonte no meio do terreno: onde a nuvem
 * responde vale a nuvem, onde ela falha vale o relevo global. As duas medem o
 * mesmo chão e discordam — nesta área em dezenas de metros, porque o bloco
 * fotogramétrico está arqueado. Emendar uma na outra sem acertar essa
 * diferença fabrica um degrau em cada vão da nuvem: medido num levantamento
 * real, 1496 trocas de fonte com degrau médio de 30 m e pior de 106 m, e 426
 * vértices de relevo global cercados de nuvem — buracos de até 105 m de
 * profundidade que não existem em nenhuma das duas fontes.
 *
 * Não é só feio. O perfil de um enlace que cruze um desses vãos mergulha cem
 * metros no meio do caminho, e a conta de obstrução acredita no mergulho.
 *
 * <h3>Como a costura é feita</h3>
 * Onde as DUAS fontes respondem dá para medir a diferença entre elas. Essas
 * medidas viram um campo de deslocamento numa grade grossa; dentro dos vãos,
 * onde não há o que medir, o deslocamento é herdado das bordas em volta. Ao
 * preencher um vão, o relevo global entra somado a esse deslocamento: a
 * FORMA do relevo global é preservada, e o NÍVEL passa a ser o da nuvem
 * vizinha. A emenda fecha.
 *
 * <h3>Por que o deslocamento esmaece</h3>
 * Longe do levantamento não há emenda para fechar, e deslocar o relevo global
 * ali seria estragar o único dado bom que resta. Por isso o campo perde força
 * com a distância até a medida mais próxima e chega a zero em
 * {@link #ESMAECIMENTO_M}: dentro dos vãos, cercados de nuvem, ele vale
 * inteiro; no mundo lá fora, some.
 *
 * <h3>O que isto NÃO conserta</h3>
 * O vão passa a herdar o erro da nuvem. Onde ela está 94 m alta, o vão
 * costurado fica 94 m alto junto. A troca é deliberada: erro consistente em
 * vez de degrau de cem metros, porque é o degrau que inventa obstáculo onde
 * não há. Quem quer a altitude certa tem de consertar o levantamento — ver
 * {@link PlyCheck}.
 */
public final class Costura implements ElevationSource {

    /** Lado da célula do campo de deslocamento, em metros de chão. */
    private static final double PASSO_M = 25;

    /** Distância em que o deslocamento chega a zero, em metros. */
    private static final double ESMAECIMENTO_M = 400;

    /** Teto de células do campo, para levantamento grande não pesar. */
    private static final int MAX_CELULAS = 500_000;

    private final ElevationSource fundo;
    private final double minX, minY, passo;
    private final int cols, rows;
    private final float[] vies;
    private final int medidas;

    private Costura(ElevationSource fundo, double minX, double minY, double passo,
                    int cols, int rows, float[] vies, int medidas) {
        this.fundo = fundo; this.minX = minX; this.minY = minY; this.passo = passo;
        this.cols = cols; this.rows = rows; this.vies = vies; this.medidas = medidas;
    }

    /**
     * Mede a diferença entre as duas fontes e devolve o fundo já costurado.
     *
     * @param principal quem manda onde responde (a nuvem)
     * @param fundo     quem preenche os vãos (o relevo global)
     * @param bounds    {minX, minY, maxX, maxY} em coordenadas de mundo
     * @return o fundo deslocado, ou o próprio fundo quando não houve onde
     *         medir — sem medida não há costura a fazer, e fingir que há
     *         seria deslocar o relevo por um palpite
     */
    public static ElevationSource tecer(ElevationSource principal, ElevationSource fundo,
                                        double[] bounds) {
        if (principal == null || fundo == null || bounds == null) return fundo;

        double lat = Mercator.latOfWorldY((bounds[1] + bounds[3]) / 2);
        double escala = Mercator.groundScaleAt(lat);
        // O passo vem em metros de chão e a caixa está em metros de Mercator.
        double passo = PASSO_M / (escala <= 0 ? 1 : escala);

        // Uma folga em volta: o esmaecimento precisa de espaço para acontecer
        // fora do levantamento, senão o campo acabaria de supetão na borda.
        double folga = ESMAECIMENTO_M / (escala <= 0 ? 1 : escala);
        double x0 = bounds[0] - folga, y0 = bounds[1] - folga;
        long c = (long) Math.ceil((bounds[2] + folga - x0) / passo) + 1;
        long r = (long) Math.ceil((bounds[3] + folga - y0) / passo) + 1;
        while (c * r > MAX_CELULAS) { passo *= 1.5;
            c = (long) Math.ceil((bounds[2] + folga - x0) / passo) + 1;
            r = (long) Math.ceil((bounds[3] + folga - y0) / passo) + 1;
        }
        int cols = (int) c, rows = (int) r;

        float[] v = new float[cols * rows];
        boolean[] medido = new boolean[cols * rows];
        int n = 0;
        for (int i = 0; i < rows; i++) {
            double wy = y0 + i * passo;
            for (int j = 0; j < cols; j++) {
                double wx = x0 + j * passo;
                Double a = principal.elevationAt(wx, wy);
                if (a == null) { v[i * cols + j] = Float.NaN; continue; }
                Double b = fundo.elevationAt(wx, wy);
                if (b == null) { v[i * cols + j] = Float.NaN; continue; }
                v[i * cols + j] = (float) (a - b);
                medido[i * cols + j] = true;
                n++;
            }
        }
        if (n < 8) {
            Log.info("Costura do relevo: so %d ponto(s) com as duas fontes — sem costura", n);
            return fundo;
        }

        // Dentro dos vaos o deslocamento e' herdado das bordas, pela media
        // dos vizinhos que ja tem valor. Mesma ideia do preenchimento da
        // nuvem, so que numa grade grossa e sobre a DIFERENCA, nao a altura.
        boolean mudou = true;
        for (int passada = 0; passada < 400 && mudou; passada++) {
            mudou = false;
            for (int i = 0; i < v.length; i++) {
                if (!Float.isNaN(v[i])) continue;
                int li = i / cols, co = i % cols;
                double soma = 0; int viz = 0;
                if (co > 0        && !Float.isNaN(v[i - 1]))    { soma += v[i - 1];    viz++; }
                if (co < cols - 1 && !Float.isNaN(v[i + 1]))    { soma += v[i + 1];    viz++; }
                if (li > 0        && !Float.isNaN(v[i - cols])) { soma += v[i - cols]; viz++; }
                if (li < rows - 1 && !Float.isNaN(v[i + cols])) { soma += v[i + cols]; viz++; }
                if (viz > 0) { v[i] = (float) (soma / viz); mudou = true; }
            }
        }
        for (int i = 0; i < v.length; i++) if (Float.isNaN(v[i])) v[i] = 0;

        // Esmaecimento -- mas NAO dentro dos vaos.
        //
        // A primeira versao esmaecia so pela distancia ate a medida mais
        // proxima, e com isso punia justamente o miolo dos vaos: no centro de
        // um vao de 300 m o deslocamento caia a 62% e sobrava um poco de 34 m.
        // Distancia sozinha nao distingue "dentro de um vao cercado de nuvem"
        // de "150 m fora do levantamento".
        //
        // Quem distingue e' o cerco: celula com medida nas QUATRO direcoes
        // esta entre bordas e leva o deslocamento inteiro; o resto esmaece
        // com a distancia, e longe do levantamento chega a zero.
        boolean[] cercado = cercadas(medido, cols, rows);
        int[] dist = distanciaAte(medido, cols, rows);
        int alcance = Math.max(1, (int) Math.round(ESMAECIMENTO_M / PASSO_M));
        for (int i = 0; i < v.length; i++) {
            if (cercado[i]) continue;
            double f = 1.0 - dist[i] / (double) alcance;
            v[i] = (float) (v[i] * Math.max(0, Math.min(1, f)));
        }

        double soma = 0, pior = 0;
        for (int i = 0; i < v.length; i++) { soma += Math.abs(v[i]); pior = Math.max(pior, Math.abs(v[i])); }
        Log.info("Costura do relevo: %d ponto(s) medidos, grade %dx%d de %.0f m, "
                + "deslocamento medio %.1f m, maior %.1f m",
                n, cols, rows, PASSO_M, soma / v.length, pior);

        return new Costura(fundo, x0, y0, passo, cols, rows, v, n);
    }

    /**
     * Células com medida nas quatro direções -- ou seja, dentro de um vão.
     *
     * Mesmo criterio do preenchimento da nuvem: o que decide nao e' o tamanho
     * do vao, e sim estar entre bordas. Sem limite de alcance aqui, porque a
     * grade e' grossa e ja esta contida na area do levantamento mais a folga.
     */
    private static boolean[] cercadas(boolean[] medido, int cols, int rows) {
        boolean[] ok = new boolean[medido.length];
        java.util.Arrays.fill(ok, true);
        boolean[] lado = new boolean[medido.length];
        for (int eixo = 0; eixo < 4; eixo++) {
            final boolean linha = eixo < 2, frente = (eixo % 2) == 0;
            int externo = linha ? rows : cols, interno = linha ? cols : rows;
            for (int e = 0; e < externo; e++) {
                boolean viu = false;
                for (int k = 0; k < interno; k++) {
                    int p = frente ? k : interno - 1 - k;
                    int i = linha ? e * cols + p : p * cols + e;
                    if (medido[i]) viu = true;
                    lado[i] = viu;
                }
            }
            for (int i = 0; i < ok.length; i++) ok[i] &= lado[i];
        }
        return ok;
    }

    /**
     * Distância de cada célula até a medida mais próxima, em células.
     *
     * Duas varreduras em cruz (uma para frente, outra para tras) dao a
     * distancia de quarteirao, que aqui basta: o esmaecimento e' um degrade,
     * nao uma medida.
     */
    private static int[] distanciaAte(boolean[] medido, int cols, int rows) {
        final int LONGE = 1 << 20;
        int[] d = new int[medido.length];
        for (int i = 0; i < d.length; i++) d[i] = medido[i] ? 0 : LONGE;
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                int k = i * cols + j;
                if (j > 0) d[k] = Math.min(d[k], d[k - 1] + 1);
                if (i > 0) d[k] = Math.min(d[k], d[k - cols] + 1);
            }
        }
        for (int i = rows - 1; i >= 0; i--) {
            for (int j = cols - 1; j >= 0; j--) {
                int k = i * cols + j;
                if (j < cols - 1) d[k] = Math.min(d[k], d[k + 1] + 1);
                if (i < rows - 1) d[k] = Math.min(d[k], d[k + cols] + 1);
            }
        }
        return d;
    }

    /** Deslocamento naquele ponto, interpolado entre as células da grade. */
    private double viesEm(double wx, double wy) {
        double fx = (wx - minX) / passo, fy = (wy - minY) / passo;
        int j = (int) Math.floor(fx), i = (int) Math.floor(fy);
        if (i < 0 || j < 0 || i >= rows - 1 || j >= cols - 1) {
            // Fora da grade o deslocamento ja' esmaeceu ate zero.
            return 0;
        }
        double tx = fx - j, ty = fy - i;
        double v00 = vies[i * cols + j],       v10 = vies[i * cols + j + 1];
        double v01 = vies[(i + 1) * cols + j], v11 = vies[(i + 1) * cols + j + 1];
        return (v00 * (1 - tx) + v10 * tx) * (1 - ty)
             + (v01 * (1 - tx) + v11 * tx) * ty;
    }

    @Override
    public Double elevationAt(double worldX, double worldY) {
        Double v = fundo.elevationAt(worldX, worldY);
        return v == null ? null : v + viesEm(worldX, worldY);
    }

    @Override
    public Double elevationOver(double worldX, double worldY, double raioM) {
        Double v = fundo.elevationOver(worldX, worldY, raioM);
        return v == null ? null : v + viesEm(worldX, worldY);
    }

    @Override
    public String sourceName() { return fundo.sourceName() + " (costurado ao levantamento)"; }

    @Override
    public double resolutionMeters() { return fundo.resolutionMeters(); }

    /** Quantos pontos tinham as duas fontes, e portanto sustentam a costura. */
    public int medidas() { return medidas; }
}
