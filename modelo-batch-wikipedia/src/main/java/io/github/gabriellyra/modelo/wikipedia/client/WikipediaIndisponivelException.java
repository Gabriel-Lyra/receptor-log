package io.github.gabriellyra.modelo.wikipedia.client;

/**
 * Falha ao obter uma pagina da Wikipedia (erro HTTP, timeout, resposta vazia).
 * E tratada como "skippable" no step: a pagina e descartada e o job segue.
 */
public class WikipediaIndisponivelException extends RuntimeException {

    public WikipediaIndisponivelException(String message) {
        super(message);
    }

    public WikipediaIndisponivelException(String message, Throwable cause) {
        super(message, cause);
    }
}
