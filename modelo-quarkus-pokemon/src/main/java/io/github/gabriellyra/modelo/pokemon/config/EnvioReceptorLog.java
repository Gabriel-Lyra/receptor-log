package io.github.gabriellyra.modelo.pokemon.config;

import java.util.Optional;

import jakarta.inject.Singleton;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdkBuilder;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.quarkus.opentelemetry.runtime.AutoConfiguredOpenTelemetrySdkBuilderCustomizer;

/**
 * Envia os logs tambem para o receptor-log, sem tirar o exportador da stack corporativa.
 * Sem RECEPTOR_LOG_ENDPOINT, nao faz nada.
 */
@Singleton
public class EnvioReceptorLog implements AutoConfiguredOpenTelemetrySdkBuilderCustomizer {

    private static final Logger LOG = Logger.getLogger(EnvioReceptorLog.class);

    @ConfigProperty(name = "receptor-log.endpoint")
    Optional<String> endpoint;

    @ConfigProperty(name = "receptor-log.token")
    Optional<String> token;

    @Override
    public void customize(AutoConfiguredOpenTelemetrySdkBuilder builder) {
        if (endpoint.isEmpty() || endpoint.get().isBlank()) {
            LOG.info("receptor-log desligado (receptor-log.endpoint vazio)");
            return;
        }
        String url = endpoint.get().replaceAll("/+$", "") + "/v1/logs";
        var exportador = OtlpHttpLogRecordExporter.builder()
                .setEndpoint(url)
                .setCompression("gzip");
        token.filter(t -> !t.isBlank()).ifPresent(t -> exportador.addHeader("Authorization", "Bearer " + t));
        builder.addLoggerProviderCustomizer((provider, config) -> provider.addLogRecordProcessor(
                BatchLogRecordProcessor.builder(exportador.build()).build()));
        LOG.infof("receptor-log ligado: %s", url);
    }
}
