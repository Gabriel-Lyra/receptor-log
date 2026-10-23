package io.github.gabriellyra.receptorlog.conversao;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import io.github.gabriellyra.receptorlog.Fuso;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converte uma requisicao OTLP de logs em linhas JSON (uma por evento), agrupadas por servico.
 *
 * <p>As chaves seguem o ECS com ponto ({@code log.level}, {@code service.name}), o mesmo formato decidido para o
 * configurador. Atributos do evento que nao tem campo ECS proprio (MDC, por exemplo) vao para {@code labels}.
 */
@ApplicationScoped
public class ConversorOtlp {

    /** Nome usado quando o servico nao informa {@code service.name}. */
    public static final String SERVICO_DESCONHECIDO = "desconhecido";

    /** Horario de Brasilia com deslocamento: {@code 2026-10-22T16:00:00.123-03:00}. */
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(Fuso.BRASILIA);
    private static final HexFormat HEX = HexFormat.of();

    /**
     * Atributos do evento que viram campos ECS (ou sao ruido) e, por isso, nao vao para {@code labels}.
     * {@code bridge.name} e {@code log.logger.namespace} vem do handler do Quarkus: o primeiro e o nome real do
     * logger; o segundo e sempre a classe da API de log ({@code org.jboss.logging.Logger}).
     */
    private static final Set<String> ATRIBUTOS_MAPEADOS = Set.of(
            "exception.type", "exception.message", "exception.stacktrace",
            "thread.name", "thread.id",
            "code.namespace", "code.function", "code.function.name",
            "code.filepath", "code.file.path", "code.lineno", "code.line.number",
            "bridge.name", "log.logger.namespace");

    private static final JsonFactory JSON = new JsonFactory();
    private final Clock relogio;

    public ConversorOtlp(Clock relogio) {
        this.relogio = relogio;
    }

    /**
     * @return linhas JSON por servico, na ordem em que chegaram. A chave e o {@code service.name} como veio
     *     (sem sanitizar; quem grava em disco decide como transformar em nome de diretorio).
     */
    public Map<String, List<String>> converter(ExportLogsServiceRequest requisicao) {
        Map<String, List<String>> porServico = new LinkedHashMap<>();
        for (ResourceLogs resourceLogs : requisicao.getResourceLogsList()) {
            Recurso recurso = Recurso.de(resourceLogs.getResource().getAttributesList());
            List<String> linhas = porServico.computeIfAbsent(recurso.servico(), s -> new ArrayList<>());
            for (ScopeLogs scopeLogs : resourceLogs.getScopeLogsList()) {
                String logger = scopeLogs.getScope().getName();
                for (LogRecord registro : scopeLogs.getLogRecordsList()) {
                    linhas.add(linha(recurso, logger, registro));
                }
            }
        }
        porServico.values().removeIf(List::isEmpty);
        return porServico;
    }

    private String linha(Recurso recurso, String logger, LogRecord registro) {
        Map<String, AnyValue> atributos = new LinkedHashMap<>();
        for (KeyValue kv : registro.getAttributesList()) {
            atributos.put(kv.getKey(), kv.getValue());
        }

        StringWriter saida = new StringWriter(256);
        try (JsonGenerator g = JSON.createGenerator(saida)) {
            g.writeStartObject();
            g.writeStringField("@timestamp", ISO.format(instante(registro)));
            escreverSeTiver(g, "log.level", nivel(registro));
            g.writeStringField("message", texto(registro.getBody()));
            g.writeStringField("service.name", recurso.servico());
            escreverSeTiver(g, "service.version", recurso.versao());
            escreverSeTiver(g, "service.environment", recurso.ambiente());
            escreverSeTiver(g, "host.name", recurso.host());
            // No Quarkus o escopo e sempre "io.quarkus.opentelemetry"; o logger de verdade vem em bridge.name
            String loggerReal = textoOuNulo(atributos.get("bridge.name"));
            escreverSeTiver(g, "log.logger", loggerReal != null ? loggerReal : logger);
            escreverSeTiver(g, "process.thread.name", textoOuNulo(atributos.get("thread.name")));
            escreverSeTiver(g, "process.thread.id", textoOuNulo(atributos.get("thread.id")));
            // Sem informacao de chamador, o Quarkus manda "null.null" e linha -1: descarta.
            escreverSeTiver(g, "log.origin.function",
                    semNulo(primeiro(atributos, "code.function.name", "code.function")));
            escreverSeTiver(g, "log.origin.class", semNulo(textoOuNulo(atributos.get("code.namespace"))));
            escreverSeTiver(g, "log.origin.file.name", semNulo(primeiro(atributos, "code.file.path", "code.filepath")));
            escreverSeTiver(g, "log.origin.file.line", linhaValida(primeiro(atributos, "code.line.number", "code.lineno")));
            if (!registro.getTraceId().isEmpty()) {
                g.writeStringField("trace.id", HEX.formatHex(registro.getTraceId().toByteArray()));
            }
            if (!registro.getSpanId().isEmpty()) {
                g.writeStringField("span.id", HEX.formatHex(registro.getSpanId().toByteArray()));
            }
            escreverSeTiver(g, "error.type", textoOuNulo(atributos.get("exception.type")));
            escreverSeTiver(g, "error.message", textoOuNulo(atributos.get("exception.message")));
            escreverSeTiver(g, "error.stack_trace", textoOuNulo(atributos.get("exception.stacktrace")));

            boolean abriuLabels = false;
            for (Map.Entry<String, AnyValue> atributo : atributos.entrySet()) {
                if (ATRIBUTOS_MAPEADOS.contains(atributo.getKey())) {
                    continue;
                }
                if (!abriuLabels) {
                    g.writeObjectFieldStart("labels");
                    abriuLabels = true;
                }
                g.writeFieldName(atributo.getKey());
                escreverValor(g, atributo.getValue());
            }
            if (abriuLabels) {
                g.writeEndObject();
            }
            g.writeEndObject();
        } catch (IOException e) {
            // StringWriter nao lanca IOException; so chega aqui por bug.
            throw new UncheckedIOException(e);
        }
        return saida.toString();
    }

