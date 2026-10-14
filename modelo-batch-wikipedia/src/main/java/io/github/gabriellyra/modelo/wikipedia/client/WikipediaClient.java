package io.github.gabriellyra.modelo.wikipedia.client;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import io.github.gabriellyra.modelo.wikipedia.client.dto.PaginaWikipediaResponse;
import io.github.gabriellyra.modelo.wikipedia.client.dto.WikipediaQueryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Cliente da API do MediaWiki. Uma unica chamada traz uma pagina aleatoria do
 * namespace principal (artigos) com resumo em texto puro, metadados, categorias
 * visiveis e pageprops (usado para identificar paginas de desambiguacao).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WikipediaClient {

    private static final String API_PATH = "/w/api.php";

    private final RestClient wikipediaRestClient;

    public PaginaWikipediaResponse buscarPaginaAleatoria() {
        log.debug("Requisitando pagina aleatoria na API da Wikipedia");
        long inicio = System.nanoTime();

        WikipediaQueryResponse resposta;
        try {
            resposta = wikipediaRestClient.get()
                    .uri(uri -> uri.path(API_PATH)
                            .queryParam("action", "query")
                            .queryParam("format", "json")
                            .queryParam("formatversion", "2")
                            .queryParam("generator", "random")
                            .queryParam("grnnamespace", "0")
                            .queryParam("grnlimit", "1")
                            .queryParam("prop", "extracts|info|pageprops|categories")
                            .queryParam("exintro", "1")
                            .queryParam("explaintext", "1")
                            .queryParam("inprop", "url")
                            .queryParam("cllimit", "max")
                            .queryParam("clshow", "!hidden")
                            .build())
                    .retrieve()
                    .body(WikipediaQueryResponse.class);
        } catch (RestClientResponseException e) {
            throw new WikipediaIndisponivelException(
                    "Wikipedia respondeu HTTP " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            throw new WikipediaIndisponivelException(
                    "Falha de comunicacao com a Wikipedia: " + e.getMessage(), e);
        }

        long duracaoMs = (System.nanoTime() - inicio) / 1_000_000;

        List<PaginaWikipediaResponse> paginas = resposta == null || resposta.query() == null
                ? List.of()
                : resposta.query().pages();
        if (paginas == null || paginas.isEmpty()) {
            throw new WikipediaIndisponivelException("Wikipedia retornou resposta sem paginas");
        }

        PaginaWikipediaResponse pagina = paginas.get(0);
        log.debug("Pagina recebida em {} ms: pageId={} titulo='{}'", duracaoMs, pagina.pageid(), pagina.title());
        return pagina;
    }
}
