package io.github.gabriellyra.receptorlog.buffer;

import io.github.gabriellyra.receptorlog.ConfiguracaoReceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GravadorSegmentosTest {

    @TempDir
    Path raiz;

    private RelogioAjustavel relogio;
    private GravadorSegmentos gravador;

    @BeforeEach
    void preparar() throws IOException {
        relogio = new RelogioAjustavel(Instant.parse("2026-10-07T14:10:00Z"));
        gravador = novoGravador(1_000_000);
        gravador.iniciar(null);
    }

    @Test
    void fechaSegmentoNoCaminhoParticionado() throws IOException {
        gravador.gravar("modelo-batch", List.of("{\"a\":1}", "{\"a\":2}"));
        gravador.gravar("modelo-batch", List.of("{\"a\":3}"));
        gravador.fecharTodos();

        List<Path> prontos = prontos();
        assertEquals(1, prontos.size());
        String relativo = gravador.diretorioProntos().relativize(prontos.get(0)).toString().replace('\\', '/');
        assertEquals("servico=modelo-batch/data=2026-10-07/20261007T141000000Z-pod-1-2.jsonl.gz", relativo);
        assertEquals("{\"a\":1}\n{\"a\":2}\n{\"a\":3}\n", gunzip(prontos.get(0)));
        assertEquals(0, contar(raiz.resolve("abertos"), true));
    }

    @Test
    void separaPorServico() throws IOException {
        gravador.gravar("a", List.of("1"));
        gravador.gravar("b", List.of("2"));
        gravador.fecharTodos();

        assertEquals(2, prontos().size());
    }

    @Test
    void rotacionaPorIdade() throws IOException {
        gravador.gravar("svc", List.of("1"));
        relogio.avancar(Duration.ofSeconds(60));
        gravador.gravar("svc", List.of("2"));
        gravador.fecharTodos();

        assertEquals(2, prontos().size());
    }

    @Test
    void agendadorFechaSegmentoParadoSemLogNovo() throws IOException {
        gravador.gravar("svc", List.of("1"));
        gravador.fecharVencidos();
        assertEquals(0, prontos().size(), "ainda nao venceu");

        relogio.avancar(Duration.ofSeconds(61));
        gravador.fecharVencidos();
        assertEquals(1, prontos().size());
    }

    @Test
    void rotacionaNaTrocaDeHora() throws IOException {
        relogio.definir(Instant.parse("2026-10-07T14:59:50Z"));
        gravador.gravar("svc", List.of("1"));
        relogio.definir(Instant.parse("2026-10-07T15:00:05Z"));
        gravador.gravar("svc", List.of("2"));
        gravador.fecharTodos();

        List<String> caminhos = prontos().stream().map(Path::toString).map(s -> s.replace('\\', '/')).toList();
        assertEquals(2, caminhos.size());
        // Cada arquivo cobre uma hora so: achar uma hora pelo prefixo do nome e exato
        assertTrue(caminhos.stream().anyMatch(c -> c.contains("/data=2026-10-07/20261007T145950000Z-")));
        assertTrue(caminhos.stream().anyMatch(c -> c.contains("/data=2026-10-07/20261007T150005000Z-")));
    }

    @Test
    void rotacionaPorTamanho() throws IOException {
        gravador = novoGravador(10);
        gravador.iniciar(null);

        gravador.gravar("svc", List.of("123456789012"));
        gravador.gravar("svc", List.of("x"));
        gravador.fecharTodos();

        assertEquals(2, prontos().size());
    }

    @Test
    void recuperaSegmentoQueFicouAbertoNaExecucaoAnterior() throws IOException {
        Path sobra = raiz.resolve("abertos/svc/1791382800000-7.jsonl");
        Files.createDirectories(sobra.getParent());
        Files.writeString(sobra, "{\"perdido\":false}\n");

        novoGravador(1_000_000).iniciar(null);

        List<Path> prontos = prontos();
        assertEquals(1, prontos.size());
        assertTrue(prontos.get(0).toString().replace('\\', '/').contains("servico=svc/data=2026-10-07/20261007T14"));
        assertEquals("{\"perdido\":false}\n", gunzip(prontos.get(0)));
        assertTrue(Files.notExists(sobra));
    }

    @Test
    void recuperaGzipInterrompidoDescartandoALinhaPelaMetade() throws IOException {
        // Simula o processo morrendo no meio: gzip com sync flush, sem o final do arquivo.
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(bytes, 512, true);
        gzip.write("{\"a\":1}\n{\"a\":2}\n".getBytes(StandardCharsets.UTF_8));
        gzip.flush();
        gzip.write("{\"a\":3".getBytes(StandardCharsets.UTF_8));
        gzip.flush();
        Path sobra = raiz.resolve("abertos/svc/1791382800000-8.jsonl.gz");
        Files.createDirectories(sobra.getParent());
        Files.write(sobra, bytes.toByteArray());

        novoGravador(1_000_000).iniciar(null);

        List<Path> prontos = prontos();
        assertEquals(1, prontos.size());
        assertEquals("{\"a\":1}\n{\"a\":2}\n", gunzip(prontos.get(0)));
        assertTrue(Files.notExists(sobra));
    }

    @Test
    void segmentoAbertoJaEComprimidoELegivelAntesDeFechar() throws IOException {
        gravador.gravar("svc", List.of("{\"a\":1}"));
        Path aberto;
        try (Stream<Path> s = Files.walk(raiz.resolve("abertos"))) {
            aberto = s.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        assertTrue(aberto.toString().endsWith(".jsonl.gz"));
        assertEquals("{\"a\":1}\n", new String(GravadorSegmentos.lerGzipInterrompido(aberto), StandardCharsets.UTF_8));
    }

    @Test
    void segmentoVazioNaoEPublicado() throws IOException {
        gravador.gravar("svc", List.of());
        gravador.fecharTodos();

        assertEquals(0, prontos().size());
    }

    @Test
    void nomeDeServicoNaoEscapaDoDiretorio() {
        assertEquals("_", GravadorSegmentos.nomeSeguro(".."));
        assertEquals("_", GravadorSegmentos.nomeSeguro("   "));
        assertEquals("_.._etc_passwd", GravadorSegmentos.nomeSeguro("/../etc/passwd"));
        assertEquals("unknown_service_java", GravadorSegmentos.nomeSeguro("unknown_service:java"));
        assertEquals("modelo-batch.v2", GravadorSegmentos.nomeSeguro("modelo-batch.v2"));
        assertEquals(100, GravadorSegmentos.nomeSeguro("x".repeat(300)).length());
    }

    @Test
    void depoisDeEncerrarFechaTudoERecusaGravacao() throws IOException {
        gravador.gravar("a", List.of("1"));
        gravador.encerrar(null);

        assertEquals(1, prontos().size());
        org.junit.jupiter.api.Assertions.assertThrows(GravadorSegmentos.Encerrando.class,
                () -> gravador.gravar("a", List.of("2")));
        assertEquals(1, prontos().size());
    }

    private GravadorSegmentos novoGravador(long tamanhoMaximo) {
        return new GravadorSegmentos(configuracao(raiz, tamanhoMaximo), relogio);
    }

    private List<Path> prontos() throws IOException {
        try (Stream<Path> s = Files.walk(gravador.diretorioProntos())) {
            return s.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static long contar(Path diretorio, boolean soArquivos) throws IOException {
        try (Stream<Path> s = Files.walk(diretorio)) {
            return s.filter(p -> !soArquivos || Files.isRegularFile(p)).count();
        }
    }

    private static String gunzip(Path arquivo) throws IOException {
        try (InputStream entrada = new GZIPInputStream(Files.newInputStream(arquivo))) {
            return new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static ConfiguracaoReceptor configuracao(Path raiz, long tamanhoMaximo) {
        return new ConfiguracaoReceptor() {
            @Override
            public Buffer buffer() {
                return new Buffer() {
                    @Override
                    public Path diretorio() {
                        return raiz;
                    }

                    @Override
                    public long tetoBytes() {
                        return 1L << 30;
                    }
                };
            }

            @Override
            public java.util.Optional<List<String>> tokens() {
                return java.util.Optional.empty();
            }

            @Override
            public boolean tokenObrigatorio() {
                return false;
            }

            @Override
            public Segmento segmento() {
                return new Segmento() {
                    @Override
                    public Duration idadeMaxima() {
                        return Duration.ofSeconds(60);
                    }

                    @Override
                    public long tamanhoMaximoBytes() {
                        return tamanhoMaximo;
                    }

                    @Override
                    public Duration verificacao() {
                        return Duration.ofSeconds(5);
                    }
                };
            }

            @Override
            public Requisicao requisicao() {
                return () -> 1_000_000;
            }

            @Override
            public String instancia() {
                return "pod-1";
            }
        };
    }

    static final class RelogioAjustavel extends Clock {
        private Instant agora;

        RelogioAjustavel(Instant agora) {
            this.agora = agora;
        }

        void avancar(Duration d) {
            agora = agora.plus(d);
        }

        void definir(Instant instante) {
            agora = instante;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return agora;
        }
    }
}
