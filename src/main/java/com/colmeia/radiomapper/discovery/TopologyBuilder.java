package com.colmeia.radiomapper.discovery;

import com.colmeia.radiomapper.model.Link;
import com.colmeia.radiomapper.model.LinkOrigin;
import com.colmeia.radiomapper.model.NetworkPoint;
import com.colmeia.radiomapper.model.Project;
import com.colmeia.radiomapper.model.Radio;
import com.colmeia.radiomapper.model.RadioStatus;
import com.colmeia.radiomapper.model.Router;
import com.colmeia.radiomapper.ssh.RouterPing;
import com.colmeia.radiomapper.ssh.NeighborInfo;
import com.colmeia.radiomapper.ssh.RadioProbe;
import com.colmeia.radiomapper.util.Log;
import com.colmeia.radiomapper.util.Settings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

public final class TopologyBuilder {

    public static class ProbeReport {
        public final Radio radio;
        public final List<NeighborInfo> neighbors;
        public final String error;
        public ProbeReport(Radio radio, List<NeighborInfo> neighbors, String error) {
            this.radio = radio;
            this.neighbors = neighbors;
            this.error = error;
        }
    }

    public static class Result {
        public final List<ProbeReport> reports;
        public final int totalRadios;
        public final int radiosDown;
        public final int linksActive;
        public final int linksStale;
        public Result(List<ProbeReport> r, int total, int down, int active, int stale) {
            this.reports = r; this.totalRadios = total; this.radiosDown = down;
            this.linksActive = active; this.linksStale = stale;
        }
    }

    private TopologyBuilder() {}

