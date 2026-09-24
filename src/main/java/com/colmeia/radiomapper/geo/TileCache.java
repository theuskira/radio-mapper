package com.colmeia.radiomapper.geo;

import com.colmeia.radiomapper.util.Log;
import javafx.application.Platform;
import javafx.scene.image.Image;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Busca de tiles em três camadas: memória, disco e rede.
 *
 * O cache em disco não é só otimização — é o que permite trabalhar em campo
 * sem internet, revendo uma área já visitada. Ele nunca expira sozinho;
 * limpar é ação explícita do usuário ({@link #clearDisk()}).
 *
 * <h3>Boa vizinhança com os servidores</h3>
 * O pool tem apenas 4 threads e o User-Agent identifica o app, como exige a
 * política de uso do OpenStreetMap (requisições anônimas levam 403). Tiles já
 * em disco nunca são rebaixados.
 */
public final class TileCache {

    public static final TileCache INSTANCE = new TileCache();

    private static final String USER_AGENT =
            "RadioMapper/0.1.0 (mapeador WISP; contato via administrador da rede)";

    /** Tiles decodificados mantidos em RAM. 512 x 256px RGBA ~ 134 MB no pior caso. */
    private static final int MEM_CAPACITY = 512;

    private final Map<String, Image> memory = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
                    return size() > MEM_CAPACITY;
                }
            });

    /** Chaves com download em andamento, para não pedir o mesmo tile duas vezes. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "tile-loader");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Path root = Paths.get(System.getProperty("user.home"), ".radio-mapper", "tiles");

    /** Falhas de rede são contadas para não inundar o log quando cai a internet. */
    private final AtomicInteger failures = new AtomicInteger();

    private TileCache() {}

    private static String key(TileSource s, int z, int x, int y) {
        return s.name() + "/" + z + "/" + x + "/" + y;
    }

    /** Tile já decodificado em RAM, ou {@code null}. Não toca em disco nem rede. */
    public Image cached(TileSource s, int z, int x, int y) {
        return memory.get(key(s, z, x, y));
    }

    /**
     * Garante o tile, buscando em disco e depois na rede se preciso.
     *
     * @param onReady chamado na thread do JavaFX quando a imagem estiver pronta.
     *                Não é chamado se o tile falhar — quem pediu simplesmente
     *                continua sem ele (o mapa mostra o fundo vazio ali).
     */
    public void request(TileSource s, int z, int x, int y, Consumer<Image> onReady) {
        if (!s.isMap()) return;
        String k = key(s, z, x, y);

        Image hit = memory.get(k);
        if (hit != null) { onReady.accept(hit); return; }

        if (!inFlight.add(k)) return;  // já tem alguém buscando

        pool.execute(() -> {
            try {
                Image img = loadFromDisk(s, z, x, y);
                if (img == null) img = downloadAndStore(s, z, x, y);
                if (img == null) return;

                memory.put(k, img);
                final Image ready = img;
                Platform.runLater(() -> onReady.accept(ready));
            } finally {
                inFlight.remove(k);
            }
        });
    }

    private Path diskPath(TileSource s, int z, int x, int y) {
        return root.resolve(s.cacheDir()).resolve(String.valueOf(z))
                   .resolve(String.valueOf(x)).resolve(y + ".png");
    }

    private Image loadFromDisk(TileSource s, int z, int x, int y) {
        Path p = diskPath(s, z, x, y);
        if (!Files.isRegularFile(p)) return null;
        try {
            byte[] bytes = Files.readAllBytes(p);
            Image img = new Image(new ByteArrayInputStream(bytes));
            if (img.isError()) {
                Files.deleteIfExists(p);   // arquivo corrompido: some com ele
                return null;
            }
            return img;
        } catch (IOException ex) {
            return null;
        }
    }

    private Image downloadAndStore(TileSource s, int z, int x, int y) {
        String url = s.url(z, x, y);
        if (url == null) return null;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 404) return null;   // buraco de cobertura: normal
            if (resp.statusCode() != 200) {
                noteFailure(url, "HTTP " + resp.statusCode());
                return null;
            }

            byte[] bytes = resp.body();
            if (bytes == null || bytes.length == 0) return null;

            Image img = new Image(new ByteArrayInputStream(bytes));
            if (img.isError()) {
                noteFailure(url, "resposta não decodificou como imagem");
                return null;
            }

            // Grava só depois de validar, para não cachear lixo.
            try {
                Path p = diskPath(s, z, x, y);
                Files.createDirectories(p.getParent());
                Files.write(p, bytes);
            } catch (IOException ioe) {
                Log.warn("Nao consegui gravar tile em disco: %s", ioe.getMessage());
            }
            return img;

        } catch (Exception ex) {
            noteFailure(url, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return null;
        }
    }

    /** Loga a primeira falha e depois só de 50 em 50, para não afogar o log offline. */
    private void noteFailure(String url, String why) {
        int n = failures.incrementAndGet();
        if (n == 1 || n % 50 == 0) {
            Log.warn("Falha ao baixar tile (%d no total): %s — %s", n, why, url);
        }
    }

    // ------------------------ Manutenção ------------------------

    /** Tamanho do cache em disco, em bytes. Percorre a árvore; use fora da thread de UI. */
    public long diskBytes() {
        if (!Files.isDirectory(root)) return 0;
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (IOException e) { return 0; }
            }).sum();
        } catch (IOException ex) {
            return 0;
        }
    }

    /** Apaga todo o cache em disco e a memória. Ação explícita do usuário. */
    public void clearDisk() {
        memory.clear();
        if (!Files.isDirectory(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
            Log.info("Cache de tiles limpo: %s", root);
        } catch (IOException ex) {
            Log.warn("Falha ao limpar cache de tiles: %s", ex.getMessage());
        }
    }

    public Path cacheRoot() { return root; }

    public void shutdown() {
        pool.shutdownNow();
    }
}
