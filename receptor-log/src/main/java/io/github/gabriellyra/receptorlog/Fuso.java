package io.github.gabriellyra.receptorlog;

import java.time.ZoneId;

/**
 * Fuso de tudo o que o receptor grava: o {@code @timestamp} de cada linha, a pasta {@code data=} e o inicio no nome do
 * arquivo. Horario de Brasilia e requisito. O deslocamento ({@code -03:00}) vai sempre junto, entao o instante continua
 * sem ambiguidade mesmo se o horario de verao voltar.
 */
public final class Fuso {

    public static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

    private Fuso() {
    }
}
