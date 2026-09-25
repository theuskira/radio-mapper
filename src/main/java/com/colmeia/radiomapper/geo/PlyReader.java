package com.colmeia.radiomapper.geo;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Leitor de PLY (Polygon File Format) — só os vértices, que é o que interessa
 * para relevo.
 *
 * O cabeçalho é sempre texto, mesmo nos arquivos binários, e declara quantos
 * vértices existem e em que ordem e tipo vêm as propriedades. Faces são
 * ignoradas: uma malha de fotogrametria tem os mesmos vértices da nuvem, e
 * para consultar altura num ponto bastam eles.
 *
 * <h3>Streaming</h3>
 * Levantamento de drone passa fácil de 10 milhões de pontos. Guardar tudo em
 * memória estouraria; por isso os vértices são entregues um a um a um
 * {@link VertexSink}, que decide o que fazer — no nosso caso, acumular numa
 * grade de altura.
 */
public final class PlyReader {

    private PlyReader() {}

    @FunctionalInterface
    public interface VertexSink {
        void accept(double x, double y, double z);
    }

    public static class PlyException extends Exception {
        public PlyException(String msg) { super(msg); }
        public PlyException(String msg, Throwable cause) { super(msg, cause); }
    }

    /**
     * O que o cabeçalho diz antes de lermos um único ponto.
     *
     * Os comentários entram aqui porque são o único lugar onde um PLY pode
     * declarar em que sistema de coordenadas ele está — o formato não tem
     * campo para isso, e quem gera o arquivo costuma escrever a informação
     * como comentário. Ver {@link PlyGeoref}.
     */
    public record Header(long vertexCount, String format, List<String> properties,
                         List<String> comments, Map<String, String> vertexTypes) {

        /**
         * Passo mínimo que o tipo declarado consegue representar na magnitude
         * informada, nas unidades do próprio arquivo.
         *
         * Coordenada guardada em {@code float} tem 24 bits de mantissa: perto
         * de zero isso é fino, mas num northing UTM de 8,7 milhões o degrau
         * chega a 1 m. O arquivo não avisa — o número simplesmente chega
         * arredondado, e uma grade mais fina que o degrau só produz listras
         * vazias. Daí perguntarmos ao tipo, antes de prometer resolução.
         *
         * @param prop  nome da propriedade (x, y ou z)
         * @param valor a maior magnitude que aquela coordenada assume
         * @return o degrau em unidades do arquivo, ou 0 se o tipo não limita
         *         (double, ou propriedade ausente)
         */
        public double stepOf(String prop, double valor) {
            String t = vertexTypes == null ? null : vertexTypes.get(prop.toLowerCase());
            if (t == null) return 0;
            return switch (t) {
                case "float", "float32" -> Math.ulp((float) valor);
                case "double", "float64" -> 0;
                // Inteiro guarda metro cheio (ou a unidade que o gerador usou).
                case "int", "int32", "uint", "uint32" -> 1;
                case "short", "int16", "ushort", "uint16" -> 1;
                default -> 0;
            };
        }
    }

    private enum Format { ASCII, BINARY_LE, BINARY_BE }

    private record Prop(String name, String type, boolean isList,
                        String listCountType, String listItemType) {}