    /** Sondagem + atualizacao in-place do estado do Project. */
    public static Result refresh(Project project) {
        List<Radio> radios = new ArrayList<>();
        for (NetworkPoint p : project.getPoints()) radios.addAll(p.getRadios());

        List<ProbeReport> reports = probeAll(radios);

        // 1) atualiza status de cada radio (UP/DOWN), logando transicoes
        int down = 0;
        for (ProbeReport r : reports) {
            RadioStatus prev = r.radio.getStatus();
            if (r.error != null) {
                r.radio.setStatus(RadioStatus.DOWN);
                r.radio.setLastError(r.error);
                down++;
                if (prev != RadioStatus.DOWN) {
                    Log.warn("Rádio CAIU: %s @ %s (%s) — %s",
                            r.radio.getName(), r.radio.getHost(), r.radio.getVendor(), r.error);
                } else {
                    Log.warn("Rádio segue down: %s @ %s — %s",
                            r.radio.getName(), r.radio.getHost(), r.error);
                }
            } else {
                r.radio.setStatus(RadioStatus.UP);
                r.radio.setLastError("");
                if (prev == RadioStatus.DOWN) {
                    Log.info("Rádio voltou: %s @ %s (%d vizinhos)",
                            r.radio.getName(), r.radio.getHost(), r.neighbors.size());
                }
            }
        }

        // 1.5) associacoes informadas a mao viram enlaces antes de tudo: elas
        //      descrevem ligacoes que existem e que a sondagem nao enxerga.
        int manuais = aplicarUplinksManuais(project);

        // 2) indice dos links existentes por par-ordenado de IDs + snapshot do estado
        Map<String, Link> byKey = new HashMap<>();
        Map<String, Boolean> prevStale = new HashMap<>();
        for (Link l : project.getLinks()) {
            String k = pairKey(l.getRadioAId(), l.getRadioBId());
            byKey.put(k, l);
            prevStale.put(k, l.isStale());
        }

        // 3) inicialmente marca todos os links existentes como stale; vao desmarcar
        //    se forem confirmados nesta rodada.
        //    Enlace planejado fica de fora: ele nao depende de confirmacao
        //    para existir, e marca-lo stale o mandaria para a remocao logo
        //    abaixo — apagando o planejamento na primeira sincronizacao.
        for (Link l : byKey.values()) {
            if (!l.isUserDefined()) l.setStale(true);
        }

        // 4) processa vizinhos descobertos
        Instant now = Instant.now();
        for (ProbeReport rep : reports) {
            if (rep.error != null) continue; // radio caido nao confirma nada
            String aId = rep.radio.getId();
            for (NeighborInfo n : rep.neighbors) {
                Optional<Radio> matched = project.findRadioByMac(n.mac);
                if (matched.isEmpty()) continue;
                String bId = matched.get().getId();
                if (aId.equals(bId)) continue;

                String key = pairKey(aId, bId);
                Link link = byKey.computeIfAbsent(key, k -> {
                    Link nl = new Link(
                            aId.compareTo(bId) < 0 ? aId : bId,
                            aId.compareTo(bId) < 0 ? bId : aId,
                            0);
                    project.getLinks().add(nl);
                    return nl;
                });
                if (link.isUserDefined()) {
                    // O que era plano ou palpite agora existe no ar. Daqui em
                    // diante e um enlace comum, com sinal medido e sujeito a
                    // remocao se cair.
                    Log.info("Enlace %s confirmado pelos radios: %s <-> %s",
                            link.getOrigin(),
                            nomeDe(project, link.getRadioAId()), nomeDe(project, link.getRadioBId()));
                    link.setOrigin(LinkOrigin.DISCOVERED);
                }
                link.setStale(false);
                link.setLastSeen(now);
                boolean aIsFirst = link.getRadioAId().equals(aId);
                if (aIsFirst) {
                    link.setSignalDbm(n.signalDbm);
                    link.setTxMbps(n.txMbps);
                    link.setRxMbps(n.rxMbps);

                    // throughput por delta de bytes (precisa de 2+ amostras)
                    if (n.txBytes > 0 || n.rxBytes > 0) {
                        if (link.getLastBytesAt() != null) {
                            long ms = now.toEpochMilli() - link.getLastBytesAt().toEpochMilli();
                            long dTx = n.txBytes - link.getLastTxBytes();
                            long dRx = n.rxBytes - link.getLastRxBytes();
                            if (ms >= 500 && dTx >= 0 && dRx >= 0) {
                                link.setTxBps(dTx * 8_000L / ms);
                                link.setRxBps(dRx * 8_000L / ms);
                            }
                            // se dTx ou dRx for negativo (reboot do radio), zera nesta rodada
                            if (dTx < 0 || dRx < 0) {
                                link.setTxBps(0);
                                link.setRxBps(0);
                            }
                        }
                        link.setLastTxBytes(n.txBytes);
                        link.setLastRxBytes(n.rxBytes);
                        link.setLastBytesAt(now);
                    }
                } else {
                    link.setSignalDbmReverse(n.signalDbm);
                }
            }
        }

        // Enlaces não confirmados nesta rodada são REMOVIDOS — útil para
        // acompanhar em tempo real estação migrando entre APs (a linha velha
        // some na mesma rodada em que a nova aparece).
        int active = 0, removed = 0, planned = 0;
        java.util.Iterator<Link> it = project.getLinks().iterator();
        while (it.hasNext()) {
            Link l = it.next();
            String aName = project.findRadioById(l.getRadioAId()).map(Radio::getName).orElse(l.getRadioAId());
            String bName = project.findRadioById(l.getRadioBId()).map(Radio::getName).orElse(l.getRadioBId());
            if (l.isUserDefined()) {
                planned++;
                continue;
            }
            if (l.isStale()) {
                Log.warn("Link removido: %s <-> %s (último sinal %d dBm)",
                        aName, bName, l.getDisplaySignalDbm());
                it.remove();
                removed++;
            } else {
                String k = pairKey(l.getRadioAId(), l.getRadioBId());
                if (prevStale.get(k) == null) {
                    Log.info("Link descoberto: %s <-> %s (%d dBm)",
                            aName, bName, l.getDisplaySignalDbm());
                }
                active++;
            }
        }
        int routersDown = probeRouters(project);
        int routers = 0;
        for (NetworkPoint p : project.getPoints()) routers += p.getRouters().size();

        Log.info("Sincronização: %d rádios (%d down), %d enlaces ativos, %d removidos%s%s",
                radios.size(), down, active, removed,
                planned == 0 ? "" : ", " + planned + " definido(s) pelo usuario preservado(s)",
                routers == 0 ? "" : String.format(", %d roteador(es) (%d down)", routers, routersDown));
        return new Result(reports, radios.size(), down, active, removed);
    }

