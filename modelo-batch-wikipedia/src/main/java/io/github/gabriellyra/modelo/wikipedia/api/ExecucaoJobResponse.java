package io.github.gabriellyra.modelo.wikipedia.api;

import java.time.LocalDateTime;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;

public record ExecucaoJobResponse(
        Long idExecucao,
        String status,
        LocalDateTime inicio,
        LocalDateTime fim,
        long lidas,
        long gravadas,
        long filtradas,
        long puladas) {

    public static ExecucaoJobResponse de(JobExecution execucao) {
        long lidas = 0;
        long gravadas = 0;
        long filtradas = 0;
        long puladas = 0;
        for (StepExecution step : execucao.getStepExecutions()) {
            lidas += step.getReadCount();
            gravadas += step.getWriteCount();
            filtradas += step.getFilterCount();
            puladas += step.getSkipCount();
        }
        return new ExecucaoJobResponse(execucao.getId(), execucao.getStatus().name(),
                execucao.getStartTime(), execucao.getEndTime(), lidas, gravadas, filtradas, puladas);
    }
}
