package io.github.gabriellyra.modelo.pokemon.exception;

public class PokemonNaoEncontradoException extends RuntimeException {

    public PokemonNaoEncontradoException(Long id) {
        super("Pokemon " + id + " nao encontrado");
    }
}