    /**
     * Sincroniza os enlaces MANUAL com o campo de associacao dos radios.
     *
     * O campo e a fonte da verdade, e nao so uma semente: preencher cria o
     * enlace, limpar remove. Sem a segunda metade, um enlace criado por
     * engano ficaria no mapa para sempre — nao ha outro lugar na interface de
     * onde apaga-lo, ja que a tela de enlaces planejados so lista os
     * planejados.
     *
     * Enlace ja descoberto do mesmo par nao e tocado: o que os radios dizem
     * vale mais que o que alguem digitou, e sobrescrever apagaria o sinal
     * medido.
     *
     * @return quantos enlaces manuais existem depois desta passada
     */
    private static int aplicarUplinksManuais(Project project) {
        java.util.Set<String> afirmados = new java.util.HashSet<>();
        for (NetworkPoint p : project.getPoints()) {
            for (Radio r : p.getRadios()) {
                if (!r.hasManualUplink()) continue;
                String outro = r.getUplinkRadioId();
                if (outro.equals(r.getId())) continue;
                if (project.findRadioById(outro).isEmpty()) continue;
                afirmados.add(pairKey(r.getId(), outro));

                Link existente = achar(project, r.getId(), outro);
                if (existente == null) {
                    String a = r.getId(), b = outro;
                    Link l = new Link(a.compareTo(b) < 0 ? a : b,
                                      a.compareTo(b) < 0 ? b : a, 0);
                    l.setOrigin(LinkOrigin.MANUAL);
                    l.setStale(false);
                    project.getLinks().add(l);
                    Log.info("Associacao informada a mao: %s -> %s",
                            nomeDe(project, r.getId()), nomeDe(project, outro));
                } else if (existente.isManual()) {
                    existente.setStale(false);
                }
            }
        }

        // Enlace manual que nenhum radio afirma mais deixou de existir.
        int n = 0;
        java.util.Iterator<Link> it = project.getLinks().iterator();
        while (it.hasNext()) {
            Link l = it.next();
            if (!l.isManual()) continue;
            if (afirmados.contains(pairKey(l.getRadioAId(), l.getRadioBId()))) { n++; continue; }
            Log.info("Associacao removida: %s <-> %s (ninguem mais informa este AP)",
                    nomeDe(project, l.getRadioAId()), nomeDe(project, l.getRadioBId()));
            it.remove();
        }
        return n;
    }

    private static Link achar(Project project, String idA, String idB) {
        for (Link l : project.getLinks()) {
            boolean par = (idA.equals(l.getRadioAId()) && idB.equals(l.getRadioBId()))
                       || (idB.equals(l.getRadioAId()) && idA.equals(l.getRadioBId()));
            if (par) return l;
        }
        return null;
    }

    /**
     * Sonda os roteadores do projeto so para saber se respondem.
     *
     * Interfaces e trafego nao entram aqui: sao leituras caras e so
     * interessam quando alguem esta olhando. Ver
     * {@code ui.TrafficMonitorDialog}.
     */
    private static int probeRouters(Project project) {
        int fora = 0;
        for (NetworkPoint p : project.getPoints()) {
            for (Router rt : p.getRouters()) {
                if (!rt.isMonitored()) { rt.setStatus(RadioStatus.UNKNOWN); continue; }
                RadioStatus antes = rt.getStatus();
                try {
                    RouterPing.check(rt);
                    rt.setStatus(RadioStatus.UP);
                    rt.setLastError("");
                    if (antes == RadioStatus.DOWN) {
                        Log.info("Roteador voltou: %s @ %s", rt.displayName(), rt.getHost());
                    }
                } catch (Exception ex) {
                    rt.setStatus(RadioStatus.DOWN);
                    rt.setLastError(ex.getMessage() == null ? "sem resposta" : ex.getMessage());
                    fora++;
                    if (antes != RadioStatus.DOWN) {
                        Log.warn("Roteador CAIU: %s @ %s \u2014 %s",
                                rt.displayName(), rt.getHost(), rt.getLastError());
                    }
                }
            }
        }
        return fora;
    }

