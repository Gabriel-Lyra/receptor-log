package io.github.gabriellyra.modelo.wikipedia.job;

import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;

import io.github.gabriellyra.modelo.wikipedia.model.PaginaExtraida;
import io.github.gabriellyra.modelo.wikipedia.repository.PaginaExtraidaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaginaExtraidaWriter implements ItemWriter<PaginaExtraida> {

    private final PaginaExtraidaRepository repository;

    @Override
    public void write(Chunk<? extends PaginaExtraida> chunk) {
        log.debug("Gravando chunk com {} paginas", chunk.size());
        for (PaginaExtraida pagina : chunk) {
            repository.salvar(pagina);
            log.info("Pagina extraida: '{}' ({} palavras, {} categorias) {}",
                    pagina.titulo(), pagina.quantidadePalavras(), pagina.quantidadeCategorias(), pagina.url());
        }
    }
}
