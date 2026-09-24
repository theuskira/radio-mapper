package com.colmeia.radiomapper.geo;

import com.colmeia.radiomapper.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Relevo vindo de um levantamento .PLY — normalmente nuvem densa de drone.
 *
 * <h3>Nuvem vira grade</h3>
 * Consultar altura num ponto percorrendo milhões de vértices seria inviável.
 * Na carga, os pontos são jogados numa grade regular guardando a MAIOR altura
 * de cada célula. Máxima, e não média: para obstrução de enlace o que importa
 * é o topo do que existe ali — a árvore, o telhado, a caixa d'água. Média
 * suavizaria justamente o obstáculo.
 *
 * <h3>O PLY não diz onde fica</h3>
 * O formato não guarda sistema de coordenadas. Quem carrega informa em que
 * CRS o levantamento está, e a conversão acontece na consulta: coordenada de
 * mundo (Mercator) → lat/lon → coordenada do levantamento.
 */
public final class PlyElevation implements ElevationSource {

    /** Como interpretar os X/Y do arquivo. */
    public enum Crs {
        // Valor de referência ao desserializar um projeto gravado por uma
        // versão que tivesse outros nomes aqui: sem isto a leitura falharia.
        @com.fasterxml.jackson.annotation.JsonEnumDefaultValue
        UTM("UTM (metros)"),
        GEOGRAPHIC("Geográfico (graus)"),
        WEB_MERCATOR("Web Mercator (metros)");

