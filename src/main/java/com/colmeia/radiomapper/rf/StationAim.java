package com.colmeia.radiomapper.rf;

import com.colmeia.radiomapper.geo.ElevationSource;
import com.colmeia.radiomapper.geo.Mercator;
import com.colmeia.radiomapper.model.NetworkPoint;
import com.colmeia.radiomapper.model.Project;
import com.colmeia.radiomapper.model.Radio;

import java.util.ArrayList;
import java.util.List;

/**
 * Como apontar uma estação para o AP dela — potência, azimute, inclinação e
 * altura.
 *
 * <h3>Por que a estação precisa de outra recomendação</h3>
 * A recomendação que já existia ({@link BeamCoverage#recomendarAltura}) responde
 * à pergunta de um AP: "subindo a antena, quanto mais chão eu cubro?". Para uma
 * estação isso não quer dizer nada. Ela não cobre chão nenhum: ela fala com
 * <b>um</b> rádio, o AP ao qual está associada, e a única pergunta que importa
 * é "como fecho ESTE enlace do melhor jeito possível?".
 *
 * São quatro respostas, e cada uma sai de uma conta diferente:
 *
 * <ul>
 *   <li><b>Azimute e inclinação</b> — geometria pura: a direção e o ângulo
 *       vertical da antena desta estação até a antena do AP, com a curvatura
 *       da Terra embutida. Não há o que otimizar aqui, só acertar.</li>
 *   <li><b>Altura</b> — a menor que deixa 60% da primeira zona de Fresnel
 *       livre ao longo de todo o caminho. Tem forma fechada (veja
 *       {@link #alturaParaFresnel}), não precisa de busca.</li>
 *   <li><b>Potência</b> — a que põe o sinal na janela boa. E aqui a resposta
 *       quase nunca é "o máximo", pelo motivo abaixo.</li>
 * </ul>
 *
 * <h3>Por que não recomendar potência máxima</h3>
 * Parece óbvio que mais potência = melhor sinal, e é por isso que tanta
 * estação sai de fábrica no talo. Mas o receptor do outro lado satura: acima
 * de uns −45 dBm o front-end do AP entra em compressão, a taxa de erro sobe e
 * o enlace fica <em>pior</em> com mais potência — o caso clássico do cliente a
 * 200 m da torre que não passa de 20 Mbps. Fora isso, potência sobrando vira
 * interferência nos vizinhos do mesmo canal.
 *
 * Então o alvo é uma janela, não um teto: {@value #ALVO_DBM} dBm como mira,
 * nunca acima de {@value #TETO_DBM}. Quando a distância não deixa chegar nem
 * no alvo com a potência máxima, aí sim a recomendação é o máximo — e a nota
 * diz que o limite é o percurso, não o rádio.
 *
 * <h3>O que isto não sabe</h3>
 * O sinal sai de {@link LinkBudget}: espaço livre, sem difração, sem
 * vegetação, sem chuva e sem piso de ruído. Com visada limpa e Fresnel livre a
 * conta é boa; com o caminho obstruído ela é otimista, e é justamente por isso
 * que a altura para liberar a Fresnel vem junto.
 */
public final class StationAim {

    private StationAim() {}

    /** Raio efetivo da Terra (4/3), o mesmo do perfil e da varredura. */
    private static final double RAIO_EFETIVO_M = 6371000.0 * 4.0 / 3.0;

    /** Fração da primeira zona de Fresnel que se exige livre. */
    public static final double FRESNEL_ALVO = 0.60;

    /** Sinal que se persegue no AP, em dBm: topo do "bom". */
    public static final double ALVO_DBM = -55;

    /** Acima disto o receptor do AP começa a saturar. */
    public static final double TETO_DBM = -45;

    /**
     * Teto prático de potência, em dBm.
     *
     * Rádio 5 GHz comum entrega entre 22 e 27 dBm; 30 é folga para o caso de
     * quem tem equipamento melhor. Não é limite legal — o de EIRP da faixa
     * depende do país e da banda, e quem instala é que responde por ele.
     */
    public static final double POT_MAX_DBM = 30;

