package io.github.gabriellyra.receptorlog;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Configuracao do receptor. Cada propriedade pode ser sobrescrita por variavel de ambiente
 * (ex.: {@code receptor.buffer.diretorio} -> {@code RECEPTOR_BUFFER_DIRETORIO}).
 */
@ConfigMapping(prefix = "receptor")
public interface ConfiguracaoReceptor {

    Buffer buffer();

    Segmento segmento();

    Requisicao requisicao();

    /**
     * Identifica esta instancia no nome dos arquivos. No Kubernetes, o HOSTNAME e o nome do pod,
     * o que evita colisao entre replicas gravando no mesmo prefixo do bucket.
     */
    @WithDefault("${HOSTNAME:local}")
    String instancia();

    /**
     * Tokens por servico, no formato {@code servico:token} separados por virgula
     * ({@code RECEPTOR_TOKENS=modelo-quarkus-pokemon:abc,modelo-batch-wikipedia:def}). Cada token so pode mandar log
     * com o {@code service.name} dele. Repetir o servico com outro token permite trocar sem parar o servico.
     */
    Optional<List<String>> tokens();

    /**
     * Sem token configurado, o receptor nao sobe. Desligado so nos perfis dev e test
     * ({@code RECEPTOR_TOKEN_OBRIGATORIO=false} para rodar localmente sem token).
     */
    @WithDefault("true")
    boolean tokenObrigatorio();

    interface Buffer {
        /** Diretorio local onde os segmentos ficam ate serem enviados. Disco efemero do pod. */
        @WithDefault("./buffer")
        Path diretorio();

        /**
         * Espaco maximo do buffer (abertos + prontos). Deve ficar abaixo do ephemeral storage do pod. Acima dele, os
         * prontos mais antigos sao descartados; se nem assim couber, o receptor responde 503.
         */
        @WithDefault("1073741824")
        long tetoBytes();
    }

    interface Segmento {
        /**
         * Tempo maximo que um segmento fica aberto. E tambem o maximo que se perde se o pod morrer de repente
         * (OOM, queda do no); no desligamento normal o segmento aberto e enviado.
         */
        @WithDefault("30m")
        Duration idadeMaxima();

        /** Tamanho maximo do segmento ja comprimido, ou seja, do arquivo que vai para o bucket. */
        @WithDefault("1048576")
        long tamanhoMaximoBytes();

        /** De quanto em quanto tempo os segmentos abertos sao conferidos (usado pelo agendador). */
        @WithDefault("5s")
        Duration verificacao();
    }

    interface Requisicao {
        /**
         * Tamanho maximo do corpo depois de descomprimido. Protege contra "bomba de gzip":
         * o limite do Quarkus (quarkus.http.limits.max-body-size) vale so para o corpo comprimido.
         */
        @WithDefault("67108864")
        long tamanhoMaximoDescomprimidoBytes();
    }
}