        private final String label;
        Crs(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    /** Teto de células. Acima disso a resolução é afrouxada para caber na memória. */
    private static final int MAX_CELLS = 12_000_000;

    /**
     * Até que distância uma célula vazia aceita a altura da vizinha.
     *
     * Vale para a consulta e para o desenho da área coberta: é o que define
     * onde {@link #elevationAt} responde, e o contorno precisa desenhar
     * exatamente isso.
     */
    private static final int RAIO_VIZINHO = 2;

    /** Teto de células por lado em {@link #elevationOver}, para não travar. */
    private static final int MAX_RAIO_AREA = 48;

    private final File source;
    private final Crs crs;
    private final int utmZone;
    private final boolean utmSouth;

    private final double minX, minY, cellSize;
    private final int cols, rows;
    private final float[] grid;
    private final long pointsUsed;
    private final double minZ, maxZ;

    private PlyElevation(File source, Crs crs, int utmZone, boolean utmSouth,
                         double minX, double minY, double cellSize, int cols, int rows,
                         float[] grid, long pointsUsed, double minZ, double maxZ) {
        this.source = source; this.crs = crs;
        this.utmZone = utmZone; this.utmSouth = utmSouth;
        this.minX = minX; this.minY = minY; this.cellSize = cellSize;
        this.cols = cols; this.rows = rows; this.grid = grid;
        this.pointsUsed = pointsUsed; this.minZ = minZ; this.maxZ = maxZ;
    }

    /** O que a leitura encontrou, para mostrar ao usuário antes de confirmar. */
    public record Summary(long vertexCount, double minX, double maxX,
                          double minY, double maxY, double minZ, double maxZ) {

        /** Palpite de CRS a partir da ordem de grandeza dos valores. */
        public Crs guessCrs() {
            boolean grausX = minX >= -180 && maxX <= 180;
            boolean grausY = minY >= -90 && maxY <= 90;
            if (grausX && grausY) return Crs.GEOGRAPHIC;
            // Easting de UTM fica entre 100 mil e 900 mil por definição.
            if (minX > 100_000 && maxX < 1_000_000) return Crs.UTM;
            return Crs.WEB_MERCATOR;
        }
    }

    /** Passa pelo arquivo só medindo a extensão — para decidir o CRS antes de carregar. */
    public static Summary summarize(File file, int stride) throws PlyReader.PlyException {
        double[] ext = { Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE,
                         -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE };
        long n = PlyReader.readVertices(file, stride, (x, y, z) -> {
            if (x < ext[0]) ext[0] = x;
            if (x > ext[1]) ext[1] = x;
            if (y < ext[2]) ext[2] = y;
            if (y > ext[3]) ext[3] = y;
            if (z < ext[4]) ext[4] = z;
            if (z > ext[5]) ext[5] = z;
        });
        if (n == 0) throw new PlyReader.PlyException("Nenhum vértice legível no arquivo.");
        return new Summary(n, ext[0], ext[1], ext[2], ext[3], ext[4], ext[5]);
    }

    /**
     * Carrega a nuvem numa grade.
     *
     * @param cellSizeM resolução desejada, em metros. É afrouxada
     *                  automaticamente se a área for grande demais.
     */
    public static PlyElevation load(File file, Crs crs, int utmZone, boolean utmSouth,
                                    double cellSizeM, int stride) throws PlyReader.PlyException {
        Summary s = summarize(file, stride);

        double spanX = s.maxX() - s.minX();
        double spanY = s.maxY() - s.minY();
        if (spanX <= 0 || spanY <= 0) {
            throw new PlyReader.PlyException("O levantamento não tem extensão — todos os "
                    + "pontos estão no mesmo lugar.");
        }

        // Em graus, a célula pedida em metros precisa virar graus.
        double cell = crs == Crs.GEOGRAPHIC ? cellSizeM / 111_320.0 : Math.max(0.05, cellSizeM);

        int c = (int) Math.ceil(spanX / cell) + 1;
        int r = (int) Math.ceil(spanY / cell) + 1;
        // Afrouxa até caber: grade grande demais estouraria a memória.
        while ((long) c * r > MAX_CELLS) {
            cell *= 1.5;
            c = (int) Math.ceil(spanX / cell) + 1;
            r = (int) Math.ceil(spanY / cell) + 1;
        }

        float[] grid = new float[c * r];
        Arrays.fill(grid, Float.NaN);

        final double fMinX = s.minX(), fMinY = s.minY(), fCell = cell;
        final int fc = c, fr = r;
        long used = PlyReader.readVertices(file, stride, (x, y, z) -> {
            int col = (int) ((x - fMinX) / fCell);
            int row = (int) ((y - fMinY) / fCell);
            if (col < 0 || row < 0 || col >= fc || row >= fr) return;
            int idx = row * fc + col;
            float cur = grid[idx];
            if (Float.isNaN(cur) || z > cur) grid[idx] = (float) z;
        });

        Log.info("PLY carregado: %s — %d ponto(s), grade %dx%d de %.2f %s, altura %.1f..%.1f m",
                file.getName(), used, c, r, cell,
                crs == Crs.GEOGRAPHIC ? "graus" : "m", s.minZ(), s.maxZ());

        return new PlyElevation(file, crs, utmZone, utmSouth, s.minX(), s.minY(), cell,
                c, r, grid, used, s.minZ(), s.maxZ());
    }

    // ------------------------ Consulta ------------------------

    @Override
    public Double elevationAt(double worldX, double worldY) {
        double[] p = toSurvey(worldX, worldY);
        double px = p[0], py = p[1];

        int col = (int) ((px - minX) / cellSize);
        int row = (int) ((py - minY) / cellSize);
        if (col < 0 || row < 0 || col >= cols || row >= rows) return null;

        float v = grid[row * cols + col];
        if (!Float.isNaN(v)) return (double) v;

        // Célula vazia: procura vizinha próxima, para buraco de amostragem não
        // virar "sem dado" no meio de uma área coberta.
        for (int rad = 1; rad <= RAIO_VIZINHO; rad++) {
            for (int dr = -rad; dr <= rad; dr++) {
                for (int dc = -rad; dc <= rad; dc++) {
                    int rr = row + dr, cc = col + dc;
                    if (rr < 0 || cc < 0 || rr >= rows || cc >= cols) continue;
                    float n = grid[rr * cols + cc];
                    if (!Float.isNaN(n)) return (double) n;
                }
            }
        }
        return null;
    }

    // ------------------------ Coordenadas ------------------------

    /** Coordenada do mapa para a coordenada do levantamento. */
    private double[] toSurvey(double worldX, double worldY) {
        double lat = Mercator.latOfWorldY(worldY);
        double lon = Mercator.lonOfWorldX(worldX);
        return switch (crs) {
            case GEOGRAPHIC -> new double[] { lon, lat };
            case WEB_MERCATOR -> new double[] { worldX, -worldY };
            default -> Utm.fromLatLon(lat, lon, utmZone, utmSouth);
        };
    }

    /** O caminho de volta: coordenada do levantamento para a do mapa. */
    private double[] toWorld(double px, double py) {
        return switch (crs) {
            case GEOGRAPHIC -> new double[] { Mercator.worldX(px), Mercator.worldY(py) };
            case WEB_MERCATOR -> new double[] { px, -py };
            default -> {
                double[] ll = Utm.toLatLon(px, py, utmZone, utmSouth);
                yield new double[] { Mercator.worldX(ll[1]), Mercator.worldY(ll[0]) };
            }
        };
    }

    // ------------------------ Pegada da cobertura ------------------------

    /**
     * Contorno da area que o levantamento realmente cobre, em coordenadas de
     * mundo — pronto para desenhar sobre o mapa.
     *
     * <h3>Por que nao serve a caixa envolvente</h3>
     * Voo de drone nao cobre retangulo: cobre a faixa que o plano de voo
     * desenhou, e o que sobra nos cantos da caixa e area sem ponto nenhum.
     * Nas nuvens de teste a cobertura real da 64% da caixa — desenhar o
     * retangulo prometeria altitude em um terco de area onde nao ha dado.
     *
     * <h3>Como o contorno sai</h3>
     * A grade e reduzida a blocos (senao o contorno teria milhoes de
     * vertices), cada bloco vira coberto ou vazio, e as arestas que separam
     * coberto de vazio sao encadeadas em aneis fechados. Aresta entre dois
     * blocos cobertos se cancela, entao o que sobra e exatamente a fronteira
     * — inclusive de buracos internos e de pedacos separados do voo.
     *
     * Coberto aqui quer dizer "a consulta responde na maior parte do bloco",
     * e nao "ha ponto do voo no bloco". Numa nuvem esparsa as duas coisas sao
     * muito diferentes, e e a primeira que o usuario precisa ver: o contorno
     * existe para dizer ate onde da para confiar na altitude.
     *
     * @param maxBlocos lado maximo da grade de blocos; mais blocos dao
     *                  contorno mais fiel e mais vertices para desenhar
     */
    public Footprint footprint(int maxBlocos) {
        int alvo = Math.max(16, maxBlocos);
        int b = Math.max(1, (int) Math.ceil(Math.max(cols, rows) / (double) alvo));
        int bc = (cols + b - 1) / b;
        int br = (rows + b - 1) / b;

        // Onde a consulta realmente responde — nao onde ha ponto. Sao coisas
        // diferentes: elevationAt() aceita a altura de uma vizinha ate
        // RAIO_VIZINHO celulas de distancia, entao uma celula vazia cercada
        // de dado responde, e precisa contar como coberta.
        boolean[] responde = dilatar(marcarComDado(), RAIO_VIZINHO);

        // E o bloco entra no desenho quando a MAIORIA dele responde. Bloco
        // que so encosta na franja do voo responderia em um canto e ficaria
        // vazio no resto; pintar ele inteiro prometeria altitude onde nao ha.
        boolean[] cob = new boolean[bc * br];
        int[] respondem = new int[bc * br];
        long celulasComDado = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (!Float.isNaN(grid[r * cols + c])) celulasComDado++;
                if (responde[r * cols + c]) respondem[(r / b) * bc + (c / b)]++;
            }
        }
        for (int i = 0; i < cob.length; i++) {
            cob[i] = respondem[i] * 2 >= b * b;
        }