    /** Piso de potência, em dBm: abaixo disto a maioria dos rádios não regula. */
    public static final double POT_MIN_DBM = 0;

    /** Quantos pontos do terreno olhar entre as duas antenas. */
    private static final int AMOSTRAS = 256;

    /** Subir mais que isto já não é ajuste de instalação, é outro projeto. */
    private static final double SUBIDA_DEMAIS_M = 30;

    /**
     * Um campo a mexer.
     *
     * @param muda false quando o valor atual já está bom — a linha aparece
     *             assim mesmo, porque "não mexa nisto" também é resposta, e
     *             esconder o campo deixaria a dúvida de se foi conferido
     */
    public record Ajuste(String campo, double atual, double sugerido,
                         String unidade, String porque, boolean muda) {}

    /**
     * @param alturaM      altura sugerida JÁ no modo do rádio (mastro ou cota)
     * @param topoAntenaM  cota absoluta da antena sugerida
     * @param fresnelPct   pior cobertura de Fresnel hoje, em % (NaN sem frequência)
     * @param fresnelPlanoPct idem depois de aplicar a altura sugerida
     */
    public record Plano(boolean vale, String nota, String parNome,
                        double distanciaM, double azimuteDeg, double inclinacaoDeg,
                        double potenciaDbm, double alturaM, double topoAntenaM,
                        double fresnelPct, double fresnelPlanoPct,
                        double rssiAtualDbm, double rssiPlanoDbm,
                        boolean semRelevo, List<Ajuste> ajustes) {

        /** Há alguma mudança a fazer, ou já está tudo no lugar? */
        public boolean temMudanca() {
            for (Ajuste a : ajustes) if (a.muda()) return true;
            return false;
        }
    }

    private static Plano naoDa(String nota) {
        return new Plano(false, nota, "", 0, 0, 0, 0, 0, 0,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, true, List.of());
    }

