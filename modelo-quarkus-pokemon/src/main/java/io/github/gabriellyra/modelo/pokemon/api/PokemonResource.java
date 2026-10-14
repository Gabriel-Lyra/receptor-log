package io.github.gabriellyra.modelo.pokemon.api;

import java.net.URI;
import java.util.List;

import org.jboss.resteasy.reactive.RestResponse;

import io.github.gabriellyra.modelo.pokemon.api.dto.PokemonRequest;
import io.github.gabriellyra.modelo.pokemon.api.dto.PokemonResponse;
import io.github.gabriellyra.modelo.pokemon.domain.Pokemon;
import io.github.gabriellyra.modelo.pokemon.domain.TipoPokemon;
import io.github.gabriellyra.modelo.pokemon.service.PokemonService;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
@Path("/pokemons")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiredArgsConstructor
public class PokemonResource {

    private final PokemonService service;

    @POST
    public RestResponse<PokemonResponse> criar(@Valid PokemonRequest request, @Context UriInfo uriInfo) {
        log.infof("POST /pokemons nome=%s", request.nome());
        Pokemon criado = service.criar(request);
        URI location = uriInfo.getAbsolutePathBuilder().path(String.valueOf(criado.getId())).build();
        return RestResponse.ResponseBuilder.<PokemonResponse>created(location).entity(PokemonResponse.de(criado)).build();
    }

    @PUT
    @Path("/{id}")
    public PokemonResponse atualizar(@PathParam("id") Long id, @Valid PokemonRequest request) {
        log.infof("PUT /pokemons/%d", id);
        return PokemonResponse.de(service.atualizar(id, request));
    }

    @GET
    @Path("/{id}")
    public PokemonResponse buscar(@PathParam("id") Long id) {
        log.debugf("GET /pokemons/%d", id);
        return PokemonResponse.de(service.buscar(id));
    }

    @GET
    public List<PokemonResponse> listar(@QueryParam("nome") String nome, @QueryParam("tipo") TipoPokemon tipo) {
        log.debugf("GET /pokemons nome=%s tipo=%s", nome, tipo);
        return service.listar(nome, tipo).stream().map(PokemonResponse::de).toList();
    }

    @DELETE
    @Path("/{id}")
    public RestResponse<Void> excluir(@PathParam("id") Long id) {
        log.infof("DELETE /pokemons/%d", id);
        service.excluir(id);
        return RestResponse.noContent();
    }
}
