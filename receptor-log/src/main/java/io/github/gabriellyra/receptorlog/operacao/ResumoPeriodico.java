package io.github.gabriellyra.receptorlog.operacao;

import io.github.gabriellyra.receptorlog.ConfiguracaoReceptor;
import io.github.gabriellyra.receptorlog.buffer.ArquivosProntos;
import io.github.gabriellyra.receptorlog.buffer.GravadorSegmentos;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.List;

/**
 * Uma linha de log por minuto com o que aconteceu: INFO normalmente, WARN se houve perda (arquivo descartado por falta
 * de espaco ou requisicao recusada com 503). Minuto sem atividade e sem fila nao gera linha.
 */
@ApplicationScoped
public class ResumoPeriodico {

    private static final Logger LOG = Logger.getLogger(ResumoPeriodico.class);

    private final Contadores contadores;
    private final GravadorSegmentos gravador;
    private final ConfiguracaoReceptor config;

    public ResumoPeriodico(Contadores contadores, GravadorSegmentos gravador, ConfiguracaoReceptor config) {
        this.contadores = contadores;
        this.gravador = gravador;
        this.config = config;
    }

    @Scheduled(every = "${receptor.resumo.intervalo:60s}", delayed = "${receptor.resumo.intervalo:60s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void resumir() {
        Contadores.Periodo p = contadores.fecharPeriodo();
        long naFila;
        long uso;
        try {
            List<ArquivosProntos.Arquivo> fila = ArquivosProntos.doMaisAntigo(gravador.diretorioProntos());
            naFila = fila.size();
            uso = ArquivosProntos.tamanho(gravador.diretorioProntos().getParent());
        } catch (IOException e) {
            naFila = -1;
            uso = -1;
        }
        if (p.vazio() && naFila <= 0) {
            return;
        }
        String texto = String.format(
                "Resumo: %d evento(s) em %d requisicao(oes); %d arquivo(s) enviado(s) ao S3 (%s); fila %d arquivo(s); "
                        + "buffer %s de %s; recusadas: %d sem espaco, %d encerrando, %d token invalido, "
                        + "%d servico diferente do token; "
                        + "descartados por falta de espaco: %d arquivo(s) (%s)",
                p.eventos(), p.requisicoes(), p.arquivosEnviados(), Tamanhos.legivel(p.bytesEnviados()), naFila,
                Tamanhos.legivel(uso), Tamanhos.legivel(config.buffer().tetoBytes()), p.recusadasSemEspaco(), p.recusadasEncerrando(),
                p.recusadasToken(), p.recusadasServico(), p.arquivosDescartados(),
                Tamanhos.legivel(p.bytesDescartados()));
        if (p.houvePerda()) {
            LOG.warn(texto + ". HOUVE PERDA DE LOG neste periodo");
        } else {
            LOG.info(texto);
        }
    }
}
