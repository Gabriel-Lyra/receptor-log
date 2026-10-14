package io.github.gabriellyra.receptorlog.ingestao;

import io.github.gabriellyra.receptorlog.ConfiguracaoReceptor;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificadorTokenTest {

    @Test
    void semTokenEObrigatorioNaoSobe() {
        VerificadorToken v = new VerificadorToken(config(List.of(), true));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> v.verificarNoInicio(null));
        assertTrue(e.getMessage().contains("RECEPTOR_TOKENS vazio"));
    }

    @Test
    void semTokenEOpcionalSobeAberto() {
        VerificadorToken v = new VerificadorToken(config(List.of(), false));
        v.verificarNoInicio(null);
        assertFalse(v.exigido());
    }

    @Test
    void cadaTokenPertenceAUmServico() {
        VerificadorToken v = new VerificadorToken(config(List.of(
                "pokemon:abc", " wikipedia : def ", "pokemon:abc-novo"), true));
        v.verificarNoInicio(null);
        assertEquals(Optional.of("pokemon"), v.servicoDo("Bearer abc"));
        assertEquals(Optional.of("pokemon"), v.servicoDo("bearer abc-novo"));
        assertEquals(Optional.of("wikipedia"), v.servicoDo("Bearer def"));
        assertEquals(Optional.empty(), v.servicoDo("Bearer xyz"));
        assertEquals(Optional.empty(), v.servicoDo("abc"));
        assertEquals(Optional.empty(), v.servicoDo(null));
    }

    @Test
    void entradaSemServicoEErroDeConfiguracao() {
        assertThrows(IllegalArgumentException.class, () -> new VerificadorToken(config(List.of("so-o-token"), true)));
        assertThrows(IllegalArgumentException.class, () -> new VerificadorToken(config(List.of("pokemon:"), true)));
        assertThrows(IllegalArgumentException.class, () -> new VerificadorToken(config(List.of(":abc"), true)));
    }

    private static ConfiguracaoReceptor config(List<String> tokens, boolean obrigatorio) {
        return new ConfiguracaoReceptor() {
            @Override public Buffer buffer() {
                return new Buffer() {
                    @Override public Path diretorio() { return Path.of("."); }
                    @Override public long tetoBytes() { return 1L << 30; }
                };
            }
            @Override public Segmento segmento() {
                return new Segmento() {
                    @Override public Duration idadeMaxima() { return Duration.ofMinutes(30); }
                    @Override public long tamanhoMaximoBytes() { return 1 << 20; }
                    @Override public Duration verificacao() { return Duration.ofSeconds(5); }
                };
            }
            @Override public Requisicao requisicao() { return () -> 1_000_000; }
            @Override public String instancia() { return "pod-1"; }
            @Override public Optional<List<String>> tokens() { return Optional.of(tokens); }
            @Override public boolean tokenObrigatorio() { return obrigatorio; }
        };
    }
}
