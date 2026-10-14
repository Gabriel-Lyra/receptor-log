package io.github.gabriellyra.modelo.wikipedia.model;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Dados extraidos de uma pagina da Wikipedia, prontos para persistencia.
 */
public record PaginaExtraida(
        Long id,
        Long pageId,
        String titulo,
        String url,
        String idioma,
        String resumo,
        int quantidadePalavras,
        int quantidadeCategorias,
        String categorias,
        Long tamanhoBytes,
        Instant ultimaAlteracao,
        Long idExecucaoJob,
        LocalDateTime dataExtracao) {
}
