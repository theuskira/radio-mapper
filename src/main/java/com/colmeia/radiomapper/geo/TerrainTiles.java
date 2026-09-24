package com.colmeia.radiomapper.geo;

import com.colmeia.radiomapper.util.Log;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;

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

/**
 * Altitude vinda de tiles de terreno — o "relevo do mapa".
 *
 * <h3>Por que os mapas base não servem</h3>
 * OSM, satélite e OpenTopoMap são figuras. O OpenTopoMap até desenha curvas de
 * nível, mas são pixels: não há como ler um número de altitude deles. Já os
 * tiles Terrarium guardam a altitude CODIFICADA nos canais de cor:
 *
 * <pre>altitude = (R * 256 + G + B / 256) − 32768</pre>
 *
 * É um serviço público (AWS Open Data, herdado do Mapzen), sem chave de API —
 * mesma linha dos provedores já usados aqui. Por baixo são dados SRTM e
 * similares, com resolução real na casa de 30 m: ótimo para perfil de enlace
 * de vários quilômetros, grosseiro para obstrução de última milha.
 *
 * <h3>Assíncrono</h3>
 * A primeira consulta a uma área devolve {@code null} e dispara o download; as
 * seguintes já respondem. Para a leitura sob o cursor isso é invisível. Para
 * cálculo em lote existe {@link #prefetch}.
 */
public final class TerrainTiles implements ElevationSource {

    public static final TerrainTiles INSTANCE = new TerrainTiles();

    private static final String URL =
            "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png";

    /**
     * Zoom fixo. O dado por baixo é de ~30 m, então pedir zoom maior só
     * interpola e multiplica o número de tiles; 13 dá ~19 m/px no equador,
     * perto do limite útil da fonte.
     */
    private static final int ZOOM = 13;

    private static final int TILE = 256;
    private static final int MEM_CAPACITY = 128;

    /** Valor do Terrarium para "sem dado" (fundo do mar profundo fictício). */
    private static final double NODATA_BELOW = -11000;

