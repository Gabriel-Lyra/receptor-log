package io.github.gabriellyra.receptorlog.envio;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * Destino S3 dos segmentos prontos. Cada propriedade pode vir de variavel de ambiente
 * (ex.: {@code receptor.s3.bucket} -> {@code RECEPTOR_S3_BUCKET}).
 *
 * <p>Sem bucket, o envio fica desligado e os arquivos continuam em {@code prontos/}.
 */
@ConfigMapping(prefix = "receptor.s3")
public interface ConfiguracaoS3 {

    /**
     * Interruptor do envio. Existe para os testes: {@code %test.receptor.s3.envio-habilitado=false} no
     * application.properties vence o RECEPTOR_S3_BUCKET vindo do {@code .env} ou do ambiente (valor de perfil tem
     * prioridade sobre valor sem perfil; ja um {@code %test.receptor.s3.bucket=} vazio nao vence, porque vazio conta
     * como "nao definido").
     */
    @WithDefault("true")
    boolean envioHabilitado();

    /** Bucket de destino. Vazio desliga o envio. */
    Optional<String> bucket();

    /** Endpoint do S3 (ex.: {@code https://s3.exemplo.local}). Vazio usa o endpoint da AWS para a regiao. */
    Optional<URI> endpoint();

    /** Regiao assinada nas requisicoes. S3 compativel fora da AWS costuma aceitar qualquer uma. */
    @WithDefault("us-east-1")
    String regiao();

    /** Prefixo das chaves no bucket (ex.: {@code logs}). Vazio grava na raiz do bucket. */
    Optional<String> prefixo();

    /** Endereco no estilo {@code endpoint/bucket/chave}. S3 compativel fora da AWS quase sempre exige. */
    @WithDefault("true")
    boolean pathStyle();

    /** Credencial fixa. Sem as duas chaves, usa a cadeia padrao do SDK (variaveis AWS_*, perfil, etc.). */
    Optional<String> chaveAcesso();

    Optional<String> chaveSecreta();

    /** De quanto em quanto tempo procura arquivos prontos para enviar. */
    @WithDefault("5s")
    Duration intervalo();

    /** Espera depois da primeira falha. Dobra a cada falha seguida, ate a espera maxima. */
    @WithDefault("5s")
    Duration esperaInicial();

    @WithDefault("60s")
    Duration esperaMaxima();

    /**
     * Tempo maximo do envio final no desligamento. Somado ao {@code quarkus.shutdown.delay} (5 s) e ao
     * {@code quarkus.shutdown.timeout} (10 s), tem de caber no {@code terminationGracePeriodSeconds} do pod (30 s).
     */
    @WithDefault("12s")
    Duration prazoEnvioFinal();

    /** Pasta vazia em prontos/ parada ha mais tempo que isso e apagada. */
    @WithDefault("10m")
    Duration idadePastaVazia();
}
