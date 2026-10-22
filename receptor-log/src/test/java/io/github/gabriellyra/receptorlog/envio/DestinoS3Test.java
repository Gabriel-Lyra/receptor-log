package io.github.gabriellyra.receptorlog.envio;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SDK de verdade contra um S3 falso em memoria, para conferir o que vai no fio. */
class DestinoS3Test {

    @TempDir
    Path pasta;

    private HttpServer servidor;
    private final Map<String, byte[]> objetos = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<String>>> cabecalhos = new ConcurrentHashMap<>();
    private final AtomicInteger respostaForcada = new AtomicInteger(0);

    @BeforeEach
    void subir() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", troca -> {
            byte[] corpo = troca.getRequestBody().readAllBytes();
            int forcada = respostaForcada.get();
            if (forcada != 0) {
                byte[] erro = ("<Error><Code>InternalError</Code><Message>falha simulada</Message></Error>").getBytes();
                troca.sendResponseHeaders(forcada, erro.length);
                troca.getResponseBody().write(erro);
                troca.close();
                return;
            }
            if ("PUT".equals(troca.getRequestMethod())) {
                String caminho = troca.getRequestURI().getRawPath();
                objetos.put(caminho, corpo);
                cabecalhos.put(caminho, Map.copyOf(troca.getRequestHeaders()));
                troca.getResponseHeaders().add("ETag", "\"abc\"");
                troca.sendResponseHeaders(200, -1);
            } else {
                troca.sendResponseHeaders(405, -1);
            }
            troca.close();
        });
        servidor.start();
    }

    @AfterEach
    void descer() {
        servidor.stop(0);
    }

    @Test
    void gravaNoEstiloPathSemAwsChunked() throws Exception {
        Path arquivo = pasta.resolve("p.jsonl.gz");
        byte[] conteudo = new byte[200_000];
        for (int i = 0; i < conteudo.length; i++) {
            conteudo[i] = (byte) (i * 31);
        }
        Files.write(arquivo, conteudo);

        destino().enviar("logs/servico=a/data=2026-10-14/hora=12/p.jsonl.gz", arquivo);

        String caminho = "/meus-logs/logs/servico%3Da/data%3D2026-10-14/hora%3D12/p.jsonl.gz";
        String chaveRecebida = objetos.keySet().stream().findFirst().orElseThrow();
        assertEquals(java.net.URLDecoder.decode(caminho, "UTF-8"), java.net.URLDecoder.decode(chaveRecebida, "UTF-8"));
        assertArrayEquals(conteudo, objetos.get(chaveRecebida));

        Map<String, List<String>> h = cabecalhos.get(chaveRecebida);
        String sha = primeiro(h, "x-amz-content-sha256");
        assertNotNull(primeiro(h, "Authorization"));
        assertFalse(sha != null && sha.startsWith("STREAMING"), "nao pode usar aws-chunked: " + sha);
        String codificacao = primeiro(h, "Content-encoding");
        assertTrue(codificacao == null || !codificacao.contains("aws-chunked"), "Content-Encoding: " + codificacao);
        assertEquals("application/gzip", primeiro(h, "Content-type"));
        assertTrue(h.keySet().stream().noneMatch(k -> k.toLowerCase().startsWith("x-amz-checksum")),
                "nao deve mandar checksum novo: " + h.keySet());
    }

    @Test
    void erroDoS3ViraExcecao() throws IOException {
        Path arquivo = pasta.resolve("p.jsonl.gz");
        Files.writeString(arquivo, "x");
        respostaForcada.set(500);

        assertThrows(Exception.class, () -> destino().enviar("p.jsonl.gz", arquivo));
    }

    private DestinoS3 destino() {
        URI endpoint = URI.create("http://127.0.0.1:" + servidor.getAddress().getPort());
        return DestinoS3.criar(new ConfiguracaoS3() {
            @Override public boolean envioHabilitado() { return true; }
            @Override public Optional<String> bucket() { return Optional.of("meus-logs"); }
            @Override public Optional<URI> endpoint() { return Optional.of(endpoint); }
            @Override public String regiao() { return "us-east-1"; }
            @Override public Optional<String> prefixo() { return Optional.empty(); }
            @Override public boolean pathStyle() { return true; }
            @Override public Optional<String> chaveAcesso() { return Optional.of("teste"); }
            @Override public Optional<String> chaveSecreta() { return Optional.of("teste"); }
            @Override public Duration intervalo() { return Duration.ofSeconds(5); }
            @Override public Duration esperaInicial() { return Duration.ofSeconds(5); }
            @Override public Duration esperaMaxima() { return Duration.ofSeconds(60); }
            @Override public Duration idadePastaVazia() { return Duration.ofMinutes(10); }
            @Override public Duration prazoEnvioFinal() { return Duration.ofSeconds(12); }
        });
    }

    private static String primeiro(Map<String, List<String>> cabecalhos, String nome) {
        return cabecalhos.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(nome))
                .map(e -> e.getValue().get(0))
                .findFirst().orElse(null);
    }
}
