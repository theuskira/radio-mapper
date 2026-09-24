package com.colmeia.radiomapper.rf;

import com.colmeia.radiomapper.geo.ElevationSource;
import com.colmeia.radiomapper.model.Radio;

import java.util.ArrayList;

/**
 * Até onde este rádio realmente alcança — o feixe simulado.
 *
 * <h3>O que isto resolve</h3>
 * O alcance do cadastro é um número que o usuário digita. Ele controla o raio
 * do setor desenhado no mapa e a extensão do perfil, e mais nada: não entra em
 * nenhuma conta de RF. Ou seja, o desenho mostra até onde alguém <i>disse</i>
 * que a antena chega, não até onde ela chega.
 *
 * Aqui o alcance é calculado. Cada direção dentro da abertura vira um raio que
 * avança até esbarrar no primeiro dos dois limites reais:
 *
 * <ol>
 *   <li><b>O orçamento de enlace.</b> Potência, ganho das duas pontas, perda de
 *       cabo e a perda de percurso pela distância. Quando o sinal cai abaixo do
 *       mínimo pedido, acabou o alcance — e isso acontece antes nas bordas da
 *       abertura, onde a antena já irradia menos, o que é o que dá ao lobo a
 *       forma de pétala em vez de fatia de pizza.</li>
 *   <li><b>O terreno.</b> Um morro no caminho encerra a direção ali, por mais
 *       potência que sobre. É o mesmo teste de visada do perfil lateral, com o
 *       raio efetivo 4/3 da Terra, só que feito em todas as direções.</li>
 * </ol>
 *
 * <h3>O que isto NÃO é</h3>
 * Continua sendo espaço livre: não há difração sobre o obstáculo, nem
 * atenuação por vegetação ou chuva, nem diagrama de irradiação real da antena
 * — a abertura de meia potência é aproximada pela mesma parábola do
 * {@link LinkBudget}. E a visada é geométrica: <b>não exige zona de Fresnel
 * desobstruída</b>, então uma direção pode aparecer como alcançável e ainda
 * assim render mal. Para o veredito fino de um enlace específico, o perfil
 * lateral continua sendo o lugar — ele mostra Fresnel.
 *
 * O resultado é um limite superior honesto: onde isto diz que não chega, não
 * chega mesmo.
 */
public final class BeamCoverage {

    private BeamCoverage() {}

    /** Raio efetivo da Terra (4/3), a convenção de rádio-enlace. */
    private static final double EARTH_EFFECTIVE_R = 6371000.0 * 4.0 / 3.0;

    /**
     * Largura de terreno que a varredura precisa conseguir distinguir.
     *
     * É o que define quantas direções varrer: o arco entre dois raios, lá na
     * borda do alcance, tem que caber nisto. Com 3° — o que se usava — esse
     * arco passa de 130 m a 2,5 km, e nada do que acontece dentro dele é
     * observado; o desenho então preenchia aquilo por interpolação, que é o
     * mesmo que inventar.
     */
    private static final double RESOLUCAO_M = 6;

    /** Teto de raios. Acima disto o ganho de detalhe não paga o tempo. */
    private static final int MAX_RAYS = 2880;

    /** Teto de amostras de relevo por varredura, para a tela seguir viva. */
    private static final long MAX_AMOSTRAS = 3_000_000L;

    /** Lado máximo da grade de saída, em células. */
    private static final int MAX_GRADE = 1100;

