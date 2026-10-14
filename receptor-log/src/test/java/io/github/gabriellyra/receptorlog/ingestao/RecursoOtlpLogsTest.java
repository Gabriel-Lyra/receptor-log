package io.github.gabriellyra.receptorlog.ingestao;

import io.github.gabriellyra.receptorlog.buffer.GravadorSegmentos;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.opentelemetry.proto.resource.v1.Resource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RecursoOtlpLogsTest {

    @Inject
    GravadorSegmentos gravador;

    @Test
    void gravaRequisicaoProtobuf() throws IOException {
        String servico = servicoUnico();

        given().contentType("application/x-protobuf")
                .body(requisicao(servico, "primeiro", "segundo").toByteArray())
                .post("/v1/logs")
                .then().statusCode(200)
                .contentType("application/x-protobuf");

        gravador.fecharTodos();
        String conteudo = conteudoDo(servico);
        assertTrue(conteudo.contains("\"message\":\"primeiro\""));
        assertTrue(conteudo.contains("\"message\":\"segundo\""));
        assertEquals(2, conteudo.lines().count());
    }

    @Test
    void aceitaCorpoComGzip() throws IOException {
        String servico = servicoUnico();

        given().contentType("application/x-protobuf")
                .header("Content-Encoding", "gzip")
                .body(gzip(requisicao(servico, "comprimido").toByteArray()))
                .post("/v1/logs")
                .then().statusCode(200);

        gravador.fecharTodos();
        assertTrue(conteudoDo(servico).contains("\"message\":\"comprimido\""));
    }

    @Test
    void recusaJsonCom415() {
        given().contentType("application/json")
                .body("{\"resourceLogs\":[]}")
                .post("/v1/logs")
                .then().statusCode(415);
    }

    @Test
    void recusaProtobufInvalidoCom400() {
        given().contentType("application/x-protobuf")
                .body(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF})
                .post("/v1/logs")
                .then().statusCode(400);
    }

    @Test
    void recusaGzipQueDescomprimeAlemDoLimiteCom413() throws IOException {
        // 2 MB de zeros viram poucos KB comprimidos; o limite de teste e 1 MB descomprimido
        given().contentType("application/x-protobuf")
                .header("Content-Encoding", "gzip")
                .body(gzip(new byte[2 * 1024 * 1024]))
                .post("/v1/logs")
                .then().statusCode(413);
    }

    @Test
    void recusaGzipCorrompidoCom400() {
        given().contentType("application/x-protobuf")
                .header("Content-Encoding", "gzip")
                .body("nao e gzip".getBytes(StandardCharsets.UTF_8))
                .post("/v1/logs")
                .then().statusCode(400);
    }

    @Test
    void healthDeProntidaoResponde() {
        given().get("/q/health/ready").then().statusCode(200).body("status", equalTo("UP"));
    }

    private String conteudoDo(String servico) throws IOException {
        Path diretorio = gravador.diretorioProntos().resolve("servico=" + servico);
        List<Path> arquivos;
        try (Stream<Path> s = Files.walk(diretorio)) {
            arquivos = s.filter(p -> p.toString().endsWith(".jsonl.gz")).toList();
        }
        assertEquals(1, arquivos.size(), "um segmento para " + servico);
        try (InputStream entrada = new GZIPInputStream(Files.newInputStream(arquivos.get(0)))) {
            return new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String servicoUnico() {
        return "teste-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static ExportLogsServiceRequest requisicao(String servico, String... mensagens) {
        ScopeLogs.Builder escopo = ScopeLogs.newBuilder();
        for (String mensagem : mensagens) {
            escopo.addLogRecords(LogRecord.newBuilder()
                    .setTimeUnixNano(System.currentTimeMillis() * 1_000_000L)
                    .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_INFO)
                    .setBody(AnyValue.newBuilder().setStringValue(mensagem)));
        }
        return ExportLogsServiceRequest.newBuilder()
                .addResourceLogs(ResourceLogs.newBuilder()
                        .setResource(Resource.newBuilder().addAttributes(KeyValue.newBuilder()
                                .setKey("service.name")
                                .setValue(AnyValue.newBuilder().setStringValue(servico))))
                        .addScopeLogs(escopo))
                .build();
    }

    private static byte[] gzip(byte[] dados) throws IOException {
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(saida)) {
            gz.write(dados);
        }
        return saida.toByteArray();
    }
}
