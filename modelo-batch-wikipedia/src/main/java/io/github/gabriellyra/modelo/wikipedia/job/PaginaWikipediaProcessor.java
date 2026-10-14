package io.github.gabriellyra.modelo.wikipedia.job;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.batch.item.ItemProcessor;

import io.github.gabriellyra.modelo.wikipedia.client.dto.PaginaWikipediaResponse;
import io.github.gabriellyra.modelo.wikipedia.model.PaginaExtraida;
import lombok.extern.slf4j.Slf4j;

/**
 * Transforma a resposta bruta da API em PaginaExtraida.
 * Retorna null (item filtrado) para paginas de desambiguacao ou sem resumo.
 */
@Slf4j
public class PaginaWikipediaProcessor implements ItemProcessor<PaginaWikipediaResponse, PaginaExtraida> {

    private static final String PREFIXO_CATEGORIA = "Categoria:";
    private static final String RETICENCIAS = "...";

    private final int tamanhoMaximoResumo;
    private final Long idExecucaoJob;

    public PaginaWikipediaProcessor(int tamanhoMaximoResumo, Long idExecucaoJob) {
        this.tamanhoMaximoResumo = tamanhoMaximoResumo;
        this.idExecucaoJob = idExecucaoJob;
    }

    @Override
    public PaginaExtraida process(PaginaWikipediaResponse pagina) {
        if (pagina.isDesambiguacao()) {
            log.info("Pagina '{}' (pageId={}) descartada: pagina de desambiguacao", pagina.title(), pagina.pageid());
            return null;
        }
        String resumoNormalizado = normalizarEspacos(pagina.extract());
        if (resumoNormalizado.isEmpty()) {
            log.info("Pagina '{}' (pageId={}) descartada: sem resumo", pagina.title(), pagina.pageid());
            return null;
        }

        List<String> categorias = pagina.categories() == null ? List.of()
                : pagina.categories().stream()
                        .map(PaginaWikipediaResponse.Categoria::title)
                        .map(this::removerPrefixoCategoria)
                        .toList();

        PaginaExtraida extraida = new PaginaExtraida(
                null,
                pagina.pageid(),
                pagina.title(),
                pagina.fullurl(),
                pagina.pagelanguage(),
                truncar(resumoNormalizado),
                contarPalavras(resumoNormalizado),
                categorias.size(),
                categorias.stream().collect(Collectors.joining("; ")),
                pagina.length(),
                converterData(pagina.touched(), pagina.title()),
                idExecucaoJob,
                LocalDateTime.now());

        log.debug("Pagina processada: pageId={} titulo='{}' palavras={} categorias={} tamanhoBytes={}",
                extraida.pageId(), extraida.titulo(), extraida.quantidadePalavras(),
                extraida.quantidadeCategorias(), extraida.tamanhoBytes());
        return extraida;
    }

    private String normalizarEspacos(String texto) {
        return texto == null ? "" : texto.replaceAll("\\s+", " ").trim();
    }

    private int contarPalavras(String texto) {
        return texto.isEmpty() ? 0 : texto.split(" ").length;
    }

    private String truncar(String texto) {
        if (texto.length() <= tamanhoMaximoResumo) {
            return texto;
        }
        return texto.substring(0, tamanhoMaximoResumo - RETICENCIAS.length()).stripTrailing() + RETICENCIAS;
    }

    private String removerPrefixoCategoria(String categoria) {
        return categoria != null && categoria.startsWith(PREFIXO_CATEGORIA)
                ? categoria.substring(PREFIXO_CATEGORIA.length())
                : categoria;
    }

    private Instant converterData(String data, String titulo) {
        if (data == null || data.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(data);
        } catch (DateTimeParseException e) {
            log.warn("Data de alteracao invalida '{}' na pagina '{}'; campo sera gravado vazio", data, titulo);
            return null;
        }
    }
}
