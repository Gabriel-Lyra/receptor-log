package io.github.gabriellyra.receptorlog.conversao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.ArrayValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.common.v1.KeyValueList;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.opentelemetry.proto.resource.v1.Resource;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversorOtlpTest {

    private static final Instant AGORA = Instant.parse("2026-10-07T15:00:00Z");
    private final ConversorOtlp conversor = new ConversorOtlp(Clock.fixed(AGORA, ZoneOffset.UTC));
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void converteEventoCompletoParaEcs() throws Exception {
        byte[] traceId = HexFormat.of().parseHex("0af7651916cd43dd8448eb211c80319c");
        byte[] spanId = HexFormat.of().parseHex("b7ad6b7169203331");
        LogRecord registro = LogRecord.newBuilder()
                .setTimeUnixNano(1_791_390_000_123_456_789L)
                .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_ERROR)
                .setSeverityText("ERROR")
                .setBody(texto("Falha ao processar lote"))
                .addAttributes(atributo("exception.type", texto("java.lang.IllegalStateException")))
                .addAttributes(atributo("exception.message", texto("estado invalido")))
                .addAttributes(atributo("exception.stacktrace", texto("java.lang.IllegalStateException: ...")))
                .addAttributes(atributo("thread.name", texto("main")))
                .addAttributes(atributo("thread.id", AnyValue.newBuilder().setIntValue(1).build()))
                .addAttributes(atributo("job.execution.id", AnyValue.newBuilder().setIntValue(42).build()))
                .addAttributes(atributo("tentativa", AnyValue.newBuilder().setDoubleValue(1.5).build()))
                .setTraceId(ByteString.copyFrom(traceId))
                .setSpanId(ByteString.copyFrom(spanId))
                .build();

        Map<String, List<String>> resultado = conversor.converter(requisicao(
                recurso("modelo-batch", "1.0.0", "hm", "pod-abc"), "io.github.gabriellyra.modelo.Job", registro));

        assertEquals(List.of("modelo-batch"), List.copyOf(resultado.keySet()));
        JsonNode linha = json.readTree(resultado.get("modelo-batch").get(0));
        // Horario de Brasilia, com deslocamento (16:20 UTC = 13:20 em Brasilia)
        assertEquals("2026-10-07T13:20:00.123456789-03:00", linha.get("@timestamp").asText());
        assertEquals("ERROR", linha.get("log.level").asText());
        assertEquals("Falha ao processar lote", linha.get("message").asText());
        assertEquals("modelo-batch", linha.get("service.name").asText());
        assertEquals("1.0.0", linha.get("service.version").asText());
        assertEquals("hm", linha.get("service.environment").asText());
        assertEquals("pod-abc", linha.get("host.name").asText());
        assertEquals("io.github.gabriellyra.modelo.Job", linha.get("log.logger").asText());
        assertEquals("main", linha.get("process.thread.name").asText());
        assertEquals("1", linha.get("process.thread.id").asText());
        assertEquals("0af7651916cd43dd8448eb211c80319c", linha.get("trace.id").asText());
        assertEquals("b7ad6b7169203331", linha.get("span.id").asText());
        assertEquals("java.lang.IllegalStateException", linha.get("error.type").asText());
        assertEquals("estado invalido", linha.get("error.message").asText());
        assertTrue(linha.get("error.stack_trace").asText().startsWith("java.lang.IllegalStateException"));
        // Atributos sem campo ECS vao para labels, mantendo o tipo
        assertEquals(42, linha.get("labels").get("job.execution.id").asLong());
        assertEquals(1.5, linha.get("labels").get("tentativa").asDouble());
        // Os mapeados nao se repetem em labels
        assertFalse(linha.get("labels").has("exception.type"));
        assertFalse(linha.get("labels").has("thread.name"));
    }

    /** Formato real enviado pelo quarkus-opentelemetry 3.33 (capturado na validacao ponta a ponta). */
    @Test
    void quarkusUsaBridgeNameComoLoggerEDescartaOrigemVazia() throws Exception {
        LogRecord comOrigem = LogRecord.newBuilder()
                .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_INFO)
                .setBody(texto("Pokemon cadastrado"))
                .addAttributes(atributo("bridge.name", texto("io.github.gabriellyra.modelo.pokemon.service.PokemonService")))
                .addAttributes(atributo("log.logger.namespace", texto("org.jboss.logging.Logger")))
                .addAttributes(atributo("code.function.name", texto("io.github.gabriellyra.modelo.pokemon.service.PokemonService.criar")))
                .addAttributes(atributo("code.line.number", AnyValue.newBuilder().setIntValue(16).build()))
                .addAttributes(atributo("pokemon.numero", texto("25")))
                .build();
        LogRecord semOrigem = LogRecord.newBuilder()
                .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_WARN)
                .setBody(texto("config"))
                .addAttributes(atributo("bridge.name", texto("io.quarkus.config")))
                .addAttributes(atributo("code.function.name", texto("null.null")))
                .addAttributes(atributo("code.line.number", AnyValue.newBuilder().setIntValue(-1).build()))
                .build();

        List<String> linhas = conversor.converter(requisicao(recurso("cliente-quarkus", null, null, null),
                "io.quarkus.opentelemetry", comOrigem, semOrigem)).get("cliente-quarkus");

        JsonNode primeira = json.readTree(linhas.get(0));
        assertEquals("io.github.gabriellyra.modelo.pokemon.service.PokemonService", primeira.get("log.logger").asText());
        assertEquals("io.github.gabriellyra.modelo.pokemon.service.PokemonService.criar", primeira.get("log.origin.function").asText());
        assertEquals("16", primeira.get("log.origin.file.line").asText());
        assertEquals("25", primeira.get("labels").get("pokemon.numero").asText());
        assertFalse(primeira.get("labels").has("bridge.name"));
        assertFalse(primeira.get("labels").has("log.logger.namespace"));

        JsonNode segunda = json.readTree(linhas.get(1));
        assertEquals("io.quarkus.config", segunda.get("log.logger").asText());
        assertFalse(segunda.has("log.origin.function"));
        assertFalse(segunda.has("log.origin.file.line"));
        assertFalse(segunda.has("labels"));
    }

    @Test
    void eventoSimplesNaoGeraCamposVazios() throws Exception {
        LogRecord registro = LogRecord.newBuilder()
                .setTimeUnixNano(1L)
                .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_INFO)
                .setBody(texto("ok"))
                .build();

        JsonNode linha = json.readTree(conversor.converter(
                requisicao(recurso("svc", null, null, null), "", registro)).get("svc").get(0));

        assertFalse(linha.has("labels"));
        assertFalse(linha.has("trace.id"));
        assertFalse(linha.has("error.type"));
        assertFalse(linha.has("log.logger"));
        assertFalse(linha.has("service.version"));
    }

    @Test
    void nivelPeloNumeroComTextoComoReserva() {
        assertEquals("DEBUG", ConversorOtlp.nivel(comNivel(SeverityNumber.SEVERITY_NUMBER_DEBUG2, "FINE")));
        assertEquals("WARN", ConversorOtlp.nivel(comNivel(SeverityNumber.SEVERITY_NUMBER_WARN, "WARNING")));
        assertEquals("FATAL", ConversorOtlp.nivel(comNivel(SeverityNumber.SEVERITY_NUMBER_FATAL, "")));
        assertEquals("TRACE", ConversorOtlp.nivel(comNivel(SeverityNumber.SEVERITY_NUMBER_TRACE, "")));
        assertEquals("NOTICE", ConversorOtlp.nivel(comNivel(SeverityNumber.SEVERITY_NUMBER_UNSPECIFIED, "notice")));
    }

    @Test
    void semServiceNameUsaDesconhecido() {
        ExportLogsServiceRequest requisicao = ExportLogsServiceRequest.newBuilder()
                .addResourceLogs(ResourceLogs.newBuilder()
                        .addScopeLogs(ScopeLogs.newBuilder()
                                .addLogRecords(LogRecord.newBuilder().setBody(texto("x")))))
                .build();

        assertEquals(List.of(ConversorOtlp.SERVICO_DESCONHECIDO), List.copyOf(conversor.converter(requisicao).keySet()));
    }

    @Test
    void semHorarioUsaObservadoOuAgora() throws Exception {
        LogRecord observado = LogRecord.newBuilder().setObservedTimeUnixNano(2_000_000_000L).setBody(texto("a")).build();
        LogRecord nenhum = LogRecord.newBuilder().setBody(texto("b")).build();

        List<String> linhas = conversor.converter(requisicao(recurso("svc", null, null, null), "l", observado, nenhum))
                .get("svc");

        assertEquals("1969-12-31T21:00:02-03:00", json.readTree(linhas.get(0)).get("@timestamp").asText());
        assertEquals("2026-10-07T12:00:00-03:00", json.readTree(linhas.get(1)).get("@timestamp").asText());
    }

    @Test
    void corpoEstruturadoViraJsonNaMensagem() throws Exception {
        AnyValue corpo = AnyValue.newBuilder().setKvlistValue(KeyValueList.newBuilder()
                .addValues(atributo("pedido", AnyValue.newBuilder().setIntValue(7).build()))
                .addValues(atributo("itens", AnyValue.newBuilder().setArrayValue(ArrayValue.newBuilder()
                        .addValues(texto("a")).addValues(texto("b"))).build())))
                .build();

        JsonNode linha = json.readTree(conversor.converter(requisicao(recurso("svc", null, null, null), "l",
                LogRecord.newBuilder().setBody(corpo).build())).get("svc").get(0));

        assertEquals("{\"pedido\":7,\"itens\":[\"a\",\"b\"]}", linha.get("message").asText());
    }

    @Test
    void agrupaPorServicoMantendoOrdem() {
        ExportLogsServiceRequest requisicao = ExportLogsServiceRequest.newBuilder()
                .addResourceLogs(ResourceLogs.newBuilder().setResource(recurso("b", null, null, null))
                        .addScopeLogs(ScopeLogs.newBuilder().addLogRecords(LogRecord.newBuilder().setBody(texto("1")))))
                .addResourceLogs(ResourceLogs.newBuilder().setResource(recurso("a", null, null, null))
                        .addScopeLogs(ScopeLogs.newBuilder().addLogRecords(LogRecord.newBuilder().setBody(texto("2")))))
                .addResourceLogs(ResourceLogs.newBuilder().setResource(recurso("b", null, null, null))
                        .addScopeLogs(ScopeLogs.newBuilder().addLogRecords(LogRecord.newBuilder().setBody(texto("3")))))
                .build();

        Map<String, List<String>> resultado = conversor.converter(requisicao);

        assertEquals(List.of("b", "a"), List.copyOf(resultado.keySet()));
        assertEquals(2, resultado.get("b").size());
        assertTrue(resultado.get("b").get(1).contains("\"message\":\"3\""));
    }

    private static LogRecord comNivel(SeverityNumber numero, String texto) {
        return LogRecord.newBuilder().setSeverityNumber(numero).setSeverityText(texto).build();
    }

    static ExportLogsServiceRequest requisicao(Resource recurso, String logger, LogRecord... registros) {
        ScopeLogs.Builder escopo = ScopeLogs.newBuilder().setScope(InstrumentationScope.newBuilder().setName(logger));
        for (LogRecord registro : registros) {
            escopo.addLogRecords(registro);
        }
        return ExportLogsServiceRequest.newBuilder()
                .addResourceLogs(ResourceLogs.newBuilder().setResource(recurso).addScopeLogs(escopo))
                .build();
    }

    static Resource recurso(String servico, String versao, String ambiente, String host) {
        Resource.Builder r = Resource.newBuilder().addAttributes(atributo("service.name", texto(servico)));
        if (versao != null) r.addAttributes(atributo("service.version", texto(versao)));
        if (ambiente != null) r.addAttributes(atributo("deployment.environment.name", texto(ambiente)));
        if (host != null) r.addAttributes(atributo("host.name", texto(host)));
        r.addAttributes(atributo("telemetry.sdk.language", texto("java")));
        return r.build();
    }

    static KeyValue atributo(String chave, AnyValue valor) {
        return KeyValue.newBuilder().setKey(chave).setValue(valor).build();
    }

    static AnyValue texto(String valor) {
        return AnyValue.newBuilder().setStringValue(valor).build();
    }
}
