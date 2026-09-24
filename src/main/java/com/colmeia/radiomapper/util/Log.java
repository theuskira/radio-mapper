package com.colmeia.radiomapper.util;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Log simples: escreve em ~/.radio-mapper/logs/app.log + buffer em memoria +
 * notifica listeners (a UI escuta para mostrar em tempo real).
 *
 * Thread-safe. As escritas no arquivo sao append; nao ha rotacao automatica.
 */
public final class Log {

    public enum Level { INFO, WARN, ERROR }

    private static final int MAX_BUFFER = 1000;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Object LOCK = new Object();
    private static final Deque<String> BUFFER = new ArrayDeque<>();
    private static final List<Consumer<String>> LISTENERS = new CopyOnWriteArrayList<>();
    private static PrintWriter writer;
    private static File logFile;

    private Log() {}

    public static void init() {
        try {
            File dir = new File(System.getProperty("user.home"), ".radio-mapper/logs");
            if (!dir.exists() && !dir.mkdirs()) {
                System.err.println("Nao consegui criar " + dir);
            }
            logFile = new File(dir, "app.log");
            writer = new PrintWriter(new FileWriter(logFile, true), true);
        } catch (IOException e) {
            System.err.println("Falha ao abrir arquivo de log: " + e.getMessage());
        }
        info("=== Radio Mapper iniciado ===");
    }

    public static File getLogFile() { return logFile; }

    public static void info(String fmt, Object... args)  { log(Level.INFO,  fmt, args); }
    public static void warn(String fmt, Object... args)  { log(Level.WARN,  fmt, args); }
    public static void error(String fmt, Object... args) { log(Level.ERROR, fmt, args); }

    private static void log(Level level, String fmt, Object... args) {
        String msg;
        try {
            msg = (args == null || args.length == 0) ? fmt : String.format(fmt, args);
        } catch (Exception ex) {
            msg = fmt + " [format error: " + ex.getMessage() + "]";
        }
        String line = LocalDateTime.now().format(TS) + " " + pad(level.name()) + " " + msg;
        synchronized (LOCK) {
            BUFFER.addLast(line);
            while (BUFFER.size() > MAX_BUFFER) BUFFER.removeFirst();
            if (writer != null) {
                writer.println(line);
            }
        }
        System.out.println(line);
        for (Consumer<String> l : LISTENERS) {
            try { l.accept(line); } catch (Throwable ignored) {}
        }
    }

    private static String pad(String s) {
        return s.length() >= 5 ? s : (s + "     ").substring(0, 5);
    }

    public static List<String> snapshot() {
        synchronized (LOCK) { return new ArrayList<>(BUFFER); }
    }

    public static void addListener(Consumer<String> l) { LISTENERS.add(l); }
    public static void removeListener(Consumer<String> l) { LISTENERS.remove(l); }

    public static void close() {
        info("=== Radio Mapper encerrado ===");
        synchronized (LOCK) {
            if (writer != null) { writer.close(); writer = null; }
        }
    }
}
