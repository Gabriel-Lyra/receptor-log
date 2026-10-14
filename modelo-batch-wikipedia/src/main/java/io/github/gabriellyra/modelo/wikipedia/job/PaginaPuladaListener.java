package io.github.gabriellyra.modelo.wikipedia.job;

import org.springframework.batch.core.SkipListener;
import org.springframework.stereotype.Component;

import io.github.gabriellyra.modelo.wikipedia.client.dto.PaginaWikipediaResponse;
import io.github.gabriellyra.modelo.wikipedia.model.PaginaExtraida;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class PaginaPuladaListener implements SkipListener<PaginaWikipediaResponse, PaginaExtraida> {

    @Override
    public void onSkipInRead(Throwable t) {
        log.warn("Leitura de pagina ignorada: {}", t.getMessage());
    }

    @Override
    public void onSkipInProcess(PaginaWikipediaResponse item, Throwable t) {
        log.warn("Processamento da pagina '{}' ignorado: {}", item.title(), t.getMessage());
    }

    @Override
    public void onSkipInWrite(PaginaExtraida item, Throwable t) {
        log.warn("Gravacao da pagina '{}' ignorada: {}", item.titulo(), t.getMessage());
    }
}
