package io.github.gabriellyra.modelo.pokemon.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import io.github.gabriellyra.modelo.pokemon.domain.Pokemon;
import io.github.gabriellyra.modelo.pokemon.domain.TipoPokemon;

public record PokemonResponse(
        Long id,
        Integer numeroPokedex,
        String nome,
        TipoPokemon tipoPrimario,
        TipoPokemon tipoSecundario,
        BigDecimal alturaMetros,
        BigDecimal pesoKg,
        LocalDateTime criadoEm,
        LocalDateTime atualizadoEm) {

    public static PokemonResponse de(Pokemon pokemon) {
        return new PokemonResponse(
                pokemon.getId(),
                pokemon.getNumeroPokedex(),
                pokemon.getNome(),
                pokemon.getTipoPrimario(),
                pokemon.getTipoSecundario(),
                pokemon.getAlturaMetros(),
                pokemon.getPesoKg(),
                pokemon.getCriadoEm(),
                pokemon.getAtualizadoEm());
    }
}