    /**
     * A cobertura como grade alinhada ao mundo.
     *
     * <h3>Por que grade e não polígono</h3>
     * O alcance de uma direção é um número, e desenhar com ele obriga a
     * preencher tudo entre a antena e aquele ponto — inclusive o fundo de uma
     * cava que a varredura marcou como escondido. Num relevo recortado isso
     * não é detalhe: neste projeto, 71% do que o desenho antigo pintava estava
     * na sombra do relevo.
     *
     * Contorno também não resolve. A sombra de um terreno acidentado tem a
     * forma de uma mancha cheia de furos, que nenhuma sequência de vértices
     * por direção representa. A grade representa, célula por célula, e é a
     * mesma coisa que a varredura já sabia.
     *
     * @param nivel índice em {@code niveis}, ou -1 onde não chega sinal. Uma
     *              célula por posição, em ordem de linha.
     */
    public record Cobertura(double minX, double minY, double maxX, double maxY,
                            int cols, int rows, double[] niveis, byte[] nivel) {

        public boolean vazia() {
            if (nivel == null) return true;
            for (byte b : nivel) if (b >= 0) return false;
            return true;
        }

        public double larguraCelula() { return (maxX - minX) / cols; }
        public double alturaCelula() { return (maxY - minY) / rows; }

        /** Índice do nível neste ponto do mundo, ou -1 fora da cobertura. */
        public int nivelEm(double wx, double wy) {
            if (nivel == null || wx < minX || wx >= maxX || wy < minY || wy >= maxY) return -1;
            int c = (int) ((wx - minX) / (maxX - minX) * cols);
            int r = (int) ((wy - minY) / (maxY - minY) * rows);
            if (c < 0 || r < 0 || c >= cols || r >= rows) return -1;
            return nivel[r * cols + c];
        }

        /** Quantas células têm sinal — a área coberta, em células. */
        public int celulasCobertas() {
            int n = 0;
            if (nivel != null) for (byte b : nivel) if (b >= 0) n++;
            return n;
        }
    }

    /**
     * O que assumir sobre a outra ponta e sobre o que conta como "chega".
     *
     * Um alcance só existe em relação a quem está recebendo: a mesma antena
     * cobre muito mais longe falando com um painel de 25 dBi do que com um
     * celular. Por isso a outra ponta é parâmetro, e não constante escondida.
     *
     * @param minRssiDbm   sinal mínimo que conta como alcance
     * @param farGainDbi   ganho da antena da outra ponta
     * @param farCableLossDb perda de cabo da outra ponta
     * @param rxHeightM    altura do receptor acima do solo
     * @param maxRangeM    teto de busca, para não varrer o mapa inteiro
     * @param useTerrain   false ignora o relevo e devolve só o limite de RF
     */
    public record Params(double minRssiDbm, double farGainDbi, double farCableLossDb,
                         double rxHeightM, double maxRangeM, boolean useTerrain) {

        public static Params padrao(Radio r) {
            // Espelhar a antena deste rádio é o palpite menos arbitrário: é o
            // caso do enlace ponto a ponto, onde as duas pontas são iguais.
            // Quem decide o palpite da outra ponta e o papel do radio: ponto
            // a ponto espelha esta antena, setor assume um CPE do outro lado,
            // estacao assume uma setorial. Ver RadioRole#peerGainGuessDbi.
            return new Params(-70, r.getRole().peerGainGuessDbi(r.getAntennaGainDbi()),
                              r.getCableLossDb(), 5, 30_000, true);
        }
    }

    /**
     * Até onde o sinal se mantém acima de um nível.
     *
     * O lobo inteiro responde "chega ou não chega", que é pouco: chegar com
     * -80 dBm e chegar com -50 dBm levam a decisões opostas sobre o que
     * instalar do outro lado. As faixas são o mesmo lobo recortado nos níveis
     * que mudam essa decisão, e saem da MESMA varredura — cada raio guarda,
     * de uma vez, a distância em que cruzou cada nível.
     *
     * O desenho de cada nível sai da grade de {@link Cobertura}, então aqui
     * ficam só os números que a leitura em texto precisa.
     *
     * @param dbm       nível desta faixa
     * @param qualidade a palavra que {@link LinkBudget#quality} dá a ele
     * @param maxM      maior alcance dentro desta faixa
     * @param meanM     alcance médio
     * @param celulas   quantas células da grade chegam a este nível
     */
    public record Faixa(double dbm, String qualidade, double maxM, double meanM, int celulas) {
        public boolean valid() { return maxM > 0; }
    }

    /**
     * Níveis que separam decisões diferentes em campo. São os mesmos limites
     * que {@link LinkBudget#quality} usa para nomear o sinal — ter duas
     * escalas para a mesma coisa só confundiria quem lê as duas telas.
     */
    private static final double[] NIVEIS = { -55, -65, -75, -83 };