    /** Lê só o cabeçalho — barato, serve para mostrar o que tem no arquivo. */
    public static Header peek(File file) throws PlyException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
            Parsed p = parseHeader(in);
            List<String> names = new ArrayList<>();
            Map<String, String> tipos = new HashMap<>();
            for (Prop pr : p.vertexProps) {
                names.add(pr.name() + " (" + pr.type() + ")");
                tipos.put(pr.name().toLowerCase(), pr.type());
            }
            return new Header(p.vertexCount, p.format.name().toLowerCase(), names,
                    List.copyOf(p.comments), Map.copyOf(tipos));
        } catch (IOException ex) {
            throw new PlyException("Não consegui ler o arquivo: " + ex.getMessage(), ex);
        }
    }

    /**
     * Percorre os vértices entregando x, y e z.
     *
     * @param stride lê 1 de cada N vértices. Nuvem densa não precisa ser lida
     *               inteira para virar grade de relevo, e pular acelera muito.
     */
    public static long readVertices(File file, int stride, VertexSink sink) throws PlyException {
        if (stride < 1) stride = 1;
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file.toPath()), 1 << 16)) {
            Parsed p = parseHeader(raw);
            int ix = indexOf(p.vertexProps, "x");
            int iy = indexOf(p.vertexProps, "y");
            int iz = indexOf(p.vertexProps, "z");
            if (ix < 0 || iy < 0 || iz < 0) {
                throw new PlyException("O arquivo não declara propriedades x, y e z nos vértices.");
            }
            return p.format == Format.ASCII
                    ? readAscii(raw, p, ix, iy, iz, stride, sink)
                    : readBinary(raw, p, ix, iy, iz, stride, sink);
        } catch (EOFException ex) {
            throw new PlyException("O arquivo terminou antes do esperado — pode estar truncado.");
        } catch (IOException ex) {
            throw new PlyException("Falha ao ler o arquivo: " + ex.getMessage(), ex);
        }
    }

    // ------------------------ Cabeçalho ------------------------

    private static final class Parsed {
        Format format;
        long vertexCount;
        List<Prop> vertexProps = new ArrayList<>();
        /** Linhas de comentário, já sem a palavra "comment". */
        List<String> comments = new ArrayList<>();
        /** Elementos depois de vertex, com suas propriedades (para pular no binário). */
        List<long[]> ignoredCounts = new ArrayList<>();
    }

    private static Parsed parseHeader(InputStream in) throws IOException, PlyException {
        String magic = readLine(in);
        if (magic == null || !magic.trim().equalsIgnoreCase("ply")) {
            throw new PlyException("Não é um arquivo PLY (falta a linha 'ply' no início).");
        }

        Parsed p = new Parsed();
        String currentElement = null;
        String line;
        while ((line = readLine(in)) != null) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("comment") || t.startsWith("obj_info")) {
                int sp = t.indexOf(' ');
                String body = sp < 0 ? "" : t.substring(sp + 1).trim();
                if (!body.isEmpty()) p.comments.add(body);
                continue;
            }
            if (t.equals("end_header")) return p;

            String[] parts = t.split("\\s+");
            switch (parts[0]) {
                case "format" -> {
                    if (parts.length < 2) throw new PlyException("Linha 'format' incompleta.");
                    p.format = switch (parts[1]) {
                        case "ascii" -> Format.ASCII;
                        case "binary_little_endian" -> Format.BINARY_LE;
                        case "binary_big_endian" -> Format.BINARY_BE;
                        default -> throw new PlyException("Formato PLY não suportado: " + parts[1]);
                    };
                }
                case "element" -> {
                    if (parts.length < 3) throw new PlyException("Linha 'element' incompleta.");
                    currentElement = parts[1];
                    if (currentElement.equals("vertex")) p.vertexCount = Long.parseLong(parts[2]);
                }
                case "property" -> {
                    if (!"vertex".equals(currentElement)) continue;   // só vértices interessam
                    if (parts[1].equals("list")) {
                        p.vertexProps.add(new Prop(parts[4], "list", true, parts[2], parts[3]));
                    } else {
                        p.vertexProps.add(new Prop(parts[2], parts[1], false, null, null));
                    }
                }
                default -> { /* linha desconhecida: ignora */ }
            }
        }
        throw new PlyException("Cabeçalho sem 'end_header'.");
    }

    /**
     * Lê uma linha byte a byte.
     *
     * Não dá para usar BufferedReader: ele leria adiante e comeria o começo
     * dos dados binários que vêm logo depois do cabeçalho.
     */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return (c == -1 && sb.length() == 0) ? null : sb.toString();
    }

    private static int indexOf(List<Prop> props, String name) {
        for (int i = 0; i < props.size(); i++) {
            if (props.get(i).name().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    // ------------------------ Corpo ------------------------

    private static long readAscii(InputStream in, Parsed p, int ix, int iy, int iz,
                                  int stride, VertexSink sink) throws IOException, PlyException {
        long used = 0;
        for (long i = 0; i < p.vertexCount; i++) {
            String line = readLine(in);
            if (line == null) break;
            if (i % stride != 0) continue;
            String[] f = line.trim().split("\\s+");
            int need = Math.max(ix, Math.max(iy, iz));
            if (f.length <= need) continue;
            try {
                sink.accept(Double.parseDouble(f[ix]), Double.parseDouble(f[iy]),
                            Double.parseDouble(f[iz]));
                used++;
            } catch (NumberFormatException ignored) {
                // linha corrompida: pula o ponto em vez de abortar o arquivo
            }
        }
        return used;
    }

    private static long readBinary(InputStream in, Parsed p, int ix, int iy, int iz,
                                   int stride, VertexSink sink) throws IOException, PlyException {
        ByteOrder order = p.format == Format.BINARY_LE ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        DataInputStream din = new DataInputStream(in);

        // Tamanho fixo por vértice só existe se não houver propriedade de lista.
        boolean fixed = p.vertexProps.stream().noneMatch(Prop::isList);
        int recordSize = 0;
        int[] offsets = new int[p.vertexProps.size()];
        if (fixed) {
            for (int i = 0; i < p.vertexProps.size(); i++) {
                offsets[i] = recordSize;
                recordSize += sizeOf(p.vertexProps.get(i).type());
            }
        }

        long used = 0;
        byte[] buf = fixed ? new byte[recordSize] : null;
        for (long i = 0; i < p.vertexCount; i++) {
            if (fixed) {
                din.readFully(buf);
                if (i % stride != 0) continue;
                ByteBuffer bb = ByteBuffer.wrap(buf).order(order);
                sink.accept(
                        valueAt(bb, offsets[ix], p.vertexProps.get(ix).type()),
                        valueAt(bb, offsets[iy], p.vertexProps.get(iy).type()),
                        valueAt(bb, offsets[iz], p.vertexProps.get(iz).type()));
                used++;
            } else {
                // Com lista no vértice (raro) a leitura é campo a campo.
                double x = 0, y = 0, z = 0;
                for (int k = 0; k < p.vertexProps.size(); k++) {
                    Prop pr = p.vertexProps.get(k);
                    if (pr.isList()) {
                        long n = (long) readScalar(din, pr.listCountType(), order);
                        for (long q = 0; q < n; q++) readScalar(din, pr.listItemType(), order);
                        continue;
                    }
                    double v = readScalar(din, pr.type(), order);
                    if (k == ix) x = v; else if (k == iy) y = v; else if (k == iz) z = v;
                }
                if (i % stride != 0) continue;
                sink.accept(x, y, z);
                used++;
            }
        }
        return used;
    }

    private static int sizeOf(String type) throws PlyException {
        return switch (type) {
            case "char", "int8", "uchar", "uint8" -> 1;
            case "short", "int16", "ushort", "uint16" -> 2;
            case "int", "int32", "uint", "uint32", "float", "float32" -> 4;
            case "double", "float64" -> 8;
            default -> throw new PlyException("Tipo de propriedade PLY desconhecido: " + type);
        };
    }

    private static double valueAt(ByteBuffer bb, int offset, String type) throws PlyException {
        return switch (type) {
            case "char", "int8" -> bb.get(offset);
            case "uchar", "uint8" -> bb.get(offset) & 0xFF;
            case "short", "int16" -> bb.getShort(offset);
            case "ushort", "uint16" -> bb.getShort(offset) & 0xFFFF;
            case "int", "int32" -> bb.getInt(offset);
            case "uint", "uint32" -> bb.getInt(offset) & 0xFFFFFFFFL;
            case "float", "float32" -> bb.getFloat(offset);
            case "double", "float64" -> bb.getDouble(offset);
            default -> throw new PlyException("Tipo de propriedade PLY desconhecido: " + type);
        };
    }

    private static double readScalar(DataInputStream in, String type, ByteOrder order)
            throws IOException, PlyException {
        int n = sizeOf(type);
        byte[] b = new byte[n];
        in.readFully(b);
        ByteBuffer bb = ByteBuffer.wrap(b).order(order);
        return valueAt(bb, 0, type);
    }
}