    private Instant instante(LogRecord registro) {
        long nanos = registro.getTimeUnixNano() != 0 ? registro.getTimeUnixNano() : registro.getObservedTimeUnixNano();
        if (nanos == 0) {
            return relogio.instant();
        }
        return Instant.ofEpochSecond(0, nanos);
    }

    /**
     * Nivel a partir do {@code severity_number} (padronizado pelo OTLP), com o {@code severity_text} como reserva.
     * O texto varia por framework (JUL manda FINE, SEVERE...); o numero nao.
     */
    static String nivel(LogRecord registro) {
        int n = registro.getSeverityNumberValue();
        if (n >= 21) return "FATAL";
        if (n >= 17) return "ERROR";
        if (n >= 13) return "WARN";
        if (n >= 9) return "INFO";
        if (n >= 5) return "DEBUG";
        if (n >= 1) return "TRACE";
        String texto = registro.getSeverityText().trim().toUpperCase();
        return texto.isEmpty() ? null : texto;
    }

    private static String semNulo(String valor) {
        return valor == null || valor.equals("null") || valor.equals("null.null") ? null : valor;
    }

    private static String linhaValida(String valor) {
        try {
            return valor != null && Long.parseLong(valor) > 0 ? valor : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String primeiro(Map<String, AnyValue> atributos, String... chaves) {
        for (String chave : chaves) {
            String valor = textoOuNulo(atributos.get(chave));
            if (valor != null) {
                return valor;
            }
        }
        return null;
    }

    private static void escreverSeTiver(JsonGenerator g, String campo, String valor) throws IOException {
        if (valor != null && !valor.isEmpty()) {
            g.writeStringField(campo, valor);
        }
    }

    private static String textoOuNulo(AnyValue valor) {
        if (valor == null || valor.getValueCase() == AnyValue.ValueCase.VALUE_NOT_SET) {
            return null;
        }
        return texto(valor);
    }

    /** Representacao em texto de qualquer valor; estruturas viram JSON. */
    static String texto(AnyValue valor) {
        switch (valor.getValueCase()) {
            case STRING_VALUE:
                return valor.getStringValue();
            case BOOL_VALUE:
                return Boolean.toString(valor.getBoolValue());
            case INT_VALUE:
                return Long.toString(valor.getIntValue());
            case DOUBLE_VALUE:
                return Double.toString(valor.getDoubleValue());
            case BYTES_VALUE:
                return Base64.getEncoder().encodeToString(valor.getBytesValue().toByteArray());
            case ARRAY_VALUE:
            case KVLIST_VALUE:
                StringWriter saida = new StringWriter();
                try (JsonGenerator g = JSON.createGenerator(saida)) {
                    escreverValor(g, valor);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return saida.toString();
            default:
                return "";
        }
    }

    /** Escreve o valor preservando o tipo JSON (numero continua numero, lista vira array). */
    private static void escreverValor(JsonGenerator g, AnyValue valor) throws IOException {
        switch (valor.getValueCase()) {
            case STRING_VALUE -> g.writeString(valor.getStringValue());
            case BOOL_VALUE -> g.writeBoolean(valor.getBoolValue());
            case INT_VALUE -> g.writeNumber(valor.getIntValue());
            case DOUBLE_VALUE -> {
                double d = valor.getDoubleValue();
                if (Double.isFinite(d)) {
                    g.writeNumber(d);
                } else {
                    g.writeString(Double.toString(d));
                }
            }
            case BYTES_VALUE -> g.writeString(Base64.getEncoder().encodeToString(valor.getBytesValue().toByteArray()));
            case ARRAY_VALUE -> {
                g.writeStartArray();
                for (AnyValue item : valor.getArrayValue().getValuesList()) {
                    escreverValor(g, item);
                }
                g.writeEndArray();
            }
            case KVLIST_VALUE -> {
                g.writeStartObject();
                for (KeyValue kv : valor.getKvlistValue().getValuesList()) {
                    g.writeFieldName(kv.getKey());
                    escreverValor(g, kv.getValue());
                }
                g.writeEndObject();
            }
            default -> g.writeNull();
        }
    }

    /** Atributos do recurso (o servico que mandou) que interessam em toda linha. */
    record Recurso(String servico, String versao, String ambiente, String host) {

        static Recurso de(List<KeyValue> atributos) {
            Map<String, String> m = new LinkedHashMap<>();
            for (KeyValue kv : atributos) {
                String valor = textoOuNulo(kv.getValue());
                if (valor != null) {
                    m.put(kv.getKey(), valor);
                }
            }
            String servico = m.getOrDefault("service.name", "").trim();
            String ambiente = m.containsKey("deployment.environment.name")
                    ? m.get("deployment.environment.name")
                    : m.get("deployment.environment");
            String host = m.containsKey("k8s.pod.name") ? m.get("k8s.pod.name") : m.get("host.name");
            return new Recurso(
                    servico.isEmpty() ? SERVICO_DESCONHECIDO : servico,
                    m.get("service.version"),
                    ambiente,
                    host);
        }
    }
}