    /**
     * @param cobertura    onde o sinal chega, célula por célula. Null se não
     *                     deu para simular.
     * @param budgetRangeM alcance no eixo só pelo orçamento de enlace, sem terreno
     * @param maxReachM    maior alcance entre as direções
     * @param minReachM    menor
     * @param meanReachM   média
     * @param rays         direções avaliadas
     * @param stepDeg      espaçamento angular da varredura — a largura do que
     *                     ela consegue enxergar
     * @param blockedRays  quantas pararam no terreno antes do limite de RF
     * @param noElevation  amostras sem dado de relevo
     * @param note         o que o usuário precisa saber para ler o resultado
     */
    public record Result(Cobertura cobertura, double budgetRangeM,
                         double maxReachM, double minReachM, double meanReachM,
                         int rays, double stepDeg, int blockedRays, int noElevation,
                         boolean terrainUsed, java.util.List<Faixa> faixas, String note) {

        public boolean valid() { return cobertura != null && !cobertura.vazia(); }
    }

    /**
     * Até onde o orçamento de enlace chega no eixo, antes de olhar o terreno.
     *
     * É conta fechada, sem varredura nenhuma: serve para saber de quanto
     * terreno a simulação vai precisar ANTES de sair buscando relevo. Sem
     * isto, quem chama só tinha o teto configurado para se guiar, e baixava
     * relevo de uma área dezenas de vezes maior que a varrida.
     *
     * @return metros, já limitado pelo teto de {@code prm}
     */
    public static double reachEstimateM(Radio r, Params prm) {
        double freq = r.getFrequencyMhz();
        if (freq <= 0) return 0;
        double linkGain = r.getTxPowerDbm() + r.getAntennaGainDbi() - r.getCableLossDb()
                        + prm.farGainDbi() - prm.farCableLossDb();
        double d = distanceForFspl(linkGain - prm.minRssiDbm(), freq);
        return Math.max(0, Math.min(d, prm.maxRangeM()));
    }

    /**
     * Caixa, em coordenadas de mundo, que a varredura pode alcançar.
     *
     * Um setor de 30° varre uma fatia fina; pedir o quadrado inteiro em volta
     * da antena faria baixar relevo de uma área muitas vezes maior do que a
     * que será olhada. A caixa aqui cobre o arco de verdade — o centro, as
     * duas bordas e os eixos cardeais que o arco cruza.
     *
     * @return {minX, minY, maxX, maxY}, ou null se não há o que varrer
     */
    public static double[] sweepBoundsWorld(Radio r, Params prm, double cx, double cy,
                                            double worldPerMeter) {
        double raio = reachEstimateM(r, prm) * worldPerMeter;
        if (!(raio > 0)) return null;

        double largura = r.getBeamWidthDeg();
        if (largura <= 0 || largura >= 360) {
            return new double[] { cx - raio, cy - raio, cx + raio, cy + raio };
        }

        double meia = largura / 2;
        double a0 = r.getBeamAzimuthDeg() - meia;
        double a1 = r.getBeamAzimuthDeg() + meia;

        double minX = cx, maxX = cx, minY = cy, maxY = cy;   // a antena sempre entra
        for (double az = a0; ; az += 1) {
            if (az > a1) az = a1;
            double rad = Math.toRadians(az);
            double x = cx + Math.sin(rad) * raio;
            double y = cy - Math.cos(rad) * raio;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            if (az >= a1) break;
        }
        return new double[] { minX, minY, maxX, maxY };
    }

    /**
     * Assinatura do que influencia o resultado.
     *
     * Duas simulações com a mesma assinatura dão o mesmo lobo, então a
     * segunda pode ser dispensada. Tudo que entra na conta precisa estar aqui:
     * esquecer um campo faria a tela mostrar um desenho velho depois de uma
     * mudança real, que é pior do que recalcular à toa.
     */
    public static String signature(Radio r, Params prm, double cx, double cy) {
        return String.join("|",
                fmt(cx), fmt(cy),
                fmt(r.getTxPowerDbm()), fmt(r.getAntennaGainDbi()), fmt(r.getCableLossDb()),
                fmt(r.getFrequencyMhz()), fmt(r.getBeamWidthDeg()),
                fmt(r.getBeamVerticalWidthDeg()), fmt(r.getBeamAzimuthDeg()),
                fmt(r.getBeamTiltDeg()), fmt(r.getAntennaHeightM()),
                r.getAltitudeMode().name(),
                fmt(prm.minRssiDbm()), fmt(prm.farGainDbi()), fmt(prm.farCableLossDb()),
                fmt(prm.rxHeightM()), fmt(prm.maxRangeM()), String.valueOf(prm.useTerrain()));
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.4f", v);
    }

