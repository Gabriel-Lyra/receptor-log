package io.github.gabriellyra.modelo.pokemon.api.dto;

import java.math.BigDecimal;

import io.github.gabriellyra.modelo.pokemon.domain.TipoPokemon;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PokemonRequest(
        @NotNull(message = "numeroPokedex e obrigatorio")
        @Positive(message = "numeroPokedex deve ser positivo")
        Integer numeroPokedex,

        @NotBlank(message = "nome e obrigatorio")
        @Size(max = 50, message = "nome deve ter no maximo 50 caracteres")
        String nome,

        @NotNull(message = "tipoPrimario e obrigatorio")
        TipoPokemon tipoPrimario,

        TipoPokemon tipoSecundario,

        @Positive(message = "alturaMetros deve ser positiva")
        BigDecimal alturaMetros,

        @Positive(message = "pesoKg deve ser positivo")
        BigDecimal pesoKg) {
}
