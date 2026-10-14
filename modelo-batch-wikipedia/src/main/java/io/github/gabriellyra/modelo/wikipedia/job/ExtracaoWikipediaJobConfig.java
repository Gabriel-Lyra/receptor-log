package io.github.gabriellyra.modelo.wikipedia.job;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import io.github.gabriellyra.modelo.wikipedia.client.WikipediaClient;
import io.github.gabriellyra.modelo.wikipedia.client.WikipediaIndisponivelException;
import io.github.gabriellyra.modelo.wikipedia.client.dto.PaginaWikipediaResponse;
import io.github.gabriellyra.modelo.wikipedia.config.WikipediaProperties;
import io.github.gabriellyra.modelo.wikipedia.model.PaginaExtraida;

/**
 * Job: extracaoWikipediaJob
 *   Step: extrairPaginasStep (chunk)
 *     reader    -> PaginaAleatoriaReader    (API do MediaWiki, N paginas por execucao)
 *     processor -> PaginaWikipediaProcessor (filtra desambiguacao, normaliza e extrai dados)
 *     writer    -> PaginaExtraidaWriter     (grava em pagina_wikipedia no H2)
 */
@Configuration
public class ExtracaoWikipediaJobConfig {

    public static final String NOME_JOB = "extracaoWikipediaJob";
    public static final String NOME_STEP = "extrairPaginasStep";

    @Bean
    Job extracaoWikipediaJob(JobRepository jobRepository,
                             Step extrairPaginasStep,
                             ExtracaoWikipediaJobListener jobListener) {
        return new JobBuilder(NOME_JOB, jobRepository)
                .listener(jobListener)
                .start(extrairPaginasStep)
                .build();
    }

    @Bean
    Step extrairPaginasStep(JobRepository jobRepository,
                            PlatformTransactionManager transactionManager,
                            PaginaAleatoriaReader paginaAleatoriaReader,
                            PaginaWikipediaProcessor paginaWikipediaProcessor,
                            PaginaExtraidaWriter paginaExtraidaWriter,
                            PaginaPuladaListener paginaPuladaListener,
                            WikipediaProperties properties) {
        return new StepBuilder(NOME_STEP, jobRepository)
                .<PaginaWikipediaResponse, PaginaExtraida>chunk(properties.tamanhoChunk(), transactionManager)
                .reader(paginaAleatoriaReader)
                .processor(paginaWikipediaProcessor)
                .writer(paginaExtraidaWriter)
                .faultTolerant()
                .skip(WikipediaIndisponivelException.class)
                .skipLimit(properties.limiteFalhas())
                .listener(paginaPuladaListener)
                .build();
    }

    @Bean
    @StepScope
    PaginaAleatoriaReader paginaAleatoriaReader(WikipediaClient wikipediaClient, WikipediaProperties properties) {
        return new PaginaAleatoriaReader(wikipediaClient, properties.paginasPorExecucao(),
                properties.intervaloEntreRequisicoes());
    }

    @Bean
    @StepScope
    PaginaWikipediaProcessor paginaWikipediaProcessor(WikipediaProperties properties,
                                                      @Value("#{stepExecution.jobExecutionId}") Long idExecucaoJob) {
        return new PaginaWikipediaProcessor(properties.tamanhoMaximoResumo(), idExecucaoJob);
    }
}
