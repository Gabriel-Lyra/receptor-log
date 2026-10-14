package io.github.gabriellyra.receptorlog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.time.Clock;

/** Relogio injetavel, para os testes controlarem o tempo (rotacao de segmentos, particao por hora). */
public class Relogio {

    @Produces
    @ApplicationScoped
    Clock relogio() {
        return Clock.systemUTC();
    }
}
