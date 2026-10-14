package io.github.gabriellyra.modelo.wikipedia.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "agendamento.extracao-wikipedia", name = "habilitado", havingValue = "true", matchIfMissing = true)
public class ExtracaoWikipediaScheduler {

    private final ExtracaoWikipediaLauncher launcher;

    /** Padrao: a cada 5 minutos. Configuravel via EXTRACAO_WIKIPEDIA_CRON. */
    @Scheduled(cron = "${agendamento.extracao-wikipedia.cron}")
    public void executar() {
        log.debug("Agendamento disparado");
        try {
            launcher.executar("agendamento");
        } catch (RuntimeException e) {
            // ja logado no launcher; nao propaga para nao derrubar a thread do scheduler
            log.debug("Execucao agendada abortada: {}", e.getMessage());
        }
    }
}