    private static String nomeDe(Project project, String radioId) {
        return project.findRadioById(radioId)
                .map(Radio::getName)
                .filter(n -> !n.isBlank())
                .orElse(radioId);
    }

    private static String pairKey(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    // ------------------------ Sondagem paralela ------------------------

    /**
     * Pool reaproveitado entre rodadas.
     *
     * Antes um pool novo era criado a cada sincronização e encerrado com
     * {@code shutdown()}, que NÃO interrompe tarefas em andamento. Uma sondagem
     * podia levar até ~23 s (8 s de conexão + 15 s de socket), enquanto a
     * rodada seguinte já começava e criava mais threads. Com auto-sync de 5 s,
     * as threads se acumulavam rodada após rodada — o programa ia ficando cada
     * vez mais pesado até travar.
     */
    private static ExecutorService pool;
    private static int poolSize;

    private static synchronized ExecutorService pool() {
        int want = Math.max(1, Settings.probeParallelism());
        if (pool == null || poolSize != want) {
            if (pool != null) pool.shutdownNow();
            poolSize = want;
            pool = Executors.newFixedThreadPool(want, r -> {
                Thread t = new Thread(r, "radio-probe");
                t.setDaemon(true);
                return t;
            });
        }
        return pool;
    }

    /** Encerra o pool no fechamento do programa. */
    public static synchronized void shutdown() {
        if (pool != null) { pool.shutdownNow(); pool = null; }
    }

    /**
     * Sonda todos os rádios em paralelo, com prazo GLOBAL para a rodada.
     *
     * O prazo é da rodada inteira, não de cada rádio: esperar 10 s por um,
     * depois mais 10 s pelo seguinte, e assim por diante, fazia o tempo total
     * crescer com a quantidade de rádios inacessíveis — era isso que dava a
     * impressão de fila, mesmo com a sondagem rodando em paralelo.
     *
     * Todo rádio sai com um relatório. Antes, quem estourava o prazo era
     * simplesmente descartado: não entrava como UP nem como DOWN, e o status
     * na tela ficava congelado no valor da rodada anterior.
     */
    private static List<ProbeReport> probeAll(List<Radio> radios) {
        if (radios.isEmpty()) return List.of();

        ExecutorService ex = pool();
        Map<Radio, Future<ProbeReport>> futures = new LinkedHashMap<>();
        for (Radio r : radios) {
            futures.put(r, ex.submit(() -> {
                try {
                    return new ProbeReport(r, RadioProbe.forRadio(r).probe(r), null);
                } catch (Exception e) {
                    return new ProbeReport(r, List.of(), describe(e));
                }
            }));
        }

        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(Math.max(1, Settings.probeTimeoutSec()));

        List<ProbeReport> reports = new ArrayList<>(radios.size());
        for (Map.Entry<Radio, Future<ProbeReport>> e : futures.entrySet()) {
            long left = deadline - System.nanoTime();
            Future<ProbeReport> f = e.getValue();
            try {
                reports.add(left <= 0
                        ? timedOut(e.getKey())
                        : f.get(left, TimeUnit.NANOSECONDS));
            } catch (TimeoutException te) {
                f.cancel(true);
                reports.add(timedOut(e.getKey()));
            } catch (Exception other) {
                f.cancel(true);
                reports.add(new ProbeReport(e.getKey(), List.of(), describe(other)));
            }
        }
        return reports;
    }

    private static ProbeReport timedOut(Radio r) {
        return new ProbeReport(r, List.of(), "tempo esgotado na sondagem");
    }

    /** Exceção sem mensagem ainda precisa dizer alguma coisa ao usuário. */
    private static String describe(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
    }
}
