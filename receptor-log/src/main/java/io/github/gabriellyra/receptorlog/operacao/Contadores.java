package io.github.gabriellyra.receptorlog.operacao;

import jakarta.inject.Singleton;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Contadores do ultimo periodo, zerados a cada resumo.
 *
 * <p>{@code @Singleton} de proposito, e nao {@code @ApplicationScoped}: com escopo normal o CDI entrega um proxy, e
 * campos acessados direto no proxy sao uma copia separada (o resumo mostrava zero). Aqui so ha metodos, mas o escopo
 * fica assim para nao reabrir o problema.
 */
@Singleton
public class Contadores {

    private final AtomicLong requisicoes = new AtomicLong();
    private final AtomicLong eventos = new AtomicLong();
    private final AtomicLong recusadasSemEspaco = new AtomicLong();
    private final AtomicLong recusadasEncerrando = new AtomicLong();
    private final AtomicLong recusadasToken = new AtomicLong();
    private final AtomicLong recusadasServico = new AtomicLong();
    private final AtomicLong arquivosEnviados = new AtomicLong();
    private final AtomicLong bytesEnviados = new AtomicLong();
    private final AtomicLong arquivosDescartados = new AtomicLong();
    private final AtomicLong bytesDescartados = new AtomicLong();

    public void aceita(int quantidadeEventos) {
        requisicoes.incrementAndGet();
        eventos.addAndGet(quantidadeEventos);
    }

    public void recusadaSemEspaco() {
        recusadasSemEspaco.incrementAndGet();
    }

    public void recusadaEncerrando() {
        recusadasEncerrando.incrementAndGet();
    }

    public void recusadaToken() {
        recusadasToken.incrementAndGet();
    }

    public void recusadaServico() {
        recusadasServico.incrementAndGet();
    }

    public void enviado(long bytes) {
        arquivosEnviados.incrementAndGet();
        bytesEnviados.addAndGet(bytes);
    }

    public void descartado(long bytes) {
        arquivosDescartados.incrementAndGet();
        bytesDescartados.addAndGet(bytes);
    }

    /** Le e zera tudo de uma vez. */
    public Periodo fecharPeriodo() {
        return new Periodo(requisicoes.getAndSet(0), eventos.getAndSet(0), recusadasSemEspaco.getAndSet(0),
                recusadasEncerrando.getAndSet(0), recusadasToken.getAndSet(0), recusadasServico.getAndSet(0),
                arquivosEnviados.getAndSet(0),
                bytesEnviados.getAndSet(0), arquivosDescartados.getAndSet(0), bytesDescartados.getAndSet(0));
    }

    public record Periodo(long requisicoes, long eventos, long recusadasSemEspaco, long recusadasEncerrando,
                          long recusadasToken, long recusadasServico, long arquivosEnviados, long bytesEnviados,
                          long arquivosDescartados,
                          long bytesDescartados) {

        /**
         * Log que nao vai chegar ao S3: descartado por espaco, recusado por falta de espaco ou recusado por token
         * (o servico nao reenvia 401/403, entao o log se perde).
         */
        public boolean houvePerda() {
            return arquivosDescartados > 0 || recusadasSemEspaco > 0 || recusadasToken > 0 || recusadasServico > 0;
        }

        public boolean vazio() {
            return requisicoes == 0 && recusadasSemEspaco == 0 && recusadasEncerrando == 0 && recusadasToken == 0
                    && recusadasServico == 0 && arquivosEnviados == 0 && arquivosDescartados == 0;
        }
    }
}
