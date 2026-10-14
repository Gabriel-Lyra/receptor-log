package io.github.gabriellyra.modelo.pokemon.api;

import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import io.github.gabriellyra.modelo.pokemon.api.dto.ErroResponse;
import io.github.gabriellyra.modelo.pokemon.exception.NumeroPokedexDuplicadoException;
import io.github.gabriellyra.modelo.pokemon.exception.PokemonNaoEncontradoException;
import lombok.extern.jbosslog.JBossLog;

/**
 * Traduz excecoes de negocio em respostas HTTP. Erros de validacao (@Valid) ja sao
 * tratados pelo quarkus-hibernate-validator e retornam 400.
 */
@JBossLog
public class ExceptionMappers {

    @ServerExceptionMapper
    public RestResponse<ErroResponse> naoEncontrado(PokemonNaoEncontradoException e) {
        log.debug(e.getMessage());
        return RestResponse.status(RestResponse.Status.NOT_FOUND, ErroResponse.de(404, e.getMessage()));
    }

    @ServerExceptionMapper
    public RestResponse<ErroResponse> numeroDuplicado(NumeroPokedexDuplicadoException e) {
        return RestResponse.status(RestResponse.Status.CONFLICT, ErroResponse.de(409, e.getMessage()));
    }
}
