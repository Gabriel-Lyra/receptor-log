package io.github.gabriellyra.receptorlog.buffer;

import io.github.gabriellyra.receptorlog.ConfiguracaoReceptor;
import io.github.gabriellyra.receptorlog.operacao.Contadores;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControleEspacoTest {

    private static final long TETO = 10_000;

    @TempDir
    Path raiz;

    private GravadorSegmentosTest.RelogioAjustavel relogio;
    private GravadorSegmentos gravador;
    private Contadores contadores;
    private ControleEspaco espaco;

    @BeforeEach
    void preparar() throws IOException {
        relogio = new GravadorSegmentosTest.RelogioAjustavel(Instant.parse("2026-10-14T12:00:00Z"));
        ConfiguracaoReceptor base = GravadorSegmentosTest.configuracao(raiz, 1_000_000);
        ConfiguracaoReceptor config = new ConfiguracaoReceptor() {
            @Override public Buffer buffer() {
                return new Buffer() {
                    @Override public Path diretorio() { return raiz; }
                    @Override public long tetoBytes() { return TETO; }
                };
            }
            @Override public Segmento segmento() { return base.segmento(); }
            @Override public Requisicao requisicao() { return base.requisicao(); }
            @Override public String instancia() { return base.instancia(); }
            @Override public Optional<List<String>> tokens() { return Optional.empty(); }
            @Override public boolean tokenObrigatorio() { return false; }
        };
        gravador = new GravadorSegmentos(config, relogio);
        gravador.iniciar(null);
        contadores = new Contadores();
        espaco = new ControleEspaco(config, gravador, contadores, relogio);
    }

    @Test
    void abaixoDoTetoNaoDescartaNada() throws IOException {
        Path a = pronto("servico=a/data=2026-10-14/p-1.jsonl.gz", 4_000, "2026-10-14T11:00:00Z");

        assertTrue(espaco.liberar());
        assertTrue(Files.exists(a));
        assertEquals(0, contadores.fecharPeriodo().arquivosDescartados());
    }

    @Test
    void noTetoDescartaOsMaisAntigosAte90PorCento() throws IOException {
        Path velho = pronto("servico=a/data=2026-10-14/p-1.jsonl.gz", 3_000, "2026-10-14T09:00:00Z");
        Path meio = pronto("servico=b/data=2026-10-14/p-2.jsonl.gz", 3_000, "2026-10-14T10:00:00Z");
        Path novo = pronto("servico=a/data=2026-10-14/p-3.jsonl.gz", 3_000, "2026-10-14T11:00:00Z");
        Path novissimo = pronto("servico=a/data=2026-10-14/p-4.jsonl.gz", 2_000, "2026-10-14T11:30:00Z");
        // 11.000 bytes, teto 10.000, alvo 9.000: descartar so o mais velho (3.000) ja basta.

        assertTrue(espaco.liberar());

        assertFalse(Files.exists(velho));
        assertTrue(Files.exists(meio));
        assertTrue(Files.exists(novo));
        assertTrue(Files.exists(novissimo));
        Contadores.Periodo p = contadores.fecharPeriodo();
        assertEquals(1, p.arquivosDescartados());
        assertEquals(3_000, p.bytesDescartados());
        assertTrue(p.houvePerda());
    }

    @Test
    void soComSegmentosAbertosNoTetoRecusa() throws IOException {
        Path aberto = raiz.resolve("abertos/a/1-1.jsonl");
        Files.createDirectories(aberto.getParent());
        Files.write(aberto, new byte[(int) TETO]);

        assertFalse(espaco.liberar());
        assertTrue(Files.exists(aberto), "segmento aberto nunca e descartado");
    }

    @Test
    void medeDeNovoDepoisDeUmSegundo() throws IOException {
        assertTrue(espaco.liberar());
        pronto("servico=a/data=2026-10-14/p-1.jsonl.gz", 12_000, "2026-10-14T11:00:00Z");

        // Medicao em cache: ainda acha que ha espaco.
        assertTrue(espaco.liberar());
        relogio.avancar(Duration.ofSeconds(1));
        // Mediu de novo: 12.000 no teto, descarta o unico pronto e volta a ter espaco.
        assertTrue(espaco.liberar());
        assertEquals(1, contadores.fecharPeriodo().arquivosDescartados());
    }

    private Path pronto(String relativo, int bytes, String instante) throws IOException {
        Path arquivo = gravador.diretorioProntos().resolve(relativo);
        Files.createDirectories(arquivo.getParent());
        Files.write(arquivo, new byte[bytes]);
        Files.setLastModifiedTime(arquivo, FileTime.from(Instant.parse(instante)));
        return arquivo;
    }
}
