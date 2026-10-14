package io.github.gabriellyra.modelo.wikipedia.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Habilita o agendamento apenas quando agendamento.extracao-wikipedia.habilitado=true
 * (desligado nos testes para o job nao disparar sozinho).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "agendamento.extracao-wikipedia", name = "habilitado", havingValue = "true", matchIfMissing = true)
public class AgendamentoConfig {
}