    /**
     * @param cx cy        posição da antena, em coordenadas de mundo
     * @param worldPerMeter quantas unidades de mundo tem um metro de chão aqui
     */
    public static Result simulate(Radio r, double cx, double cy,
                                  ElevationSource elev, double worldPerMeter,
                                  Params prm) {

        double freq = r.getFrequencyMhz();
        if (freq <= 0 || r.getAntennaGainDbi() == 0 && r.getTxPowerDbm() == 0) {
            return vazio("Informe frequência, ganho e potência do rádio para simular o alcance.");
        }

        // Ganho total do enlace: tudo que não depende da distância.
        double linkGain = r.getTxPowerDbm() + r.getAntennaGainDbi() - r.getCableLossDb()
                        + prm.farGainDbi() - prm.farCableLossDb();
        double fsplMax = linkGain - prm.minRssiDbm;
        double budgetRange = distanceForFspl(fsplMax, freq);
        if (!(budgetRange > 0)) {
            return vazio("Com estes números o sinal já nasce abaixo do mínimo pedido.");
        }

        double limite = Math.min(budgetRange, prm.maxRangeM());

        // Solo sob a antena. Sem relevo e com altura de mastro, não há de onde
        // partir: a simulação cai para o limite de RF puro, em terreno plano.
        Double solo = elev == null ? null : elev.elevationAt(cx, cy);
        boolean temRelevo = prm.useTerrain() && elev != null && solo != null;
        double solo0 = solo == null ? 0 : solo;
        double antena = r.antennaTopM(solo0);

        double largura = r.getBeamWidthDeg();
        boolean omni = largura <= 0 || largura >= 360;

        // A varredura precisa ser fina o bastante para o arco entre dois raios
        // caber na resolucao pedida; senao o que fica entre eles nao e' medido
        // e o desenho teria de inventar.
        double passo = Math.max(2, Math.min(RESOLUCAO_M, limite / 40.0));
        double[] offsets = direcoes(largura, omni, limite, passo);

        double[] alcance = new double[offsets.length];
        int bloqueados = 0, semDado = 0;
        double sombraTotal = 0;

        // Níveis a recortar: só os melhores que a borda pedida — abaixo dela o
        // lobo não existe, e uma faixa vazia na legenda é ruído.
        double[] niveis = niveisAcimaDe(prm.minRssiDbm());
        double[][] alcanceNivel = new double[niveis.length][offsets.length];
        double soma = 0, maior = 0, menor = Double.MAX_VALUE;

        // Onde o sinal chega, amostra por amostra — e não só até onde.
        //
        // Guardar apenas o alcance de cada direção obriga o desenho a preencher
        // tudo entre a antena e aquele ponto, inclusive o fundo de uma cava que
        // a própria varredura marcou como sombra. O mapa passava a prometer
        // sinal exatamente onde a conta diz que não há. Com a máscara, o buraco
        // é recortado do desenho.
        int nPassos = (int) Math.floor((limite - passo) / passo) + 1;
        if (nPassos < 1) nPassos = 1;
        boolean[][] visivel = new boolean[offsets.length][nPassos];
        boolean[][][] visivelNivel = new boolean[niveis.length][offsets.length][nPassos];

        for (int i = 0; i < offsets.length; i++) {
            double azim = r.getBeamAzimuthDeg() + offsets[i];
            double rad = Math.toRadians(azim);
            double dirX = Math.sin(rad), dirY = -Math.cos(rad);

            // Perda por estar fora do eixo no plano horizontal: constante ao
            // longo do raio, e é ela que encurta as bordas do lobo.
            double perdaH = omni ? 0 : LinkBudget.offAxisLoss(offsets[i], largura);

            double horizonte = -Math.PI / 2;   // maior ângulo de terreno já visto
            double chegou = 0;
            boolean houveSombra = false;
            int amostrasEmSombra = 0, amostras = 0;

            for (int k = 0; k < nPassos; k++) {
                double d = passo * (k + 1);
                double wx = cx + dirX * d * worldPerMeter;
                double wy = cy + dirY * d * worldPerMeter;

                double g = solo0;
                if (temRelevo) {
                    Double h = elev.elevationAt(wx, wy);
                    if (h == null) semDado++; else g = h;
                }

                // A Terra cai debaixo do enlace; o raio 4/3 já embute a
                // refração da atmosfera, que devolve parte dessa queda.
                double queda = d * d / (2 * EARTH_EFFECTIVE_R);
                double topoTerreno = g - queda;
                double angAlvo = Math.atan2(topoTerreno + prm.rxHeightM() - antena, d);

                amostras++;
                boolean naSombra = temRelevo && angAlvo < horizonte;

                double perdaV = LinkBudget.offAxisLoss(
                        Math.toDegrees(angAlvo) - r.getBeamTiltDeg(),
                        r.getBeamVerticalWidthDeg());
                double rssi = linkGain - perdaH - perdaV - LinkBudget.fspl(d / 1000.0, freq);

                if (naSombra) {
                    // Sombra NAO encerra a direcao. Uma quebra convexa do
                    // relevo esconde o que vem logo depois dela e volta a
                    // liberar mais adiante — a borda de uma cava esconde o
                    // fundo e nao esconde a encosta do outro lado. Parar na
                    // primeira sombra encolhia o lobo a poucas dezenas de
                    // metros num terreno assim, enquanto o perfil do enlace,
                    // que olha a reta ate a outra antena, mostrava a visada
                    // livre. As duas telas discordavam por causa disto.
                    amostrasEmSombra++;
                    houveSombra = true;
                } else if (rssi >= prm.minRssiDbm()) {
                    chegou = d;
                    visivel[i][k] = true;
                    // O mesmo passo alimenta todas as faixas: quem quer saber
                    // até onde o sinal fica acima de -65 não precisa de outra
                    // varredura, só de outra comparação.
                    for (int j = 0; j < niveis.length; j++) {
                        if (rssi >= niveis[j]) {
                            alcanceNivel[j][i] = d;
                            visivelNivel[j][i][k] = true;
                        }
                    }
                }

                // O terreno DESTA amostra só passa a obstruir dali para a
                // frente — senão ele bloquearia a si mesmo. Atualiza mesmo em
                // sombra: o horizonte é do relevo, não da visibilidade.
                double angTerreno = Math.atan2(topoTerreno - antena, d);
                if (angTerreno > horizonte) horizonte = angTerreno;
            }

            alcance[i] = chegou;
            if (houveSombra) bloqueados++;
            if (amostras > 0) sombraTotal += amostrasEmSombra / (double) amostras;
            soma += chegou;
            maior = Math.max(maior, chegou);
            menor = Math.min(menor, chegou);
        }

        if (maior <= 0) {
            return vazio("Nenhuma direção alcança o sinal mínimo pedido.");
        }

        Cobertura cob = montarGrade(cx, cy, r.getBeamAzimuthDeg(), offsets,
                visivelNivel, niveis, passo, worldPerMeter, omni, maior);

        StringBuilder nota = new StringBuilder();
        if (!temRelevo) {
            nota.append("Sem relevo sob a antena: o lobo mostra só o limite de RF, "
                    + "como se o terreno fosse plano. ");
        } else if (bloqueados > 0) {
            double pct = 100.0 * sombraTotal / offsets.length;
            nota.append(String.format("%d de %d direções têm trecho em sombra do relevo "
                    + "(%.0f%% do caminho, em média). O que fica na sombra de uma quebra "
                    + "do relevo — o fundo de uma cava, o outro lado de um espigão — está "
                    + "recortado do desenho, e não pintado como se tivesse sinal. ",
                    bloqueados, offsets.length, pct));
        }
        if (semDado > 0) {
            nota.append("Parte do caminho está fora da área com dado de relevo. ");
        }
        nota.append(String.format("Varredura a cada %.1f°: uma fresta de visada mais "
                + "estreita que isso pode passar despercebida, e uma ponta fina no "
                + "desenho corresponde a um corredor estreito de verdade. ", passoAngDe(offsets, largura, omni)));
        nota.append("Visada geométrica, sem exigir Fresnel livre — use o perfil "
                + "lateral para o veredito de um enlace específico.");

        java.util.List<Faixa> faixas = new ArrayList<>();
        for (int j = 0; j < niveis.length; j++) {
            double mx = 0, sm = 0;
            for (double v : alcanceNivel[j]) { mx = Math.max(mx, v); sm += v; }
            if (mx <= 0) continue;   // nada alcança este nível: faixa não existe
            int celulas = 0;
            for (byte b : cob.nivel()) if (b >= 0 && b <= j) celulas++;
            faixas.add(new Faixa(niveis[j], LinkBudget.quality(niveis[j]),
                    mx, sm / offsets.length, celulas));
        }

        double passoAng = omni ? 360.0 / offsets.length : largura / (offsets.length - 1.0);
        return new Result(cob, budgetRange, maior, menor, soma / offsets.length,
                offsets.length, passoAng, bloqueados, semDado, temRelevo,
                faixas, nota.toString().trim());
    }