        // Arestas de fronteira, no sentido anti-horario. O vertice (x, y) e
        // o canto inferior-esquerdo do bloco (x, y), numerado de forma unica
        // para servir de chave.
        int largVert = bc + 1;
        Map<Integer, List<Integer>> saidas = new HashMap<>();
        for (int r = 0; r < br; r++) {
            for (int c = 0; c < bc; c++) {
                if (!cob[r * bc + c]) continue;
                if (r == 0 || !cob[(r - 1) * bc + c])
                    liga(saidas, vert(c, r, largVert), vert(c + 1, r, largVert));
                if (c == bc - 1 || !cob[r * bc + c + 1])
                    liga(saidas, vert(c + 1, r, largVert), vert(c + 1, r + 1, largVert));
                if (r == br - 1 || !cob[(r + 1) * bc + c])
                    liga(saidas, vert(c + 1, r + 1, largVert), vert(c, r + 1, largVert));
                if (c == 0 || !cob[r * bc + c - 1])
                    liga(saidas, vert(c, r + 1, largVert), vert(c, r, largVert));
            }
        }

        List<double[]> aneis = new ArrayList<>();
        for (int[] anel : encadear(saidas, largVert)) {
            // Anel de um bloco so e ponto perdido do SfM, nao area levantada.
            if (anel.length < 8) continue;
            aneis.add(paraMundo(anel, b));
        }

