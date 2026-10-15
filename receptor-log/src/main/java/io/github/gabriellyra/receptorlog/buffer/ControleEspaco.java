package io.github.gabriellyra.receptorlog.buffer;

import io.github.gabriellyra.receptorlog.ConfiguracaoReceptor;
import io.github.gabriellyra.receptorlog.operacao.Contadores;
import io.github.gabriellyra.receptorlog.operacao.Tamanhos;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Mantem o buffer abaixo do teto ({@code receptor.buffer.teto-bytes}), que deve ficar abaixo do ephemeral storage do
 * pod: se o disco do pod enche, o Kubernetes despeja o pod e perde tudo de uma vez.
 *
 * <p>Antes de cada gravacao:
 * <ol>
 *   <li>abaixo do teto: grava;</li>
 *   <li>no teto ou acima: descarta os prontos mais antigos ate ficar em 90% do teto (o S3 deve estar fora do ar ha
 *       muito tempo; perder o mais velho e melhor do que parar de receber o novo);</li>
 *   <li>se mesmo sem prontos continuar acima (so segmentos abertos): responde 503 e o cliente tenta de novo.</li>
 * </ol>
 *
 * <p>O uso do disco e medido no maximo uma vez por segundo, entao o buffer pode passar um pouco do teto. Deixe
 * folga entre o teto e o ephemeral storage.
 */
@ApplicationScoped
public class ControleEspaco {

    private static final Logger LOG = Logger.getLogger(ControleEspaco.class);
    private static final Duration VALIDADE_MEDICAO = Duration.ofSeconds(1);

    private final ConfiguracaoReceptor config;
    private final GravadorSegmentos gravador;
    private final Contadores contadores;
    private final Clock relogio;

    private long usoMedido;
    private Instant medidoEm = Instant.MIN;

    public ControleEspaco(ConfiguracaoReceptor config, GravadorSegmentos gravador, Contadores contadores,
                          Clock relogio) {
        this.config = config;
        this.gravador = gravador;
        this.contadores = contadores;
        this.relogio = relogio;
    }

    /** True se ha espaco para gravar (descartando prontos antigos, se preciso). */
    public synchronized boolean liberar() {
        long teto = config.buffer().tetoBytes();
        long uso = uso();
        if (uso < teto) {
            return true;
        }
        long alvo = teto / 10 * 9;
        int descartados = 0;
        long bytesDescartados = 0;
        try {
            List<ArquivosProntos.Arquivo> prontos = ArquivosProntos.doMaisAntigo(gravador.diretorioProntos());
            for (ArquivosProntos.Arquivo arquivo : prontos) {
                if (uso <= alvo) {
                    break;
                }
                if (Files.deleteIfExists(arquivo.caminho())) {
                    uso -= arquivo.bytes();
                    descartados++;
                    bytesDescartados += arquivo.bytes();
                    contadores.descartado(arquivo.bytes());
                }
            }
        } catch (IOException e) {
            LOG.errorf(e, "Falha ao liberar espaco no buffer");
        }
        if (descartados > 0) {
            LOG.warnf("Buffer no teto (%s): %d arquivo(s) mais antigo(s) descartado(s) (%s) SEM chegar ao S3",
                    Tamanhos.legivel(teto), descartados, Tamanhos.legivel(bytesDescartados));
        }
        usoMedido = uso;
        medidoEm = relogio.instant();
        return uso < teto;
    }

    /** Uso atual do buffer, medido de novo se a ultima medicao tem mais de 1 s. */
    long uso() {
        Instant agora = relogio.instant();
        if (agora.isBefore(medidoEm.plus(VALIDADE_MEDICAO))) {
            return usoMedido;
        }
        Path raiz = gravador.diretorioProntos().getParent();
        try {
            usoMedido = ArquivosProntos.tamanho(raiz);
        } catch (IOException e) {
            LOG.errorf(e, "Nao foi possivel medir o buffer em %s", raiz);
        }
        medidoEm = agora;
        return usoMedido;
    }
}
