package io.github.gabriellyra.receptorlog.buffer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Leitura de {@code prontos/}, usada pelo envio ao S3 e pelo controle de espaco. */
public final class ArquivosProntos {

    public static final String EXTENSAO = ".jsonl.gz";

    private ArquivosProntos() {
    }

    /** Um segmento pronto: caminho, hora em que ficou pronto e tamanho. */
    public record Arquivo(Path caminho, FileTime pronto, long bytes) {
    }

    /**
     * Segmentos completos (ignora {@code .tmp}), do que ficou pronto ha mais tempo para o mais recente.
     * Arquivo que some durante a leitura (enviado ou descartado em paralelo) e ignorado.
     */
    public static List<Arquivo> doMaisAntigo(Path prontos) throws IOException {
        List<Arquivo> arquivos = new ArrayList<>();
        try (Stream<Path> caminhos = Files.walk(prontos)) {
            for (Path caminho : (Iterable<Path>) caminhos::iterator) {
                if (!caminho.getFileName().toString().endsWith(EXTENSAO)) {
                    continue;
                }
                try {
                    BasicFileAttributes atributos = Files.readAttributes(caminho, BasicFileAttributes.class);
                    if (atributos.isRegularFile()) {
                        arquivos.add(new Arquivo(caminho, atributos.lastModifiedTime(), atributos.size()));
                    }
                } catch (NoSuchFileException sumiu) {
                    // Enviado ou descartado entre a listagem e a leitura.
                }
            }
        } catch (NoSuchFileException semPasta) {
            return List.of();
        } catch (UncheckedIOException e) {
            if (e.getCause() instanceof NoSuchFileException) {
                // Uma pasta sumiu no meio da varredura (limpeza de pastas vazias): fica para a proxima.
                return arquivos.stream().sorted(ORDEM).toList();
            }
            throw e.getCause();
        }
        arquivos.sort(ORDEM);
        return arquivos;
    }

    /** Soma dos tamanhos de todos os arquivos abaixo da pasta (abertos, prontos e temporarios). */
    public static long tamanho(Path raiz) throws IOException {
        long total = 0;
        try (Stream<Path> caminhos = Files.walk(raiz)) {
            for (Path caminho : (Iterable<Path>) caminhos::iterator) {
                try {
                    BasicFileAttributes atributos = Files.readAttributes(caminho, BasicFileAttributes.class);
                    if (atributos.isRegularFile()) {
                        total += atributos.size();
                    }
                } catch (NoSuchFileException sumiu) {
                    // Movido ou apagado durante a varredura.
                }
            }
        } catch (NoSuchFileException semPasta) {
            return 0;
        } catch (UncheckedIOException e) {
            if (!(e.getCause() instanceof NoSuchFileException)) {
                throw e.getCause();
            }
        }
        return total;
    }

    private static final Comparator<Arquivo> ORDEM =
            Comparator.comparing(Arquivo::pronto).thenComparing(a -> a.caminho().toString());
}
