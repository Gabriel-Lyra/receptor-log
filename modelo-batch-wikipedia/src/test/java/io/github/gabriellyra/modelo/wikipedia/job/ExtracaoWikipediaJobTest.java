package io.github.gabriellyra.modelo.wikipedia.job;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import io.github.gabriellyra.modelo.wikipedia.model.PaginaExtraida;
import io.github.gabriellyra.modelo.wikipedia.repository.PaginaExtraidaRepository;

@SpringBootTest
class ExtracaoWikipediaJobTest {

    @RegisterExtension
    static WireMockExtension wikipedia = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registry) {
        registry.add("wikipedia.base-url", wikipedia::baseUrl);
    }

    @Autowired
    private ExtracaoWikipediaLauncher launcher;

    @Autowired
    private PaginaExtraidaRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void limparTabela() {
        jdbcClient.sql("DELETE FROM pagina_wikipedia").update();
    }

    @Test
    void deveExtrairEGravarPaginasAleatorias() throws Exception {
        wikipedia.stubFor(get(urlPathEqualTo("/w/api.php"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json; charset=utf-8")
                        .withBody(fixture())));

        JobExecution execucao = launcher.executar("teste");

        assertThat(execucao.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        wikipedia.verify(3, getRequestedFor(urlPathEqualTo("/w/api.php")));

        List<PaginaExtraida> paginas = repository.listarMaisRecentes(10);
        assertThat(paginas).hasSize(3);
        PaginaExtraida pagina = paginas.get(0);
        assertThat(pagina.titulo()).isEqualTo("Rio Paranoá");
        assertThat(pagina.resumo()).doesNotContain("\n");
        assertThat(pagina.categorias()).isEqualTo("Rios do Distrito Federal (Brasil); Bacia do rio Paraná");
        assertThat(pagina.idExecucaoJob()).isEqualTo(execucao.getId());
        assertThat(pagina.ultimaAlteracao()).isNotNull();
    }

    @Test
    void deveFalharQuandoWikipediaEstaIndisponivelAlemDoLimiteDeFalhas() {
        wikipedia.stubFor(get(urlPathEqualTo("/w/api.php"))
                .willReturn(aResponse().withStatus(429).withBody("too many requests")));

        JobExecution execucao = launcher.executar("teste");

        assertThat(execucao.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(repository.contar()).isZero();
    }

    private String fixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/wikipedia/pagina-aleatoria.json")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