    /**
     * Monta o plano de apontamento desta estação para o AP dela.
     *
     * @param r    a estação, já com os valores que estão na tela
     * @param p    onde ela está
     * @param elev relevo; sem ele a altura e a Fresnel não são calculadas, mas
     *             azimute, inclinação e potência ainda saem
     */
    public static Plano planejar(Radio r, NetworkPoint p, Project project,
                                 ElevationSource elev) {
        if (r == null || p == null || project == null) {
            return naoDa("Sem rádio ou sem ponto.");
        }
        if (!r.getRole().isStation()) {
            return naoDa("Esta recomendação é de estação: ela aponta "
                    + "para UM ap, e o que se otimiza é esse enlace. Para um AP, "
                    + "use \"Recomendar altura\", que pergunta outra coisa — quanto "
                    + "chão a mais o setor cobre subindo a antena.");
        }

        Radio ap = LinkPeer.radioDe(r, project);
        if (ap == null) {
            return naoDa("Esta estação não tem AP no projeto. Informe o "
                    + "uplink no cadastro do rádio, ou ligue o enlace — sem "
                    + "saber para quem ela aponta, não há azimute nem "
                    + "inclinação a recomendar.");
        }
        NetworkPoint pAp = project.findPointOfRadio(ap.getId()).orElse(null);
        if (pAp == null) return naoDa("O AP desta estação não está em ponto nenhum.");

        double dx = pAp.getX() - p.getX(), dy = pAp.getY() - p.getY();
        double distMundo = Math.hypot(dx, dy);
        if (distMundo <= 0) {
            return naoDa("A estação e o AP estão no mesmo ponto do mapa.");
        }
        double k = Mercator.groundScaleAt(Mercator.latOfWorldY((p.getY() + pAp.getY()) / 2));
        double distM = distMundo * (k <= 0 ? 1 : k);
        double mundoPorM = k <= 0 ? 1 : 1 / k;

        // Azimute compass: 0 = norte, crescendo para leste. Mesma convenção
        // do perfil e da bússola.
        double azimute = (Math.toDegrees(Math.atan2(dx / distMundo, -dy / distMundo)) + 360) % 360;

        // ---------------- terreno entre as duas antenas ----------------
        double[] d = new double[AMOSTRAS];
        double[] solo = new double[AMOSTRAS];
        int semDado = 0;
        for (int i = 0; i < AMOSTRAS; i++) {
            double dm = distM * i / (double) (AMOSTRAS - 1);
            Double h = elev == null ? null
                    : elev.elevationAt(p.getX() + dx / distMundo * dm * mundoPorM,
                                       p.getY() + dy / distMundo * dm * mundoPorM);
            d[i] = dm;
            solo[i] = h == null ? Double.NaN : h;
            if (h == null) semDado++;
        }
        boolean semRelevo = semDado == AMOSTRAS;

        double baseEst = semRelevo ? 0 : maisProximoValido(solo, 0);
        double baseAp = semRelevo ? 0 : maisProximoValido(solo, AMOSTRAS - 1);
        double topoEst = r.antennaTopM(baseEst);
        double topoAp = ap.antennaTopM(baseAp);

        // ---------------- altura que libera a Fresnel ----------------
        double freq = r.getFrequencyMhz() > 0 ? r.getFrequencyMhz() : ap.getFrequencyMhz();
        double topoNecessario = semRelevo ? topoEst
                : alturaParaFresnel(d, solo, distM, topoAp, freq);
        double topoPlano = Math.max(topoEst, topoNecessario);
        double alturaPlano = r.isAbsoluteAltitude() ? topoPlano : topoPlano - baseEst;

        double fresnelHoje = semRelevo ? Double.NaN
                : fresnelPior(d, solo, distM, topoEst, topoAp, freq);
        double fresnelDepois = semRelevo ? Double.NaN
                : fresnelPior(d, solo, distM, topoPlano, topoAp, freq);

        // ---------------- inclinação até a antena do AP ----------------
        // Na altura NOVA: mexer na altura muda o ângulo, e recomendar as duas
        // coisas medidas em alturas diferentes mandaria a antena para o lado
        // errado de quem seguisse a lista na ordem.
        double inclinacao = anguloAte(topoPlano, topoAp, distM);

        // ---------------- potência ----------------
        double fspl = LinkBudget.fspl(distM / 1000.0, freq);
        double ganhos = r.getAntennaGainDbi() - r.getCableLossDb()
                      + ap.getAntennaGainDbi() - ap.getCableLossDb();
        double rssiAtual = Double.isNaN(fspl) ? Double.NaN
                : r.getTxPowerDbm() + ganhos - fspl;
        double potencia = r.getTxPowerDbm();
        String porquePot;
        if (Double.isNaN(fspl)) {
            porquePot = "sem frequência informada não dá para estimar o percurso";
        } else {
            double ideal = ALVO_DBM - (ganhos - fspl);
            potencia = Math.round(Math.max(POT_MIN_DBM, Math.min(POT_MAX_DBM, ideal)) * 2) / 2.0;
            if (ideal > POT_MAX_DBM) {
                porquePot = String.format(
                        "no máximo: o percurso pede %.0f dBm para chegar a %.0f dBm, "
                        + "e o teto prático é %.0f", ideal, ALVO_DBM, POT_MAX_DBM);
            } else if (ideal < POT_MIN_DBM) {
                porquePot = String.format(
                        "no mínimo: mesmo assim o AP recebe %.0f dBm, acima dos "
                        + "%.0f em que o receptor começa a saturar",
                        POT_MIN_DBM + ganhos - fspl, TETO_DBM);
            } else {
                porquePot = String.format(
                        "põe o sinal no AP em %.0f dBm; mais que isto só aquece "
                        + "o receptor e o canal", ALVO_DBM);
            }
        }
        double rssiPlano = Double.isNaN(fspl) ? Double.NaN : potencia + ganhos - fspl;

        // ---------------- a lista ----------------
        List<Ajuste> ajustes = new ArrayList<>();
        ajustes.add(ajuste("Potência", r.getTxPowerDbm(), potencia, "dBm", porquePot, 0.4));
        ajustes.add(ajuste("Azimute", r.getBeamAzimuthDeg(), arredondar(azimute, 1), "°",
                "direção da antena de " + nomeDe(ap) + " vista daqui", 1.0));
        ajustes.add(ajuste("Inclinação", r.getBeamTiltDeg(), arredondar(inclinacao, 1), "°",
                inclinacao < 0 ? "a antena do AP está abaixo desta"
                               : "a antena do AP está acima desta", 0.4));
        if (!semRelevo) {
            String porqueAlt;
            if (Double.isNaN(freq) || freq <= 0) {
                porqueAlt = "sem frequência não dá para calcular Fresnel";
            } else if (topoNecessario <= topoEst + 0.05) {
                porqueAlt = String.format(
                        "já serve: a Fresnel está %.0f%% livre, acima dos %.0f%% da regra",
                        fresnelHoje, FRESNEL_ALVO * 100);
            } else {
                porqueAlt = String.format(
                        "hoje a Fresnel está %.0f%% livre; sobe %.1f m para chegar aos %.0f%%",
                        fresnelHoje, topoPlano - topoEst, FRESNEL_ALVO * 100);
            }
            ajustes.add(ajuste(r.isAbsoluteAltitude() ? "Altitude" : "Altura",
                    r.getAntennaHeightM(), arredondar(alturaPlano, 1), "m", porqueAlt, 0.4));
        }

        return new Plano(true, nota(r, ap, semRelevo, freq, topoPlano - topoEst, fresnelHoje),
                nomeDe(ap), distM, arredondar(azimute, 1), arredondar(inclinacao, 1),
                potencia, arredondar(alturaPlano, 1), topoPlano,
                fresnelHoje, fresnelDepois, rssiAtual, rssiPlano, semRelevo, ajustes);
    }

