package io.github.gabriellyra.modelo.wikipedia.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracoes de acesso a Wikipedia e de dimensionamento do job de extracao.
 *
 * @param baseUrl                    URL base da Wikipedia (ex.: https://pt.wikipedia.org)
 * @param userAgent                  User-Agent enviado nas requisicoes (exigido pela politica da Wikimedia)
 * @param timeout                    timeout de conexao e leitura
 * @param paginasPorExecucao         quantas paginas aleatorias sao buscadas a cada execucao do job
 * @param intervaloEntreRequisicoes  pausa entre requisicoes, para nao sobrecarregar a API
 * @param tamanhoChunk               tamanho do chunk do step
 * @param limiteFalhas               quantas falhas de leitura sao toleradas (skip) antes de o step falhar
 * @param tamanhoMaximoResumo        tamanho maximo, em caracteres, do resumo armazenado
 */
@ConfigurationProperties(prefix = "wikipedia")
public record WikipediaProperties(
        String baseUrl,
        String userAgent,
        Duration timeout,
        int paginasPorExecucao,
        Duration intervaloEntreRequisicoes,
        int tamanhoChunk,
        int limiteFalhas,
        int tamanhoMaximoResumo) {
}
