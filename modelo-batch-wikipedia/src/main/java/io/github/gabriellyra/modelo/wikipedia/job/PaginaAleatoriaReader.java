package io.github.gabriellyra.modelo.wikipedia.job;

import java.time.Duration;

import org.springframework.batch.item.ItemReader;

import io.github.gabriellyra.modelo.wikipedia.client.WikipediaClient;
import io.github.gabriellyra.modelo.wikipedia.client.dto.PaginaWikipediaResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Le N paginas aleatorias por execucao. E @StepScope (ver ExtracaoWikipediaJobConfig),
 * entao o contador volta a zero a cada execucao do job.
 */
@Slf4j
public class PaginaAleatoriaReader implements ItemReader<PaginaWikipediaResponse> {

    private final WikipediaClient wikipediaClient;
    private final int quantidade;
    private final Duration intervalo;
    private int tentativas;

    public PaginaAleatoriaReader(WikipediaClient wikipediaClient, int quantidade, Duration intervalo) {
        this.wikipediaClient = wikipediaClient;
        this.quantidade = quantidade;
        this.intervalo = intervalo;
    }

    @Override
    public PaginaWikipediaResponse read() throws InterruptedException {
        if (tentativas >= quantidade) {
            log.debug("Leitura encerrada: {} paginas solicitadas nesta execucao", tentativas);
            return null;
        }
        if (tentativas > 0 && !intervalo.isZero()) {
            Thread.sleep(intervalo.toMillis());
        }
        tentativas++;
        log.debug("Buscando pagina aleatoria {}/{}", tentativas, quantidade);
        return wikipediaClient.buscarPaginaAleatoria();
    }
}
