package io.github.gabriellyra.receptorlog.ingestao;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.resource.v1.Resource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(FiltroTokenTest.ComTokens.class)
class FiltroTokenTest {

    public static class ComTokens implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("receptor.tokens",
                    "teste-token:token-atual, teste-token:token-anterior, outro-servico:token-do-outro");
        }
    }

    @Test
    void semCabecalhoRecebe401() {
        given().contentType("application/x-protobuf").body(corpo())
                .post("/v1/logs")
                .then().statusCode(401).header("WWW-Authenticate", equalTo("Bearer"));
    }

    @Test
    void tokenErradoRecebe401() {
        given().contentType("application/x-protobuf").header("Authorization", "Bearer token-atua").body(corpo())
                .post("/v1/logs")
                .then().statusCode(401);
    }

    @Test
    void esquemaErradoRecebe401() {
        given().contentType("application/x-protobuf").header("Authorization", "Basic token-atual").body(corpo())
                .post("/v1/logs")
                .then().statusCode(401);
    }

    @Test
    void aceitaQualquerUmDosTokensConfigurados() {
        given().contentType("application/x-protobuf").header("Authorization", "Bearer token-atual").body(corpo())
                .post("/v1/logs")
                .then().statusCode(200);
        given().contentType("application/x-protobuf").header("Authorization", "bearer token-anterior").body(corpo())
                .post("/v1/logs")
                .then().statusCode(200);
    }

    @Test
    void tokenDeOutroServicoRecebe403() {
        // Token valido, mas de "outro-servico"; o corpo diz service.name = teste-token.
        given().contentType("application/x-protobuf").header("Authorization", "Bearer token-do-outro").body(corpo())
                .post("/v1/logs")
                .then().statusCode(403);
    }

    @Test
    void recusaAntesDeOlharOCorpo() {
        // Corpo invalido daria 400; sem token, a resposta e 401: o filtro vem antes.
        given().contentType("application/x-protobuf").body(new byte[] {(byte) 0xFF})
                .post("/v1/logs")
                .then().statusCode(401);
    }

    @Test
    void healthNaoExigeToken() {
        given().get("/q/health/ready").then().statusCode(200);
    }

    private static byte[] corpo() {
        return ExportLogsServiceRequest.newBuilder()
                .addResourceLogs(ResourceLogs.newBuilder()
                        .setResource(Resource.newBuilder().addAttributes(KeyValue.newBuilder()
                                .setKey("service.name").setValue(AnyValue.newBuilder().setStringValue("teste-token"))))
                        .addScopeLogs(ScopeLogs.newBuilder().addLogRecords(LogRecord.newBuilder()
                                .setTimeUnixNano(System.currentTimeMillis() * 1_000_000L)
                                .setBody(AnyValue.newBuilder().setStringValue("ok")))))
                .build().toByteArray();
    }
}