        double lado = crs == Crs.GEOGRAPHIC ? cellSize * 111_320.0 : cellSize;
        return new Footprint(aneis, celulasComDado * lado * lado, b * lado);
    }

    /**
     * Contorno da cobertura, em coordenadas de mundo (Mercator).
     *
     * @param rings  cada anel e uma lista plana x0,y0,x1,y1,..., ja fechada
     * @param areaM2 area coberta, somando as celulas que tem altura
     * @param stepM  lado do bloco usado no contorno, em metros — a precisao
     *               do desenho, nao a da consulta
     */
    public record Footprint(List<double[]> rings, double areaM2, double stepM) {
        public boolean isEmpty() { return rings == null || rings.isEmpty(); }
    }

    /** Celulas que tem altura propria. */
    private boolean[] marcarComDado() {
        boolean[] m = new boolean[cols * rows];
        for (int i = 0; i < m.length; i++) m[i] = !Float.isNaN(grid[i]);
        return m;
    }

    /**
     * Engorda a mascara em {@code raio} celulas nas duas direcoes.
     *
     * Em duas passadas, uma por eixo, em vez de varrer a janela inteira em
     * cada celula: o resultado e o mesmo de uma janela quadrada e o custo cai
     * de raio^2 para raio por celula, o que importa numa grade de milhoes.
     */
    private boolean[] dilatar(boolean[] m, int raio) {
        boolean[] h = new boolean[m.length];
        for (int r = 0; r < rows; r++) {
            int base = r * cols;
            for (int c = 0; c < cols; c++) {
                boolean v = false;
                int de = Math.max(0, c - raio), ate = Math.min(cols - 1, c + raio);
                for (int k = de; k <= ate && !v; k++) v = m[base + k];
                h[base + c] = v;
            }
        }
        boolean[] out = new boolean[m.length];
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) {
                boolean v = false;
                int de = Math.max(0, r - raio), ate = Math.min(rows - 1, r + raio);
                for (int k = de; k <= ate && !v; k++) v = h[k * cols + c];
                out[r * cols + c] = v;
            }
        }
        return out;
    }

    private static int vert(int x, int y, int larg) { return y * larg + x; }

    private static void liga(Map<Integer, List<Integer>> saidas, int de, int para) {
        saidas.computeIfAbsent(de, k -> new ArrayList<>(1)).add(para);
    }

    /**
     * Encadeia as arestas soltas em aneis fechados.
     *
     * Cada aresta sai de um vertice e chega em outro; seguir sempre uma que
     * saia de onde a anterior chegou fecha o anel. Um vertice pode ter duas
     * saidas quando o contorno se estrangula num canto — dai a lista por
     * vertice, e dai a aresta consumida ser removida.
     */
    private static List<int[]> encadear(Map<Integer, List<Integer>> saidas, int larg) {
        List<int[]> aneis = new ArrayList<>();
        // int, e nao Integer: o fechamento do anel compara o ultimo vertice
        // com o primeiro, e com Integer isso compararia referencias. Acima de
        // 127 o cache do Java acaba, as referencias deixam de coincidir e
        // nenhum anel fecharia — exatamente nas grades mais finas.
        for (int inicio : new ArrayList<>(saidas.keySet())) {
            while (true) {
                List<Integer> deInicio = saidas.get(inicio);
                if (deInicio == null || deInicio.isEmpty()) break;

                List<Integer> caminho = new ArrayList<>();
                int atual = inicio;
                caminho.add(atual);
                while (true) {
                    List<Integer> proximos = saidas.get(atual);
                    if (proximos == null || proximos.isEmpty()) break;
                    int seguinte = proximos.remove(proximos.size() - 1);
                    caminho.add(seguinte);
                    if (seguinte == inicio) break;
                    atual = seguinte;
                }
                if (caminho.size() > 2 && caminho.get(caminho.size() - 1) == inicio) {
                    aneis.add(simplificar(caminho, larg));
                }
            }
        }
        return aneis;
    }

    /**
     * Junta os trechos retos num segmento so.
     *
     * O encadeamento entrega um vertice por bloco percorrido; numa borda reta
     * de cem blocos sao cem vertices dizendo a mesma coisa. Guardar so onde a
     * direcao muda corta a contagem em uma ordem de grandeza sem mudar o
     * desenho em um pixel.
     */
    private static int[] simplificar(List<Integer> caminho, int larg) {
        List<Integer> saida = new ArrayList<>();
        int n = caminho.size() - 1;              // o ultimo repete o primeiro
        for (int i = 0; i < n; i++) {
            int ant = caminho.get((i - 1 + n) % n), cur = caminho.get(i), prox = caminho.get((i + 1) % n);
            int dx1 = (cur % larg) - (ant % larg), dy1 = (cur / larg) - (ant / larg);
            int dx2 = (prox % larg) - (cur % larg), dy2 = (prox / larg) - (cur / larg);
            if (dx1 != dx2 || dy1 != dy2) {
                saida.add(cur % larg);
                saida.add(cur / larg);
            }
        }
        int[] out = new int[saida.size()];
        for (int i = 0; i < out.length; i++) out[i] = saida.get(i);
        return out;
    }

    /** Vertices em blocos para coordenadas de mundo. */
    private double[] paraMundo(int[] anel, int bloco) {
        double[] out = new double[anel.length];
        for (int i = 0; i < anel.length; i += 2) {
            double px = minX + anel[i] * (double) bloco * cellSize;
            double py = minY + anel[i + 1] * (double) bloco * cellSize;
            double[] w = toWorld(px, py);
            out[i] = w[0];
            out[i + 1] = w[1];
        }
        return out;
    }

    /**
     * Média do que a nuvem tem num quadrado.
     *
     * Percorre todas as células do quadrado em vez de uma só. Isso conserta
     * o buraco de amostragem — a grade de 1 m fica com boa parte das células
     * vazia porque a densidade do voo varia, e um vértice de 6 m de lado que
     * caia numa dessas vira rasgo na malha, apesar de a área estar voada.
     *
     * Média, e não máxima como na grade: a máxima é a resposta certa para
     * obstrução (o topo do que existe ali obstrui de verdade), mas pegar a
     * máxima de dezenas de células e chamar isso de "a altura do vértice"
     * levantaria o terreno inteiro até o topo da vegetação. A média não
     * desloca o relevo — medida contra a amostragem de hoje, a diferença
     * média é de centímetros.
     */
    @Override
    public Double elevationOver(double worldX, double worldY, double raioM) {
        if (raioM <= 0) return elevationAt(worldX, worldY);

        double[] p = toSurvey(worldX, worldY);
        // O raio vem em metros e a grade pode estar em graus. Converte-se
        // medindo um ponto deslocado, que funciona em qualquer CRS.
        double escala = Mercator.groundScaleAt(Mercator.latOfWorldY(worldY));
        if (escala <= 0) return elevationAt(worldX, worldY);
        double[] q = toSurvey(worldX + raioM / escala, worldY);
        int raioCel = (int) Math.round(Math.abs(q[0] - p[0]) / cellSize);
        if (raioCel < 1) return elevationAt(worldX, worldY);
        raioCel = Math.min(raioCel, MAX_RAIO_AREA);

        int col = (int) ((p[0] - minX) / cellSize);
        int row = (int) ((p[1] - minY) / cellSize);

        double soma = 0;
        int n = 0;
        for (int r = row - raioCel; r <= row + raioCel; r++) {
            if (r < 0 || r >= rows) continue;
            int base = r * cols;
            for (int c = col - raioCel; c <= col + raioCel; c++) {
                if (c < 0 || c >= cols) continue;
                float v = grid[base + c];
                if (Float.isNaN(v)) continue;
                soma += v;
                n++;
            }
        }
        // Quadrado inteiro vazio e' fora do voo de verdade: devolve null para
        // o desenho rasgar ali, em vez de inventar chao.
        return n == 0 ? null : soma / n;
    }

    @Override
    public String sourceName() {
        return "Levantamento " + source.getName();
    }

    @Override
    public double resolutionMeters() {
        return crs == Crs.GEOGRAPHIC ? cellSize * 111_320.0 : cellSize;
    }

    public File file() { return source; }
    public Crs crs() { return crs; }
    public int utmZone() { return utmZone; }
    public boolean utmSouth() { return utmSouth; }
    public long pointsUsed() { return pointsUsed; }
    public double minZ() { return minZ; }
    public double maxZ() { return maxZ; }
    public int cols() { return cols; }
    public int rows() { return rows; }
}
