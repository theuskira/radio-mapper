package com.colmeia.radiomapper.util;

import java.util.prefs.Preferences;

/**
 * Preferências persistentes do app (por usuário do SO). Guarda só o que faz
 * sentido sobreviver entre execuções — coisas de projeto seguem no .rmap.
 *
 * Os padrões e os limites ficam expostos como constantes porque a tela de
 * configurações (SettingsDialog) precisa deles para montar os spinners e
 * para o botão "Restaurar padrões".
 */
public final class Settings {

    private static final Preferences P = Preferences.userNodeForPackage(Settings.class);

    private static final String K_LAST_PROJECT = "lastProject";
    private static final String K_AUTO_SYNC = "autoSync";
    private static final String K_AUTO_SYNC_INTERVAL = "autoSyncInterval";
    private static final String K_SIGNAL_THRESHOLD = "signalThreshold";
    private static final String K_BEAMS_VISIBLE = "beamsVisible";
    private static final String K_SURVEY_VISIBLE = "surveyVisible";
    private static final String K_SIM_QUALITY = "simBeamQuality";
    private static final String K_RESTORE_LAST_PROJECT = "restoreLastProject";
    private static final String K_DEFAULT_BASEMAP = "defaultBasemap";
    private static final String K_VIEW_LAT = "lastViewLat";
    private static final String K_VIEW_LON = "lastViewLon";
    private static final String K_VIEW_ZOOM = "lastViewZoom";
    private static final String K_GEOTIFF_AUTO = "geotiffAuto";
    private static final String K_GEOTIFF_LOCK = "geotiffLockAfter";
    private static final String K_IMAGE_OPACITY = "imageDefaultOpacity";
    private static final String K_TERRAIN_TILES = "terrainTilesEnabled";
    private static final String K_SIDE_PANEL = "sidePanelVisible";
    private static final String K_TERRAIN_QUALITY = "terrainQuality";
    private static final String K_IMAGE_QUALITY = "imageQuality";
    private static final String K_PROBE_PARALLELISM = "probeParallelism";
    private static final String K_PROBE_TIMEOUT = "probeTimeoutSec";

    // ---- padrões de fábrica ----
    public static final boolean DEF_AUTO_SYNC = false;
    public static final int     DEF_AUTO_SYNC_INTERVAL = 5;
    public static final int     DEF_SIGNAL_THRESHOLD = -65;
    public static final boolean DEF_BEAMS_VISIBLE = true;
    public static final boolean DEF_SURVEY_VISIBLE = true;
    public static final boolean DEF_SIM_QUALITY = true;
    public static final boolean DEF_RESTORE_LAST_PROJECT = true;
    public static final boolean DEF_GEOTIFF_AUTO = true;
    public static final boolean DEF_GEOTIFF_LOCK = true;
    public static final double  DEF_IMAGE_OPACITY = 0.7;

    // ---- limites aceitos ----
    public static final int MIN_INTERVAL = 1;
    public static final int MAX_INTERVAL = 3600;
    public static final int MIN_THRESHOLD = -120;
    public static final int MAX_THRESHOLD = 0;

    /**
     * Detalhe do terreno 3D.
     *
     * Media por padrao, e nao o maximo: o custo cresce ao quadrado do lado da
     * malha e da textura, e quem tem maquina para mais sobe uma vez e a
     * escolha fica guardada.
     */
    public static com.colmeia.radiomapper.ui.Terrain3DView.Qualidade terrainQuality() {
        String v = P.get(K_TERRAIN_QUALITY, "MEDIA");
        try {
            return com.colmeia.radiomapper.ui.Terrain3DView.Qualidade.valueOf(v);
        } catch (IllegalArgumentException e) {
            return com.colmeia.radiomapper.ui.Terrain3DView.Qualidade.MEDIA;
        }
    }

    public static void setTerrainQuality(com.colmeia.radiomapper.ui.Terrain3DView.Qualidade q) {
        P.put(K_TERRAIN_QUALITY, q == null ? "MEDIA" : q.name());
    }

