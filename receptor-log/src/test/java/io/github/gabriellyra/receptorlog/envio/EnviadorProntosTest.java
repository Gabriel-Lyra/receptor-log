package io.github.gabriellyra.receptorlog.envio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnviadorProntosTest {

    @TempDir
    Path prontos;

    private RelogioAjustavel relogio;
    private DestinoFalso destino;

    @BeforeEach
    void preparar() {
        relogio = new RelogioAjustavel(Instant.parse("2026-10-14T12:00:00Z"));
        destino = new DestinoFalso();
    }

    @Test
    void enviaDoMaisAntigoParaOMaisNovoEApagaDepoisDeConfirmar() throws IOException {
        Path novo = pronto("servico=a/data=2026-10-14/p-3.jsonl.gz", "2026-10-14T12:03:00Z");
        Path velho = pronto("servico=b/data=2026-10-14/p-1.jsonl.gz", "2026-10-14T11:59:00Z");
        Path meio = pronto("servico=a/data=2026-10-14/p-2.jsonl.gz", "2026-10-14T12:01:00Z");

        EnviadorProntos.Resultado r = enviador("").ciclo();

        assertEquals(List.of(
                "servico=b/data=2026-10-14/p-1.jsonl.gz",
                "servico=a/data=2026-10-14/p-2.jsonl.gz",
                "servico=a/data=2026-10-14/p-3.jsonl.gz"), destino.chaves);
        assertEquals(new EnviadorProntos.Resultado(3, 0, false), r);
        assertFalse(Files.exists(novo));
        assertFalse(Files.exists(velho));
        assertFalse(Files.exists(meio));
    }

    @Test
    void ignoraArquivoAindaSendoComprimido() throws IOException {
        pronto("servico=a/data=2026-10-14/p-1.jsonl.gz.tmp", "2026-10-14T12:00:00Z");
        pronto("servico=a/data=2026-10-14/p-2.jsonl.gz", "2026-10-14T12:00:00Z");

        enviador("").ciclo();

        assertEquals(List.of("servico=a/data=2026-10-14/p-2.jsonl.gz"), destino.chaves);
        assertTrue(Files.exists(prontos.resolve("servico=a/data=2026-10-14/p-1.jsonl.gz.tmp")));
    }

    @Test
    void aplicaPrefixoComOuSemBarra() throws IOException {
        Path arquivo = pronto("servico=a/data=2026-10-14/p-1.jsonl.gz", "2026-10-14T12:00:00Z");
        assertEquals("logs/servico=a/data=2026-10-14/p-1.jsonl.gz", enviador("logs").chave(arquivo));
        assertEquals("logs/servico=a/data=2026-10-14/p-1.jsonl.gz", enviador("/logs/").chave(arquivo));
        assertEquals("a/b/servico=a/data=2026-10-14/p-1.jsonl.gz", enviador("a/b").chave(arquivo));
        assertEquals("servico=a/data=2026-10-14/p-1.jsonl.gz", enviador("  ").chave(arquivo));
    }

    @Test
    void paraNoPrimeiroErroMantemOsArquivosEEsperaAntesDeTentarDeNovo() throws IOException {
        Path primeiro = pronto("servico=a/data=2026-10-14/p-1.jsonl.gz", "2026-10-14T12:00:01Z");
        Path segundo = pronto("servico=a/data=2026-10-14/p-2.jsonl.gz", "2026-10-14T12:00:02Z");
        Path terceiro = pronto("servico=a/data=2026-10-14/p-3.jsonl.gz", "2026-10-14T12:00:03Z");
        destino.falharEm.add("servico=a/data=2026-10-14/p-2.jsonl.gz");
        EnviadorProntos enviador = enviador("");

        EnviadorProntos.Resultado r = enviador.ciclo();

        assertEquals(new EnviadorProntos.Resultado(1, 2, true), r);
        assertFalse(Files.exists(primeiro));
        assertTrue(Files.exists(segundo));
        assertTrue(Files.exists(terceiro));
        assertEquals(1, enviador.falhasSeguidas());

        // Dentro da espera de 5 s: nao tenta.
        relogio.avancar(Duration.ofSeconds(4));
        destino.falharEm.clear();
        assertEquals(new EnviadorProntos.Resultado(0, 2, true), enviador.ciclo());
        assertEquals(2, destino.tentativas);

        // Passou a espera: envia o resto, na ordem, e zera as falhas.
        relogio.avancar(Duration.ofSeconds(1));
        assertEquals(new EnviadorProntos.Resultado(2, 0, false), enviador.ciclo());
        assertEquals(List.of(
                "servico=a/data=2026-10-14/p-1.jsonl.gz",
                "servico=a/data=2026-10-14/p-2.jsonl.gz",
                "servico=a/data=2026-10-14/p-3.jsonl.gz"), destino.chaves);
        assertEquals(0, enviador.falhasSeguidas());
    }

    @Test
    void esperaDobraAteOMaximoEVoltaAoInicioDepoisDeUmSucesso() throws IOException {
        pronto("servico=a/data=2026-10-14/p-1.jsonl.gz", "2026-10-14T12:00:00Z");
        destino.falharSempre = true;
        EnviadorProntos enviador = enviador("");

        List<Long> esperas = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            Instant antes = relogio.instant();
            enviador.ciclo();
            // Avanca segundo a segundo ate a proxima tentativa acontecer.
            int tentativas = destino.tentativas;
            long segundos = 0;
            while (destino.tentativas == tentativas && segundos < 1000) {
                relogio.avancar(Duration.ofSeconds(1));
                segundos++;
                enviador.ciclo();
            }
            esperas.add(Duration.between(antes, relogio.instant()).toSeconds());
            relogio.ajustar(relogio.instant());
        }
        assertEquals(List.of(5L, 10L, 20L, 40L, 60L, 60L, 60L), esperas.subList(0, 7));

        destino.falharSempre = false;
        relogio.avancar(Duration.ofSeconds(60));
        enviador.ciclo();
        assertEquals(0, enviador.falhasSeguidas());
        assertEquals(Duration.ofSeconds(5), enviador.espera(1));
    }

    @Test
    void envioFinalRespeitaOPrazoEIgnoraAEsperaDeFalhaAnterior() throws IOException {
        pronto("servico=a/data=2026-10-14/p-1.jsonl.gz", "2026-10-14T12:00:01Z");
        pronto("servico=a/data=2026-10-14/p-2.jsonl.gz", "2026-10-14T12:00:02Z");
        destino.falharSempre = true;
        EnviadorProntos enviador = enviador("");
        enviador.ciclo();
        destino.falharSempre = false;

        // Ainda dentro da espera de 5 s, mas o envio final tenta mesmo assim.
        assertEquals(new EnviadorProntos.Resultado(2, 0, false),
                enviador.enviarPendentes(relogio.instant().plusSeconds(12)));

        // Prazo ja vencido: nao envia nada.
        pronto("servico=a/data=2026-10-14/p-3.jsonl.gz", "2026-10-14T12:00:03Z");
        assertEquals(new EnviadorProntos.Resultado(0, 1, true), enviador.enviarPendentes(relogio.instant()));
    }

    @Test
    void semArquivosNaoChamaODestino() {
        assertEquals(new EnviadorProntos.Resultado(0, 0, false), enviador("").ciclo());
        assertEquals(0, destino.tentativas);
    }

    @Test
    void apagaSoPastasVaziasParadasHaMaisDe10Minutos() throws IOException {
        Path velha = Files.createDirectories(prontos.resolve("servico=a/data=2026-10-13"));
        Path recente = Files.createDirectories(prontos.resolve("servico=a/data=2026-10-14"));
        Path comArquivo = prontos.resolve("servico=b/data=2026-10-14/p.jsonl.gz.tmp");
        Files.createDirectories(comArquivo.getParent());
        Files.writeString(comArquivo, "x");
        idade(velha, "2026-10-14T11:49:00Z");
        idade(velha.getParent(), "2026-10-14T11:00:00Z");
        idade(velha.getParent().getParent(), "2026-10-14T11:00:00Z");
        idade(recente, "2026-10-14T11:55:00Z");
        idade(comArquivo.getParent(), "2026-10-14T09:00:00Z");

        enviador("").ciclo();

        assertFalse(Files.exists(velha));
        assertTrue(Files.exists(recente));
        assertTrue(Files.exists(comArquivo));
        assertTrue(Files.exists(prontos));
    }

    private EnviadorProntos enviador(String prefixo) {
        return new EnviadorProntos(prontos, destino, prefixo, relogio,
                Duration.ofSeconds(5), Duration.ofSeconds(60), Duration.ofMinutes(10), null);
    }

    private Path pronto(String relativo, String pronto) throws IOException {
        Path arquivo = prontos.resolve(relativo);
        Files.createDirectories(arquivo.getParent());
        Files.writeString(arquivo, relativo);
        idade(arquivo, pronto);
        return arquivo;
    }

    private static void idade(Path caminho, String instante) throws IOException {
        Files.setLastModifiedTime(caminho, FileTime.from(Instant.parse(instante)));
    }

    static final class DestinoFalso implements Destino {
        final List<String> chaves = new ArrayList<>();
        final Set<String> falharEm = new HashSet<>();
        boolean falharSempre;
        int tentativas;

        @Override
        public void enviar(String chave, Path arquivo) throws Exception {
            tentativas++;
            if (falharSempre || falharEm.contains(chave)) {
                throw new IOException("S3 fora do ar");
            }
            assertEquals(chave.substring(chave.indexOf("servico=")), Files.readString(arquivo).replace('\\', '/'));
            chaves.add(chave);
        }

        @Override
        public String descricao() {
            return "destino-falso";
        }
    }

    static final class RelogioAjustavel extends Clock {
        private Instant agora;

        RelogioAjustavel(Instant agora) {
            this.agora = agora;
        }

        void avancar(Duration duracao) {
            agora = agora.plus(duracao);
        }

        void ajustar(Instant instante) {
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
