package io.github.gabriellyra.modelo.wikipedia.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.gabriellyra.modelo.wikipedia.client.dto.PaginaWikipediaResponse;
import io.github.gabriellyra.modelo.wikipedia.client.dto.PaginaWikipediaResponse.Categoria;
import io.github.gabriellyra.modelo.wikipedia.model.PaginaExtraida;

class PaginaWikipediaProcessorTest {

    private final PaginaWikipediaProcessor processor = new PaginaWikipediaProcessor(30, 7L);

    @Test
    void deveExtrairDadosDaPagina() {
        PaginaExtraida extraida = processor.process(pagina("Brasília", "Capital  federal\n do Brasil.",
                List.of(new Categoria("Categoria:Capitais do Brasil")), Map.of()));

        assertThat(extraida).isNotNull();
        assertThat(extraida.titulo()).isEqualTo("Brasília");
        assertThat(extraida.resumo()).isEqualTo("Capital federal do Brasil.");
        assertThat(extraida.quantidadePalavras()).isEqualTo(4);
        assertThat(extraida.categorias()).isEqualTo("Capitais do Brasil");
        assertThat(extraida.quantidadeCategorias()).isEqualTo(1);
        assertThat(extraida.ultimaAlteracao()).isEqualTo(Instant.parse("2026-01-01T10:00:00Z"));
        assertThat(extraida.idExecucaoJob()).isEqualTo(7L);
    }

    @Test
    void deveTruncarResumoLongoMantendoContagemDePalavrasDoTextoInteiro() {
        String texto = "palavra ".repeat(20).trim();

        PaginaExtraida extraida = processor.process(pagina("Longa", texto, null, null));

        assertThat(extraida.resumo()).hasSize(30).endsWith("...");
        assertThat(extraida.quantidadePalavras()).isEqualTo(20);
        assertThat(extraida.quantidadeCategorias()).isZero();
    }

    @Test
    void deveDescartarPaginaDeDesambiguacao() {
        assertThat(processor.process(pagina("Mercúrio", "Mercúrio pode referir-se a:", null,
                Map.of("disambiguation", "")))).isNull();
    }

    @Test
    void deveDescartarPaginaSemResumo() {
        assertThat(processor.process(pagina("Vazia", "   \n ", null, null))).isNull();
    }

    private PaginaWikipediaResponse pagina(String titulo, String resumo, List<Categoria> categorias,
                                           Map<String, String> pageprops) {
        return new PaginaWikipediaResponse(1L, titulo, resumo, "pt", "2026-01-01T10:00:00Z", 10L, 100L,
                "https://pt.wikipedia.org/wiki/" + titulo, categorias, pageprops);
    }
}