    /**
     * Detalhe com que as imagens de fundo entram na mem\u00f3ria.
     *
     * Alta por padr\u00e3o, que \u00e9 o que o programa sempre fez. Quem
     * sobrep\u00f5e v\u00e1rias ortofotos grandes pode precisar baixar: a
     * mem\u00f3ria cresce com o quadrado do lado, e soma por imagem.
     */
    public static com.colmeia.radiomapper.io.ImageLoader.Qualidade imageQuality() {
        String v = P.get(K_IMAGE_QUALITY, "ALTA");
        try {
            return com.colmeia.radiomapper.io.ImageLoader.Qualidade.valueOf(v);
        } catch (IllegalArgumentException e) {
            return com.colmeia.radiomapper.io.ImageLoader.Qualidade.ALTA;
        }
    }

    public static void setImageQuality(com.colmeia.radiomapper.io.ImageLoader.Qualidade q) {
        P.put(K_IMAGE_QUALITY, q == null ? "ALTA" : q.name());
    }

    private Settings() {}

    public static String lastProject() { return P.get(K_LAST_PROJECT, ""); }
    public static void setLastProject(String path) { P.put(K_LAST_PROJECT, path == null ? "" : path); }

    public static boolean autoSync() { return P.getBoolean(K_AUTO_SYNC, DEF_AUTO_SYNC); }
    public static void setAutoSync(boolean v) { P.putBoolean(K_AUTO_SYNC, v); }

    public static int autoSyncIntervalSec() { return P.getInt(K_AUTO_SYNC_INTERVAL, DEF_AUTO_SYNC_INTERVAL); }
    public static void setAutoSyncIntervalSec(int v) { P.putInt(K_AUTO_SYNC_INTERVAL, clamp(v, MIN_INTERVAL, MAX_INTERVAL)); }

    /** dBm. Acima desse limite o enlace é considerado bom. */
    public static int signalThresholdDbm() { return P.getInt(K_SIGNAL_THRESHOLD, DEF_SIGNAL_THRESHOLD); }
    public static void setSignalThresholdDbm(int v) { P.putInt(K_SIGNAL_THRESHOLD, clamp(v, MIN_THRESHOLD, MAX_THRESHOLD)); }

    public static boolean beamsVisible() { return P.getBoolean(K_BEAMS_VISIBLE, DEF_BEAMS_VISIBLE); }
    public static void setBeamsVisible(boolean v) { P.putBoolean(K_BEAMS_VISIBLE, v); }

    /** Contorno da area coberta pelo levantamento .PLY. */
    public static boolean surveyVisible() { return P.getBoolean(K_SURVEY_VISIBLE, DEF_SURVEY_VISIBLE); }
    public static void setSurveyVisible(boolean v) { P.putBoolean(K_SURVEY_VISIBLE, v); }

    /** Pintar o alcance simulado por faixa de qualidade, em vez de cor unica. */
    public static boolean simBeamQuality() { return P.getBoolean(K_SIM_QUALITY, DEF_SIM_QUALITY); }
    public static void setSimBeamQuality(boolean v) { P.putBoolean(K_SIM_QUALITY, v); }

    /** Se falso, o app abre sempre com projeto vazio, ignorando lastProject. */
    public static boolean restoreLastProject() { return P.getBoolean(K_RESTORE_LAST_PROJECT, DEF_RESTORE_LAST_PROJECT); }
    public static void setRestoreLastProject(boolean v) { P.putBoolean(K_RESTORE_LAST_PROJECT, v); }

    /**
     * Mapa base aplicado a PROJETOS NOVOS.
     *
     * O mapa base de verdade mora no projeto, porque é ele que define se as
     * coordenadas dos pontos são metros de Mercator ou pixels de imagem. Isto
     * aqui é só a semente: sem isso, todo projeto novo nasceria sem mapa e a
     * escolha teria que ser refeita a cada abertura de quem ainda não salvou
     * nada. Guardado como texto para o pacote util não depender do geo.
     */
    public static String defaultBasemapName() { return P.get(K_DEFAULT_BASEMAP, ""); }
    public static void setDefaultBasemapName(String name) {
        P.put(K_DEFAULT_BASEMAP, name == null ? "" : name);
    }

