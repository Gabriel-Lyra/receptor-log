package io.github.gabriellyra.modelo.wikipedia.client.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Envelope da resposta de action=query da API do MediaWiki (formatversion=2).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WikipediaQueryResponse(Query query) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Query(List<PaginaWikipediaResponse> pages) {
    }
}
