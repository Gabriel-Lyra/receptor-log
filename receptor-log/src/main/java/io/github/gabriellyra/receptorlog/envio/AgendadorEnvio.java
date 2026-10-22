package io.github.gabriellyra.receptorlog.envio;

import io.github.gabriellyra.receptorlog.buffer.GravadorSegmentos;
import io.github.gabriellyra.receptorlog.operacao.Contadores;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Liga o envio ao S3 quando ha bucket configurado e roda uma rodada a cada {@code receptor.s3.intervalo}.
 * Sem bucket, avisa no log e nao faz nada: os arquivos ficam em {@code prontos/}.
 *
 * <p>No desligamento, depois de o gravador fechar os segmentos abertos, faz um envio final com prazo
 * ({@code receptor.s3.prazo-envio-final}, 12 s). O disco do pod e efemero: o que nao subir ate la se perde quando o
 * pod for removido.
 */
@ApplicationScoped
public class AgendadorEnvio {

    private static final Logger LOG = Logger.getLogger(AgendadorEnvio.class);

    private final ConfiguracaoS3 config;
    private final GravadorSegmentos gravador;
    private final Clock relogio;
    private final Contadores contadores;
    private volatile EnviadorProntos enviador;

    public AgendadorEnvio(ConfiguracaoS3 config, GravadorSegmentos gravador, Clock relogio, Contadores contadores) {
        this.config = config;
        this.gravador = gravador;
        this.relogio = relogio;
        this.contadores = contadores;
    }

    void iniciar(@Observes StartupEvent evento) {
        if (!config.envioHabilitado()) {
            LOG.infof("Envio ao S3 desligado (receptor.s3.envio-habilitado=false). Os arquivos ficam em %s",
                    gravador.diretorioProntos());
            return;
        }
        if (config.bucket().filter(b -> !b.isBlank()).isEmpty()) {
            LOG.warnf("Envio ao S3 desligado (RECEPTOR_S3_BUCKET vazio). Os arquivos ficam em %s",
                    gravador.diretorioProntos());
            return;
        }
        DestinoS3 destino = DestinoS3.criar(config);
        enviador = new EnviadorProntos(gravador.diretorioProntos(), destino, config.prefixo().orElse(""), relogio,
                config.esperaInicial(), config.esperaMaxima(), config.idadePastaVazia(), contadores);
        LOG.infof("Envio ao S3 ligado: %s, prefixo '%s', a cada %s",
                destino.descricao(), config.prefixo().orElse(""), config.intervalo());
    }

    @Scheduled(every = "${receptor.s3.intervalo:5s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void ciclo() {
        EnviadorProntos atual = enviador;
        if (atual != null) {
            atual.ciclo();
        }
    }

    /** Roda depois do gravador ({@code @Priority(1)}), que fecha os segmentos abertos. */
    void encerrar(@Observes @Priority(2) ShutdownEvent evento) {
        EnviadorProntos atual = enviador;
        if (atual == null) {
            return;
        }
        Duration prazo = config.prazoEnvioFinal();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "receptor-envio-final");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<EnviadorProntos.Resultado> futuro =
                    executor.submit(() -> atual.enviarPendentes(relogio.instant().plus(prazo)));
            EnviadorProntos.Resultado r = futuro.get(prazo.toMillis() + 1000, TimeUnit.MILLISECONDS);
            if (r.naFila() == 0) {
                LOG.infof("Envio final: %d arquivo(s) enviado(s), nada ficou no disco", r.enviados());
            } else {
                LOG.warnf("Envio final: %d arquivo(s) enviado(s), %d ficaram no disco e se perdem se o pod for "
                        + "removido (voltam a subir se o receptor reiniciar no mesmo disco)", r.enviados(), r.naFila());
            }
        } catch (TimeoutException e) {
            LOG.warnf("Envio final passou do prazo de %s; o que nao subiu ficou no disco", prazo);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.errorf(e, "Falha no envio final");
        } finally {
            executor.shutdownNow();
        }
    }

    /** Null quando o envio esta desligado. */
    public EnviadorProntos enviador() {
        return enviador;
    }
}
