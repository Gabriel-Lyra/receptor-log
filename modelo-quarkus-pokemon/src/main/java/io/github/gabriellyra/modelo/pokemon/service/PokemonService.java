package io.github.gabriellyra.modelo.pokemon.service;

import java.time.LocalDateTime;
import java.util.List;

import io.github.gabriellyra.modelo.pokemon.api.dto.PokemonRequest;
import io.github.gabriellyra.modelo.pokemon.domain.Pokemon;
import io.github.gabriellyra.modelo.pokemon.domain.TipoPokemon;
import io.github.gabriellyra.modelo.pokemon.exception.NumeroPokedexDuplicadoException;
import io.github.gabriellyra.modelo.pokemon.exception.PokemonNaoEncontradoException;
import io.github.gabriellyra.modelo.pokemon.repository.PokemonRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
@ApplicationScoped
@RequiredArgsConstructor
public class PokemonService {

    private final PokemonRepository repository;

    @Transactional
    public Pokemon criar(PokemonRequest request) {
        log.debugf("Criando pokemon: %s", request);
        validarNumeroDisponivel(request.numeroPokedex(), null);

        Pokemon pokemon = new Pokemon();
        aplicar(request, pokemon);
        pokemon.setAtivo(true);
        pokemon.setCriadoEm(LocalDateTime.now());
        repository.persist(pokemon);

        log.infof("Pokemon criado: id=%d numeroPokedex=%d nome=%s",
                pokemon.getId(), pokemon.getNumeroPokedex(), pokemon.getNome());
        return pokemon;
    }

    @Transactional
    public Pokemon atualizar(Long id, PokemonRequest request) {
        log.debugf("Atualizando pokemon %d com %s", id, request);
        Pokemon pokemon = buscarAtivo(id);
        validarNumeroDisponivel(request.numeroPokedex(), id);

        aplicar(request, pokemon);
        pokemon.setAtualizadoEm(LocalDateTime.now());

        log.infof("Pokemon atualizado: id=%d numeroPokedex=%d nome=%s",
                pokemon.getId(), pokemon.getNumeroPokedex(), pokemon.getNome());
        return pokemon;
    }

    public Pokemon buscar(Long id) {
        log.debugf("Buscando pokemon %d", id);
        return buscarAtivo(id);
    }

    public List<Pokemon> listar(String nome, TipoPokemon tipo) {
        List<Pokemon> pokemons = repository.listarAtivos(nome, tipo);
        log.debugf("Listagem de pokemons (nome=%s, tipo=%s) retornou %d registros", nome, tipo, pokemons.size());
        return pokemons;
    }

    /** Exclusao logica: o registro permanece na tabela com ativo=false. */
    @Transactional
    public void excluir(Long id) {
        Pokemon pokemon = buscarAtivo(id);
        pokemon.setAtivo(false);
        pokemon.setExcluidoEm(LocalDateTime.now());
        log.infof("Pokemon excluido logicamente: id=%d nome=%s", pokemon.getId(), pokemon.getNome());
    }

    private Pokemon buscarAtivo(Long id) {
        return repository.buscarAtivoPorId(id).orElseThrow(() -> {
            log.debugf("Pokemon %d nao encontrado ou excluido", id);
            return new PokemonNaoEncontradoException(id);
        });
    }

    private void validarNumeroDisponivel(Integer numeroPokedex, Long idIgnorado) {
        if (repository.existeAtivoComNumero(numeroPokedex, idIgnorado)) {
            log.warnf("Numero da Pokedex %d ja esta em uso por outro pokemon ativo", numeroPokedex);
            throw new NumeroPokedexDuplicadoException(numeroPokedex);
        }
    }

    private void aplicar(PokemonRequest request, Pokemon pokemon) {
        pokemon.setNumeroPokedex(request.numeroPokedex());
        pokemon.setNome(request.nome().trim());
        pokemon.setTipoPrimario(request.tipoPrimario());
        pokemon.setTipoSecundario(request.tipoSecundario());
        pokemon.setAlturaMetros(request.alturaMetros());
        pokemon.setPesoKg(request.pesoKg());
    }
}