    /**
     * Uma altura testada e o que ela rendeu.
     *
     * @param alturaM   altura (ou cota) da antena
     * @param areaM2    area que recebe o sinal minimo, ja descontadas as sombras
     * @param alcanceMedioM  media das direcoes
     */
    public record Degrau(double alturaM, double areaM2, double alcanceMedioM) {}

    /**
     * O que a varredura recomenda, e por que.
     *
     * @param degraus    todas as alturas testadas, em ordem
     * @param recomendadaM  a altura sugerida
     * @param atualM     a altura de onde se partiu
     * @param nota       a frase que explica a escolha
     */
    public record Recomendacao(java.util.List<Degrau> degraus, double recomendadaM,
                               double atualM, String nota) {
        public boolean valid() { return degraus != null && degraus.size() > 1; }

        /** O degrau da altura recomendada. */
        public Degrau escolhido() {
            Degrau melhor = null;
            for (Degrau d : degraus) {
                if (melhor == null
                        || Math.abs(d.alturaM() - recomendadaM)
                           < Math.abs(melhor.alturaM() - recomendadaM)) melhor = d;
            }
            return melhor;
        }
    }

    /**
     * Até onde vale a pena subir a antena.
     *
     * <h3>Por que a altura, e não a potência</h3>
     * Num terreno acidentado o que limita o alcance quase nunca é o orçamento
     * de enlace — é o morro. Dobrar a potência rende 3 dB; subir a antena
     * acima da quebra do relevo pode render quilômetros. Esta varredura mede
     * exatamente isso: refaz a cobertura inteira para cada altura e compara a
     * ÁREA que realmente recebe sinal, já descontadas as sombras.
     *
     * <h3>Por que não simplesmente a mais alta</h3>
     * A área cresce com a altura, então "a melhor" seria sempre o topo da
     * faixa — uma resposta inútil, porque torre custa dinheiro. O que se
     * procura é o joelho da curva: a última altura que ainda traz ganho
     * relevante. Daí para cima cada degrau rende menos que {@value #GANHO_MINIMO}
     * da área, e o metro de torre deixa de se pagar.
     *
     * @param degraus quantas alturas testar entre a atual e {@code tetoM}
     */
    public static Recomendacao recomendarAltura(Radio r, double cx, double cy,
                                                ElevationSource elev, double worldPerMeter,
                                                Params prm, double tetoM, int degraus) {
        double atual = r.getAntennaHeightM();
        if (tetoM <= atual || degraus < 2) {
            return new Recomendacao(java.util.List.of(), atual, atual,
                    "Nada a testar: o teto de altura não está acima da altura atual.");
        }

        java.util.List<Degrau> lista = new ArrayList<>();
        Radio copia = new Radio();
        copiarRf(r, copia);

        for (int i = 0; i < degraus; i++) {
            double h = atual + (tetoM - atual) * i / (degraus - 1.0);
            copia.setAntennaHeightM(h);
            Result res = simulate(copia, cx, cy, elev, worldPerMeter, prm);
            if (!res.valid()) { lista.add(new Degrau(h, 0, 0)); continue; }
            lista.add(new Degrau(h, areaM2(res), res.meanReachM()));
        }

        // O joelho: o ultimo degrau que ainda cresce mais que o minimo em
        // relacao ao anterior. Sem nenhum, a altura atual ja resolve.
        double escolhida = lista.get(0).alturaM();
        for (int i = 1; i < lista.size(); i++) {
            double antes = lista.get(i - 1).areaM2();
            double agora = lista.get(i).areaM2();
            if (antes > 0 && (agora - antes) / antes < GANHO_MINIMO) break;
            escolhida = lista.get(i).alturaM();
        }

        double areaAtual = lista.get(0).areaM2();
        double areaEscolhida = areaAtual;
        for (Degrau d : lista) if (d.alturaM() == escolhida) areaEscolhida = d.areaM2();

        String nota;
        if (escolhida <= atual + 0.01) {
            nota = String.format("Subir a antena rende pouco aqui: de %.0f m para %.0f m "
                    + "a área com sinal cresce menos de %.0f%%. O que limita o alcance "
                    + "não é a altura.", atual, tetoM, GANHO_MINIMO * 100);
        } else if (areaAtual <= 0) {
            nota = String.format("Na altura atual nada alcança o mínimo pedido. A %.0f m "
                    + "a área com sinal passa a %.2f km².", escolhida, areaEscolhida / 1e6);
        } else {
            nota = String.format("A %.0f m a área com sinal é %.2f km², contra %.2f km² "
                    + "nos %.0f m de agora (%+.0f%%). Acima disso cada degrau rende menos "
                    + "de %.0f%%, e o metro de torre deixa de se pagar.",
                    escolhida, areaEscolhida / 1e6, areaAtual / 1e6, atual,
                    100 * (areaEscolhida - areaAtual) / areaAtual, GANHO_MINIMO * 100);
        }
        return new Recomendacao(lista, escolhida, atual, nota);
    }

