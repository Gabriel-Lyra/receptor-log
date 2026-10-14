package io.github.gabriellyra.modelo.pokemon.exception;

public class NumeroPokedexDuplicadoException extends RuntimeException {

    public NumeroPokedexDuplicadoException(Integer numeroPokedex) {
        super("Ja existe um pokemon ativo com o numero da Pokedex " + numeroPokedex);
    }
}
