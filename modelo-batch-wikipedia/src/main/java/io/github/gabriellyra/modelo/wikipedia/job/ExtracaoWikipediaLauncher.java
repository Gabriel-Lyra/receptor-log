package io.github.gabriellyra.modelo.wikipedia.job;

import java.time.LocalDateTime;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Ponto unico de disparo do job, usado pelo agendamento e pelo endpoint manual.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExtracaoWikipediaLauncher {

    private final JobLauncher jobLauncher;
    private final Job extracaoWikipediaJob;

    public JobExecution executar(String origem) {
        JobParameters parametros = new JobParametersBuilder()
                .addLocalDateTime("dataHoraExecucao", LocalDateTime.now())
                .addString("origem", origem, false)
                .toJobParameters();

        log.info("Disparando job {} (origem={})", extracaoWikipediaJob.getName(), origem);
        try {
            return jobLauncher.run(extracaoWikipediaJob, parametros);
        } catch (JobExecutionException e) {
            log.error("Nao foi possivel disparar o job {} (origem={})", extracaoWikipediaJob.getName(), origem, e);
            throw new IllegalStateException("Falha ao disparar o job " + extracaoWikipediaJob.getName(), e);
        }
    }
}
