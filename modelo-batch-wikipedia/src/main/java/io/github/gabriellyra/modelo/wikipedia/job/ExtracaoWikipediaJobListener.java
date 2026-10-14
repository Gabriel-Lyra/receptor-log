package io.github.gabriellyra.modelo.wikipedia.job;

import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ExtracaoWikipediaJobListener implements JobExecutionListener {

    @Override
    public void beforeJob(JobExecution jobExecution) {
        log.info("Iniciando job {} (execucao {})", jobExecution.getJobInstance().getJobName(), jobExecution.getId());
        log.debug("Parametros da execucao {}: {}", jobExecution.getId(), jobExecution.getJobParameters());
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        long lidas = 0;
        long gravadas = 0;
        long filtradas = 0;
        long puladas = 0;
        for (StepExecution step : jobExecution.getStepExecutions()) {
            lidas += step.getReadCount();
            gravadas += step.getWriteCount();
            filtradas += step.getFilterCount();
            puladas += step.getSkipCount();
            log.debug("Step {} terminou com status {} (commits={}, rollbacks={})",
                    step.getStepName(), step.getStatus(), step.getCommitCount(), step.getRollbackCount());
        }

        long duracaoMs = duracaoMs(jobExecution.getStartTime(), jobExecution.getEndTime());

        if (jobExecution.getStatus() == BatchStatus.COMPLETED) {
            log.info("Job {} (execucao {}) concluido em {} ms: lidas={} gravadas={} filtradas={} puladas={}",
                    jobExecution.getJobInstance().getJobName(), jobExecution.getId(), duracaoMs,
                    lidas, gravadas, filtradas, puladas);
        } else {
            log.error("Job {} (execucao {}) terminou com status {} em {} ms: lidas={} gravadas={} filtradas={} puladas={}",
                    jobExecution.getJobInstance().getJobName(), jobExecution.getId(), jobExecution.getStatus(),
                    duracaoMs, lidas, gravadas, filtradas, puladas);
            jobExecution.getAllFailureExceptions()
                    .forEach(e -> log.error("Causa da falha na execucao {}", jobExecution.getId(), e));
        }
    }

    private long duracaoMs(LocalDateTime inicio, LocalDateTime fim) {
        return inicio == null || fim == null ? -1 : Duration.between(inicio, fim).toMillis();
    }
}