    private final Map<String, Image> memory = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Image> e) {
                    return size() > MEM_CAPACITY;
                }
            });

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<String> missing = ConcurrentHashMap.newKeySet();

    private final ExecutorService pool = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "terrain-tiles");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Path root = Paths.get(System.getProperty("user.home"),
                                        ".radio-mapper", "terrain");

    private final AtomicInteger failures = new AtomicInteger();
    private volatile boolean enabled = true;

    private TerrainTiles() {}

    public void setEnabled(boolean v) { this.enabled = v; }
    public boolean isEnabled() { return enabled; }

    @Override public String sourceName() { return "Relevo do mapa (Terrarium ~30 m)"; }

    @Override public double resolutionMeters() { return 30; }

    @Override
    public Double elevationAt(double worldX, double worldY) {
        if (!enabled) return null;

        int n = 1 << ZOOM;
        double span = Mercator.tileSpan(ZOOM);
        double fx = (worldX + Mercator.MAX) / span;
        double fy = (worldY + Mercator.MAX) / span;
        int tx = (int) Math.floor(fx);
        int ty = (int) Math.floor(fy);
        if (tx < 0 || ty < 0 || tx >= n || ty >= n) return null;

        String key = ZOOM + "/" + tx + "/" + ty;
        if (missing.contains(key)) return null;

        Image img = memory.get(key);
        if (img == null) {
            request(key, tx, ty);
            return null;   // chega na próxima consulta
        }

        // Posição do pixel dentro do tile.
        int px = (int) Math.floor((fx - tx) * TILE);
        int py = (int) Math.floor((fy - ty) * TILE);
        px = Math.max(0, Math.min(TILE - 1, px));
        py = Math.max(0, Math.min(TILE - 1, py));

        PixelReader pr = img.getPixelReader();
        if (pr == null) return null;
        int argb = pr.getArgb(px, py);
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;

        double h = (r * 256.0 + g + b / 256.0) - 32768.0;
        return h <= NODATA_BELOW ? null : h;
    }

    /**
     * Garante que os tiles de uma área estejam em mãos, bloqueando até chegarem.
     * Para cálculo de perfil, onde devolver null não serve.
     *
     * @return true se tudo que a área precisa está disponível
     */
    public boolean prefetch(double minX, double minY, double maxX, double maxY, long timeoutMs) {
        if (!enabled) return false;
        double span = Mercator.tileSpan(ZOOM);
        int n = 1 << ZOOM;
        int x0 = Math.max(0, (int) Math.floor((minX + Mercator.MAX) / span));
        int x1 = Math.min(n - 1, (int) Math.floor((maxX + Mercator.MAX) / span));
        int y0 = Math.max(0, (int) Math.floor((minY + Mercator.MAX) / span));
        int y1 = Math.min(n - 1, (int) Math.floor((maxY + Mercator.MAX) / span));

        for (int tx = x0; tx <= x1; tx++) {
            for (int ty = y0; ty <= y1; ty++) {
                String key = ZOOM + "/" + tx + "/" + ty;
                if (memory.containsKey(key) || missing.contains(key)) continue;
                request(key, tx, ty);
            }
        }

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean pending = false;
            for (int tx = x0; tx <= x1 && !pending; tx++) {
                for (int ty = y0; ty <= y1; ty++) {
                    String key = ZOOM + "/" + tx + "/" + ty;
                    if (!memory.containsKey(key) && !missing.contains(key)) { pending = true; break; }
                }
            }
            if (!pending) return true;
            try { Thread.sleep(50); } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void request(String key, int tx, int ty) {
        if (!inFlight.add(key)) return;
        pool.execute(() -> {
            try {
                Image img = fromDisk(tx, ty);
                if (img == null) img = download(tx, ty);
                if (img == null) missing.add(key);
                else memory.put(key, img);
            } finally {
                inFlight.remove(key);
            }
        });
    }

    private Path diskPath(int tx, int ty) {
        return root.resolve(String.valueOf(ZOOM)).resolve(String.valueOf(tx)).resolve(ty + ".png");
    }

    private Image fromDisk(int tx, int ty) {
        Path p = diskPath(tx, ty);
        if (!Files.isRegularFile(p)) return null;
        try {
            Image img = new Image(new ByteArrayInputStream(Files.readAllBytes(p)));
            if (img.isError()) { Files.deleteIfExists(p); return null; }
            return img;
        } catch (IOException ex) {
            return null;
        }
    }

    private Image download(int tx, int ty) {
        String url = URL.replace("{z}", String.valueOf(ZOOM))
                        .replace("{x}", String.valueOf(tx))
                        .replace("{y}", String.valueOf(ty));
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "RadioMapper/0.1.0 (mapeador WISP)")
                    .timeout(Duration.ofSeconds(20))
                    .GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 404 || resp.statusCode() == 403) return null;
            if (resp.statusCode() != 200) { note(url, "HTTP " + resp.statusCode()); return null; }

            byte[] bytes = resp.body();
            if (bytes == null || bytes.length == 0) return null;
            Image img = new Image(new ByteArrayInputStream(bytes));
            if (img.isError()) { note(url, "nao decodificou"); return null; }

            try {
                Path p = diskPath(tx, ty);
                Files.createDirectories(p.getParent());
                Files.write(p, bytes);
            } catch (IOException ioe) {
                Log.warn("Nao consegui cachear tile de relevo: %s", ioe.getMessage());
            }
            return img;
        } catch (Exception ex) {
            note(url, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return null;
        }
    }

    private void note(String url, String why) {
        int n = failures.incrementAndGet();
        if (n == 1 || n % 25 == 0) {
            Log.warn("Falha ao baixar tile de relevo (%d): %s — %s", n, why, url);
        }
    }

    public Path cacheRoot() { return root; }

    public void shutdown() { pool.shutdownNow(); }
}
