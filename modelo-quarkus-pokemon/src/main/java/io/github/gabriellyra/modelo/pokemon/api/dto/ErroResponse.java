package io.github.gabriellyra.modelo.pokemon.api.dto;

import java.time.LocalDateTime;

public record ErroResponse(int status, String mensagem, LocalDateTime timestamp) {

    public static ErroResponse de(int status, String mensagem) {
        return new ErroResponse(status, mensagem, LocalDateTime.now());
    }
}
