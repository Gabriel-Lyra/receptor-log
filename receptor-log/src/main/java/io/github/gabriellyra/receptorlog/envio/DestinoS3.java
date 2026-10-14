package io.github.gabriellyra.receptorlog.envio;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Envia para um S3 (AWS ou compativel) com o AWS SDK v2.
 *
 * <p>Escolhas para funcionar em S3 compativel, nao so na AWS:
 * <ul>
 *   <li>{@code chunkedEncodingEnabled(false)}: sem isso, sobre HTTP o SDK manda o corpo em {@code aws-chunked}
 *       com {@code STREAMING-AWS4-HMAC-SHA256-PAYLOAD}, que nem todo S3 compativel implementa.</li>
 *   <li>Checksums so quando a operacao exige: os checksums novos (CRC32 etc.) tambem nao sao universais.</li>
 *   <li>Cliente HTTP do proprio JDK ({@code url-connection-client}): sem Apache HttpClient nem Netty.</li>
 * </ul>
 */
public final class DestinoS3 implements Destino {

    private final S3Client cliente;
    private final String bucket;
    private final String descricao;

    DestinoS3(S3Client cliente, String bucket, String descricao) {
        this.cliente = cliente;
        this.bucket = bucket;
        this.descricao = descricao;
    }

    public static DestinoS3 criar(ConfiguracaoS3 config) {
        String bucket = config.bucket().filter(b -> !b.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("receptor.s3.bucket vazio"));

        S3ClientBuilder construtor = S3Client.builder()
                .httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(60))
                        .build())
                .region(Region.of(config.regiao()))
                .credentialsProvider(credenciais(config))
                .serviceConfiguration(S3Configuration.builder()
                        .chunkedEncodingEnabled(false)
                        .pathStyleAccessEnabled(config.pathStyle())
                        .build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(120))
                        .build());
        config.endpoint().ifPresent(construtor::endpointOverride);

        String onde = config.endpoint().map(e -> " em " + e).orElse(" (AWS, " + config.regiao() + ")");
        return new DestinoS3(construtor.build(), bucket, "s3://" + bucket + onde);
    }

    private static AwsCredentialsProvider credenciais(ConfiguracaoS3 config) {
        if (config.chaveAcesso().isPresent() && config.chaveSecreta().isPresent()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(config.chaveAcesso().get(), config.chaveSecreta().get()));
        }
        return DefaultCredentialsProvider.builder().build();
    }

    @Override
    public void enviar(String chave, Path arquivo) {
        cliente.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(chave)
                        // Sem Content-Encoding: com "gzip" ali, clientes HTTP descomprimem sozinhos no download
                        // e quem le o .gz (DuckDB, por exemplo) tenta descomprimir de novo.
                        .contentType("application/gzip")
                        .build(),
                RequestBody.fromFile(arquivo));
    }

    @Override
    public String descricao() {
        return descricao;
    }
}