    /** Última área olhada, para um projeto novo abrir na sua região e não no meio do Brasil. */
    public static double lastViewLat() { return P.getDouble(K_VIEW_LAT, -15.78); }
    public static double lastViewLon() { return P.getDouble(K_VIEW_LON, -47.93); }
    public static int lastViewZoom() { return P.getInt(K_VIEW_ZOOM, 4); }

    public static void setLastView(double lat, double lon, int zoom) {
        P.putDouble(K_VIEW_LAT, lat);
        P.putDouble(K_VIEW_LON, lon);
        P.putInt(K_VIEW_ZOOM, zoom);
    }

    /** Posicionar a imagem sozinho quando o GeoTIFF trouxer coordenadas. */
    public static boolean geotiffAuto() { return P.getBoolean(K_GEOTIFF_AUTO, DEF_GEOTIFF_AUTO); }
    public static void setGeotiffAuto(boolean v) { P.putBoolean(K_GEOTIFF_AUTO, v); }

    /** Travar a imagem logo após posicioná-la por georreferência. */
    public static boolean geotiffLockAfter() { return P.getBoolean(K_GEOTIFF_LOCK, DEF_GEOTIFF_LOCK); }
    public static void setGeotiffLockAfter(boolean v) { P.putBoolean(K_GEOTIFF_LOCK, v); }

    public static final int DEF_PROBE_PARALLELISM = 16;
    public static final int DEF_PROBE_TIMEOUT = 20;

    /** Quantos rádios são sondados ao mesmo tempo. */
    public static int probeParallelism() {
        return clamp(P.getInt(K_PROBE_PARALLELISM, DEF_PROBE_PARALLELISM), 1, 128);
    }
    public static void setProbeParallelism(int v) { P.putInt(K_PROBE_PARALLELISM, v); }

    /**
     * Prazo da rodada INTEIRA de sondagem, em segundos. Precisa ser maior que
     * o pior caso de um rádio (8 s de conexão + 15 s de socket), senão rádios
     * lentos porém vivos apareceriam como caídos.
     */
    public static int probeTimeoutSec() {
        return clamp(P.getInt(K_PROBE_TIMEOUT, DEF_PROBE_TIMEOUT), 5, 300);
    }
    public static void setProbeTimeoutSec(int v) { P.putInt(K_PROBE_TIMEOUT, v); }

    /** Painel lateral com as listas de pontos e rádios. */
    public static boolean sidePanelVisible() { return P.getBoolean(K_SIDE_PANEL, true); }
    public static void setSidePanelVisible(boolean v) { P.putBoolean(K_SIDE_PANEL, v); }

    /**
     * Usar o relevo global (tiles Terrarium) como última fonte de altitude.
     * Faz requisições de rede, por isso é desligável.
     */
    public static boolean terrainTilesEnabled() { return P.getBoolean(K_TERRAIN_TILES, true); }
    public static void setTerrainTilesEnabled(boolean v) { P.putBoolean(K_TERRAIN_TILES, v); }

    /** Opacidade inicial da imagem sobre o mapa (0..1). */
    public static double imageDefaultOpacity() {
        double v = P.getDouble(K_IMAGE_OPACITY, DEF_IMAGE_OPACITY);
        return v < 0 ? 0 : Math.min(v, 1.0);
    }
    public static void setImageDefaultOpacity(double v) { P.putDouble(K_IMAGE_OPACITY, v); }

    /**
     * Volta tudo ao padrão de fábrica. Não mexe em {@code lastProject}: esquecer
     * o último projeto é uma ação separada e explícita na tela de configurações.
     */
    public static void resetDefaults() {
        setAutoSync(DEF_AUTO_SYNC);
        setAutoSyncIntervalSec(DEF_AUTO_SYNC_INTERVAL);
        setSignalThresholdDbm(DEF_SIGNAL_THRESHOLD);
        setBeamsVisible(DEF_BEAMS_VISIBLE);
        setSurveyVisible(DEF_SURVEY_VISIBLE);
        setSimBeamQuality(DEF_SIM_QUALITY);
        setRestoreLastProject(DEF_RESTORE_LAST_PROJECT);
        setGeotiffAuto(DEF_GEOTIFF_AUTO);
        setGeotiffLockAfter(DEF_GEOTIFF_LOCK);
        setImageDefaultOpacity(DEF_IMAGE_OPACITY);
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