    /**
     * A menor cota de antena, nesta ponta, que deixa a Fresnel livre.
     *
     * <h3>Por que tem forma fechada</h3>
     * Com a outra ponta fixa, a altura do raio sobre cada amostra é
     * <b>linear</b> na cota desta ponta:
     *
     * <pre>
     * h(d) = topoA·(1 − d/S) + topoB·(d/S) + d·queda(S)/S − queda(d)
     * </pre>
     *
     * Exigir {@code h(d) ≥ solo(d) + 0,6·F1(d)} em cada amostra vira uma
     * desigualdade em {@code topoA}, e o maior dos limites de todas elas é a
     * resposta. Procurar por tentativa, subindo de metro em metro, daria o
     * mesmo número com erro de arredondamento e centenas de vezes mais contas.
     *
     * @return a cota necessária; nunca menos que o solo sob a antena
     */
    public static double alturaParaFresnel(double[] d, double[] solo, double S,
                                           double topoB, double freqMhz) {
        double preciso = -Double.MAX_VALUE;
        for (int i = 0; i < d.length; i++) {
            if (Double.isNaN(solo[i])) continue;
            if (d[i] >= S * 0.995) continue;           // no pé da outra torre
            double fator = 1 - d[i] / S;
            double exigido = solo[i];
            if (freqMhz > 0 && d[i] > 0) {
                double f1 = LinkBudget.fresnelRadius(d[i] / 1000.0,
                        (S - d[i]) / 1000.0, freqMhz / 1000.0);
                if (!Double.isNaN(f1)) exigido += FRESNEL_ALVO * f1;
            }
            double limite = (exigido - topoB * d[i] / S
                    - d[i] * queda(S) / S + queda(d[i])) / fator;
            preciso = Math.max(preciso, limite);
        }
        return preciso == -Double.MAX_VALUE ? topoB : preciso;
    }

