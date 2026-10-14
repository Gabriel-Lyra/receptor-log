package io.github.gabriellyra.modelo.wikipedia.client.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Pagina retornada pela API do MediaWiki com as props extracts, info, pageprops e categories.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaginaWikipediaResponse(
        Long pageid,
        String title,
        String extract,
        String pagelanguage,
        String touched,
        Long lastrevid,
        Long length,
        String fullurl,
        List<Categoria> categories,
        Map<String, String> pageprops) {

    public boolean isDesambiguacao() {
        return pageprops != null && pageprops.containsKey("disambiguation");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Categoria(String title) {
    }
}