    /** Ganho de área abaixo do qual subir mais não se justifica. */
    private static final double GANHO_MINIMO = 0.08;

    /** Área coberta, em metros quadrados, a partir da grade. */
    private static double areaM2(Result res) {
        Cobertura c = res.cobertura();
        if (c == null) return 0;
        // A celula e' quadrada em coordenadas de mundo; em metros de chao ela
        // encolhe pelo cosseno da latitude, ja embutido no alcance maximo.
        double ladoM = 2.0 * res.maxReachM() / c.cols();
        return c.celulasCobertas() * ladoM * ladoM;
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

    // ------------------------ Apoio ------------------------

    /**
     * Distância, em metros, em que a perda de espaço livre atinge o valor dado.
     *
     * É a inversa de {@link LinkBudget#fspl}: ela dá a perda a partir da
     * distância, aqui queremos a distância que produz a perda que o orçamento
     * ainda suporta.
     */
    public static double distanceForFspl(double fsplDb, double freqMhz) {
        if (freqMhz <= 0) return 0;
        double logKm = (fsplDb - 32.44 - 20 * Math.log10(freqMhz)) / 20.0;
        double km = Math.pow(10, logKm);
        return Double.isFinite(km) && km > 0 ? km * 1000.0 : 0;
    }

    private static double passoAngDe(double[] offsets, double largura, boolean omni) {
        return omni ? 360.0 / offsets.length : largura / (offsets.length - 1.0);
    }

    /**
     * Ângulos a varrer, relativos ao azimute da antena.
     *
     * O número sai da geometria, e não de uma constante: quantos raios são
     * precisos para que o arco entre dois vizinhos, na borda do alcance, não
     * passe de {@link #RESOLUCAO_M}. Um setor estreito precisa de poucos, um
     * omni de 2,5 km precisa de milhares — e como cada raio custa menos de
     * meio microssegundo por amostra, isso cabe.
     */
    private static double[] direcoes(double largura, boolean omni,
                                     double limite, double passo) {
        double arcoTotal = omni ? 360 : largura;
        double raioMax = Math.max(1, limite);
        // Arco de terreno que a abertura cobre na borda, dividido pela
        // resolucao: e' quantas fatias sao precisas.
        double fatias = Math.toRadians(arcoTotal) * raioMax / RESOLUCAO_M;

        long passos = Math.max(1, Math.round(limite / passo));
        long teto = Math.max(60, MAX_AMOSTRAS / passos);
        int n = (int) Math.min(Math.min(MAX_RAYS, teto),
                               Math.max(omni ? 120 : 13, Math.ceil(fatias)));

        double[] out = new double[n];
        if (omni) {
            for (int i = 0; i < n; i++) out[i] = 360.0 * i / n;
        } else {
            for (int i = 0; i < n; i++) out[i] = -largura / 2 + largura * i / (n - 1.0);
        }
        return out;
    }

    /**
     * Da varredura polar para uma grade alinhada ao mundo.
     *
     * O caminho é o inverso do que parece natural: em vez de espalhar cada
     * amostra polar sobre as células que ela toca, percorre-se a grade e
     * pergunta-se, para o centro de cada célula, em que direção e a que
     * distância ela está da antena. Assim nada é interpolado — cada célula
     * recebe o veredito da amostra que de fato a observou, e onde a varredura
     * não viu, fica vazio.
     *
     * A grade é quadrada em volta do alcance máximo, com célula da ordem da
     * resolução da varredura: mais fina do que isso só criaria degraus a
     * partir de um dado que não os tem.
     */
    private static Cobertura montarGrade(double cx, double cy, double azimute,
                                         double[] offsets, boolean[][][] visivelNivel,
                                         double[] niveis, double passo,
                                         double worldPerMeter, boolean omni,
                                         double maiorAlcance) {
        double raio = Math.max(passo, maiorAlcance);
        double raioW = raio * worldPerMeter;
        double minX = cx - raioW, maxX = cx + raioW;
        double minY = cy - raioW, maxY = cy + raioW;

        int lado = (int) Math.min(MAX_GRADE, Math.max(64, Math.round(2 * raio / RESOLUCAO_M)));
        byte[] nivel = new byte[lado * lado];
        java.util.Arrays.fill(nivel, (byte) -1);

        int nDir = offsets.length;
        int nPassos = visivelNivel.length == 0 ? 0 : visivelNivel[0][0].length;
        if (nPassos == 0) {
            return new Cobertura(minX, minY, maxX, maxY, lado, lado, niveis, nivel);
        }

        // Onde comeca a varredura e quanto ela anda a cada direcao: e' o que
        // permite ir do angulo de volta para o indice do raio.
        double passoAng = omni ? 360.0 / nDir
                               : (nDir > 1 ? offsets[1] - offsets[0] : 1);
        double off0 = offsets[0];

        for (int r = 0; r < lado; r++) {
            double wy = minY + (maxY - minY) * (r + 0.5) / lado;
            for (int c = 0; c < lado; c++) {
                double wx = minX + (maxX - minX) * (c + 0.5) / lado;

                double dx = (wx - cx) / worldPerMeter;
                double dy = (wy - cy) / worldPerMeter;
                double d = Math.hypot(dx, dy);
                if (d > raio) continue;

                int k = (int) Math.round(d / passo) - 1;
                if (k < 0) k = 0;
                if (k >= nPassos) continue;

                // Azimute da celula, no mesmo referencial dos offsets.
                double az = Math.toDegrees(Math.atan2(dx, -dy)) - azimute;
                double rel = az - off0;
                if (omni) {
                    rel = ((rel % 360) + 360) % 360;
                } else {
                    while (rel > 180) rel -= 360;
                    while (rel < -180) rel += 360;
                }
                int i = (int) Math.round(rel / passoAng);
                if (omni) {
                    i = ((i % nDir) + nDir) % nDir;
                } else if (i < 0 || i >= nDir) {
                    continue;
                }

                // Melhor nivel que chega aqui: os niveis vem do melhor para o
                // pior, entao o primeiro que responde ja e' a resposta.
                for (int j = 0; j < visivelNivel.length; j++) {
                    if (visivelNivel[j][i][k]) { nivel[r * lado + c] = (byte) j; break; }
                }
            }
        }
        return new Cobertura(minX, minY, maxX, maxY, lado, lado, niveis, nivel);
    }

    /**
     * Os níveis de qualidade melhores que a borda pedida, do melhor ao pior,
     * terminando na própria borda.
     */
    static double[] niveisAcimaDe(double borda) {
        java.util.List<Double> out = new ArrayList<>();
        for (double n : NIVEIS) {
            if (n > borda) out.add(n);
        }
        out.add(borda);
        double[] v = new double[out.size()];
        for (int i = 0; i < v.length; i++) v[i] = out.get(i);
        return v;
    }

    private static Result vazio(String nota) {
        return new Result(null, 0, 0, 0, 0, 0, 0, 0, 0, false,
                java.util.List.of(), nota);
    }
}