    /** Pior cobertura da primeira zona de Fresnel ao longo do caminho, em %. */
    public static double fresnelPior(double[] d, double[] solo, double S,
                                     double topoA, double topoB, double freqMhz) {
        if (freqMhz <= 0) return Double.NaN;
        double pior = Double.MAX_VALUE;
        for (int i = 1; i < d.length - 1; i++) {
            if (Double.isNaN(solo[i])) continue;
            double f1 = LinkBudget.fresnelRadius(d[i] / 1000.0,
                    (S - d[i]) / 1000.0, freqMhz / 1000.0);
            if (Double.isNaN(f1) || f1 <= 0) continue;
            double h = topoA * (1 - d[i] / S) + topoB * (d[i] / S)
                     + d[i] * queda(S) / S - queda(d[i]);
            pior = Math.min(pior, (h - solo[i]) / f1 * 100);
        }
        return pior == Double.MAX_VALUE ? Double.NaN : pior;
    }

    /** Ângulo vertical de uma antena até a outra, com a curvatura embutida. */
    public static double anguloAte(double topoA, double topoB, double S) {
        if (S <= 0) return 0;
        return Math.toDegrees(Math.atan((topoB - topoA + queda(S)) / S));
    }

    private static double queda(double d) { return (d * d) / (2 * RAIO_EFETIVO_M); }

    private static Ajuste ajuste(String campo, double atual, double sugerido,
                                 String unidade, String porque, double tolerancia) {
        return new Ajuste(campo, atual, sugerido, unidade, porque,
                Math.abs(sugerido - atual) > tolerancia);
    }

    private static String nota(Radio r, Radio ap, boolean semRelevo,
                               double freq, double subida, double fresnelHoje) {
        StringBuilder sb = new StringBuilder();
        if (semRelevo) {
            sb.append("Sem altitude nesta área: azimute e potência saem assim "
                    + "mesmo, mas a inclinação supõe as duas antenas na mesma "
                    + "cota e a altura não foi calculada.");
        } else if (freq <= 0) {
            sb.append("Sem frequência informada: não dá para calcular Fresnel "
                    + "nem estimar sinal. Informe a frequência no cadastro do rádio.");
        } else if (subida > SUBIDA_DEMAIS_M) {
            sb.append(String.format(
                    "Atenção: liberar a Fresnel só por esta ponta pede %.0f m "
                    + "a mais de antena. Isto deixou de ser ajuste de instalação — "
                    + "compare com subir a antena do AP, ou com outro ponto para o "
                    + "cliente.", subida));
        } else if (fresnelHoje < FRESNEL_ALVO * 100) {
            sb.append("O caminho tem obstrução de Fresnel: o enlace pode até "
                    + "fechar assim, mas rende abaixo do que a conta de espaço livre "
                    + "promete. A altura sugerida é a que tira essa perda.");
        } else {
            sb.append("Visada e Fresnel livres: a estimativa de sinal vale.");
        }
        if (!ap.hasRfData()) {
            sb.append("\nO AP está sem dados de RF completos — a estimativa usa "
                    + "o que há e pode estar otimista.");
        }
        return sb.toString();
    }

    private static String nomeDe(Radio r) {
        if (r.getName() != null && !r.getName().isBlank()) return r.getName();
        return r.getHost() == null || r.getHost().isBlank() ? "AP" : r.getHost();
    }

    private static double arredondar(double v, int casas) {
        double f = Math.pow(10, casas);
        return Math.round(v * f) / f;
    }

    /** Amostra válida mais próxima deste índice — ver o mesmo truque no perfil. */
    private static double maisProximoValido(double[] v, int i) {
        for (int passo = 0; passo < v.length; passo++) {
            int a = i - passo, b = i + passo;
            if (a >= 0 && !Double.isNaN(v[a])) return v[a];
            if (b < v.length && !Double.isNaN(v[b])) return v[b];
        }
        return 0;
    }
}
