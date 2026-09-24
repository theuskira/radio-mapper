package com.colmeia.radiomapper.io;

import com.colmeia.radiomapper.model.Project;
import com.colmeia.radiomapper.util.Log;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Salva o projeto sozinho, sem o usuário precisar lembrar.
 *
 * <h3>Como detecta mudança</h3>
 * Não há um ponto central por onde toda alteração passe — pontos, rádios,
 * feixes e sobreposição são editados em dezenas de lugares. Em vez de marcar
 * "sujo" em cada um deles (e esquecer de algum), este vigia compara uma
 * ASSINATURA do projeto de tempos em tempos.
 *
 * <h3>Por que a assinatura ignora campos</h3>
 * Status de rádio, sinal, taxas e {@code lastSeen} mudam a cada sondagem — com
 * auto-sync de 1 s, usar o JSON cru como assinatura faria o arquivo ser
 * regravado o tempo todo. Pior num projeto dentro do OneDrive, que subiria o
 * arquivo a cada gravação. Então a assinatura cobre só o que é estrutura:
 * pontos, cadastro dos rádios, feixes, imagem e mapa base.
 */
public final class AutoSaver {

    /** Campos que mudam a cada sondagem e não devem disparar gravação. */
    private static final Set<String> VOLATILE_FIELDS = Set.of(
            "status", "lastError",
            "signalDbm", "signalDbmReverse", "displaySignalDbm",
            "txMbps", "rxMbps", "txBps", "rxBps",
            "lastSeen", "stale");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** Arquivo usado enquanto o usuário não escolheu um lugar. */
    public static final Path WORKSPACE = Paths.get(
            System.getProperty("user.home"), ".radio-mapper", "projeto-atual.rmap");

    /** Cópia do workspace anterior, guardada antes de um "Novo projeto" apagá-lo. */
    private static final Path WORKSPACE_BACKUP = Paths.get(
            System.getProperty("user.home"), ".radio-mapper", "projeto-anterior.rmap");

    private final Supplier<Project> source;
    private final Consumer<String> onSaved;

    private File target;
    private String lastSignature;
    private Timeline timeline;

    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "auto-save");
        t.setDaemon(true);
        return t;
    });

    /**
     * @param source  devolve o projeto atual — é um Supplier porque o controller
     *                troca a instância ao abrir outro projeto
     * @param onSaved chamado na thread do JavaFX após gravar, para feedback
     */
    public AutoSaver(Supplier<Project> source, Consumer<String> onSaved) {
        this.source = source;
        this.onSaved = onSaved;
    }

    /** Onde está gravando agora. */
    public File target() { return target == null ? WORKSPACE.toFile() : target; }

    /**
     * Aponta para outro arquivo (ao abrir ou "salvar como"). Reinicia a
     * assinatura para não achar que já está tudo gravado lá.
     */
    public void setTarget(File f) {
        this.target = f;
        this.lastSignature = null;
    }

    /** Começa a vigiar. Roda na thread do JavaFX a cada 3 s. */
    public void start() {
        if (timeline != null) return;
        timeline = new Timeline(new KeyFrame(Duration.seconds(3), e -> tick()));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
    }

    public void stop() {
        if (timeline != null) { timeline.stop(); timeline = null; }
        saveNow();
        writer.shutdown();
    }

    /**
     * Guarda o workspace atual antes que um projeto novo o substitua.
     * Sem isto, "Novo projeto" apagaria em silêncio um trabalho que só existia
     * no auto-save.
     */
    public void backupWorkspaceBeforeReset() {
        if (target != null) return;                       // tem arquivo proprio: nao ha risco
        if (!Files.isRegularFile(WORKSPACE)) return;
        try {
            Files.copy(WORKSPACE, WORKSPACE_BACKUP, StandardCopyOption.REPLACE_EXISTING);
            Log.info("Workspace anterior guardado em %s", WORKSPACE_BACKUP);
        } catch (IOException ex) {
            Log.warn("Nao consegui guardar copia do workspace: %s", ex.getMessage());
        }
        lastSignature = null;
    }

    /** Verificação periódica: mudou a estrutura? Então grava. */
    private void tick() {
        Project p = source.get();
        if (p == null) return;
        try {
            String sig = signature(p);
            if (sig.equals(lastSignature)) return;
            byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(p);
            lastSignature = sig;
            writeAsync(bytes);
        } catch (Exception ex) {
            Log.warn("Auto-save falhou ao preparar os dados: %s", ex.getMessage());
        }
    }

    /** Grava agora, sem esperar o próximo ciclo (fechamento do programa). */
    public void saveNow() {
        Project p = source.get();
        if (p == null) return;
        try {
            byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(p);
            lastSignature = signature(p);
            writeSync(bytes, target());
        } catch (Exception ex) {
            Log.warn("Auto-save final falhou: %s", ex.getMessage());
        }
    }

    private void writeAsync(byte[] bytes) {
        File dest = target();
        writer.execute(() -> {
            if (writeSync(bytes, dest) && onSaved != null) {
                javafx.application.Platform.runLater(() -> onSaved.accept(dest.getName()));
            }
        });
    }

    /** Grava em temporário e move por cima: queda no meio não trunca o projeto. */
    private boolean writeSync(byte[] bytes, File dest) {
        try {
            Path path = dest.toPath();
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Path tmp = Paths.get(path.toString() + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException ex) {
            Log.warn("Auto-save nao conseguiu gravar %s: %s", dest, ex.getMessage());
            return false;
        }
    }

    // ------------------------ Assinatura ------------------------

    /** JSON do projeto sem os campos voláteis — muda só quando a estrutura muda. */
    String signature(Project p) throws IOException {
        JsonNode tree = MAPPER.valueToTree(p);
        strip(tree);
        return tree.toString();
    }

    private static void strip(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            obj.remove(VOLATILE_FIELDS);
            List<JsonNode> kids = new java.util.ArrayList<>();
            obj.elements().forEachRemaining(kids::add);
            kids.forEach(AutoSaver::strip);
        } else if (node != null && node.isArray()) {
            node.forEach(AutoSaver::strip);
        }
    }
}
