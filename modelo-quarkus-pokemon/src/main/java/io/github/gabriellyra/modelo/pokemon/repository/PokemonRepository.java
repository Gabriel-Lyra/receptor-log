package io.github.gabriellyra.modelo.pokemon.repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.gabriellyra.modelo.pokemon.domain.Pokemon;
import io.github.gabriellyra.modelo.pokemon.domain.TipoPokemon;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Todas as consultas de negocio consideram apenas pokemons ativos (nao excluidos logicamente).
 */
@ApplicationScoped
public class PokemonRepository implements PanacheRepository<Pokemon> {

    public Optional<Pokemon> buscarAtivoPorId(Long id) {
        return find("id = ?1 and ativo = true", id).firstResultOptional();
    }

    public List<Pokemon> listarAtivos(String nome, TipoPokemon tipo) {
        StringBuilder query = new StringBuilder("ativo = true");
        Map<String, Object> parametros = new HashMap<>();

        if (nome != null && !nome.isBlank()) {
            query.append(" and lower(nome) like :nome");
            parametros.put("nome", "%" + nome.trim().toLowerCase() + "%");
        }
        if (tipo != null) {
            query.append(" and (tipoPrimario = :tipo or tipoSecundario = :tipo)");
            parametros.put("tipo", tipo);
        }
        return list(query.toString(), Sort.by("numeroPokedex"), parametros);
    }

    public boolean existeAtivoComNumero(Integer numeroPokedex, Long idIgnorado) {
        if (idIgnorado == null) {
            return count("numeroPokedex = ?1 and ativo = true", numeroPokedex) > 0;
        }
        return count("numeroPokedex = ?1 and ativo = true and id <> ?2", numeroPokedex, idIgnorado) > 0;
    }
}
