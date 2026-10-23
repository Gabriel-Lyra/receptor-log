package io.github.gabriellyra.receptorlog.ingestao;

import io.github.gabriellyra.receptorlog.ConfiguracaoReceptor;
import io.github.gabriellyra.receptorlog.buffer.ControleEspaco;
import io.github.gabriellyra.receptorlog.buffer.GravadorSegmentos;
import io.github.gabriellyra.receptorlog.conversao.ConversorOtlp;
import io.github.gabriellyra.receptorlog.operacao.Contadores;
import com.google.protobuf.InvalidProtocolBufferException;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Endpoint OTLP/HTTP de logs ({@code POST /v1/logs}), o mesmo caminho que os exportadores do OpenTelemetry usam
 * por padrao.
 *
 * <p>Codigos de resposta seguem a especificacao do OTLP, porque e deles que o cliente decide se reenvia:
 * <ul>
 *   <li>200: gravado no buffer local;</li>
 *   <li>400, 413, 415: requisicao invalida, o cliente descarta (nao adianta reenviar);</li>
 *   <li>401: token ausente ou invalido (ver {@link FiltroToken});</li>
 *   <li>403: token valido, mas de outro servico (o {@code service.name} do log nao e o dono do token);</li>
 *   <li>503: falha temporaria (disco, buffer no teto ou receptor desligando), o cliente reenvia com espera.</li>
 * </ul>
 */
@Path("/v1/logs")
public class RecursoOtlpLogs {

    private static final Logger LOG = Logger.getLogger(RecursoOtlpLogs.class);
    static final String PROTOBUF = "application/x-protobuf";

    private final ConversorOtlp conversor;
    private final GravadorSegmentos gravador;
    private final ConfiguracaoReceptor config;
    private final ControleEspaco espaco;
    private final Contadores contadores;
    private final VerificadorToken verificador;

    public RecursoOtlpLogs(ConversorOtlp conversor, GravadorSegmentos gravador, ConfiguracaoReceptor config,
                           ControleEspaco espaco, Contadores contadores, VerificadorToken verificador) {
        this.conversor = conversor;
        this.gravador = gravador;
        this.config = config;
        this.espaco = espaco;
        this.contadores = contadores;
        this.verificador = verificador;
    }

    @POST
    @Consumes(MediaType.WILDCARD)
    public Response receber(@HeaderParam("Content-Type") String tipo,
                            @HeaderParam("Content-Encoding") String codificacao,
                            @HeaderParam("Authorization") String autorizacao,
                            byte[] corpo) {
        Map<String, List<String>> porServico;
        try {
            exigirProtobuf(tipo);
            byte[] mensagem = descomprimir(corpo, codificacao);
            porServico = conversor.converter(ler(mensagem));
            conferirDonoDoToken(autorizacao, porServico);
            reservarEspaco();
            gravar(porServico);
        } catch (Recusa recusa) {
            return recusa.resposta;
        }

        int eventos = porServico.values().stream().mapToInt(List::size).sum();
        contadores.aceita(eventos);
        LOG.debugf("Recebidos %d eventos de %s", eventos, porServico.keySet());
        return Response.ok(ExportLogsServiceResponse.getDefaultInstance().toByteArray(), PROTOBUF).build();
    }

    /** 415 se nao for protobuf. */
    private static void exigirProtobuf(String tipo) throws Recusa {
        if (!PROTOBUF.equals(tipoBase(tipo))) {
            // O exportador Java usa protobuf por padrao. JSON aqui quase sempre e configuracao errada no cliente.
            LOG.warnf("Content-Type nao suportado: %s (use OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf)", tipo);
            throw new Recusa(erro(Response.Status.UNSUPPORTED_MEDIA_TYPE, "Use " + PROTOBUF));
        }
    }

    /** Descomprime o gzip (413 acima do limite, 400 se invalido); 415 para outra codificacao. */
    private byte[] descomprimir(byte[] corpo, String codificacao) throws Recusa {
        byte[] mensagem = corpo == null ? new byte[0] : corpo;
        String codificacaoBase = codificacao == null ? "" : codificacao.trim().toLowerCase(Locale.ROOT);
        if (codificacaoBase.isEmpty() || codificacaoBase.equals("identity")) {
            return mensagem;
        }
        if (!codificacaoBase.equals("gzip")) {
            throw new Recusa(erro(Response.Status.UNSUPPORTED_MEDIA_TYPE,
                    "Content-Encoding nao suportado: " + codificacao));
        }
        try {
            return Descompressor.gunzip(mensagem, config.requisicao().tamanhoMaximoDescomprimidoBytes());
        } catch (Descompressor.LimiteExcedido e) {
            LOG.warn(e.getMessage());
            throw new Recusa(erro(Response.Status.REQUEST_ENTITY_TOO_LARGE, e.getMessage()));
        } catch (IOException e) {
            throw new Recusa(erro(Response.Status.BAD_REQUEST, "gzip invalido"));
        }
    }

    /** 400 se o protobuf for invalido. */
    private static ExportLogsServiceRequest ler(byte[] mensagem) throws Recusa {
        try {
            return ExportLogsServiceRequest.parseFrom(mensagem);
        } catch (InvalidProtocolBufferException e) {
            throw new Recusa(erro(Response.Status.BAD_REQUEST, "Mensagem protobuf invalida"));
        }
    }

    /**
     * 403 se o token for de outro servico. O filtro ja garantiu que o token vale; aqui confere se ele e do servico que
     * esta mandando.
     */
    private void conferirDonoDoToken(String autorizacao, Map<String, List<String>> porServico) throws Recusa {
        if (!verificador.exigido()) {
            return;
        }
        String dono = verificador.servicoDo(autorizacao).orElse("");
        for (String servico : porServico.keySet()) {
            if (!servico.equals(dono)) {
                contadores.recusadaServico();
                LOG.debugf("Token de '%s' usado para mandar log de '%s': 403", dono, servico);
                throw new Recusa(erro(Response.Status.FORBIDDEN,
                        "Este token so pode enviar log de '" + dono + "', e a requisicao tem '" + servico + "'"));
            }
        }
    }

    /** 503 se o buffer estiver no teto so com segmentos abertos. */
    private void reservarEspaco() throws Recusa {
        if (!espaco.liberar()) {
            contadores.recusadaSemEspaco();
            LOG.warn("Buffer no teto so com segmentos abertos: requisicao recusada com 503");
            throw new Recusa(indisponivel("Buffer cheio"));
        }
    }

    /** 503 se o receptor estiver desligando ou o disco falhar (o cliente reenvia). */
    private void gravar(Map<String, List<String>> porServico) throws Recusa {
        try {
            for (Map.Entry<String, List<String>> lote : porServico.entrySet()) {
                gravador.gravar(lote.getKey(), lote.getValue());
            }
        } catch (GravadorSegmentos.Encerrando e) {
            contadores.recusadaEncerrando();
            throw new Recusa(indisponivel("Receptor encerrando"));
        } catch (IOException e) {
            LOG.errorf(e, "Falha ao gravar no buffer local");
            throw new Recusa(indisponivel("Falha temporaria ao gravar"));
        }
    }

    /**
     * Requisicao recusada, com a resposta pronta. Interrompe o {@link #receber} sem um {@code if} por etapa. Sem stack
     * trace: e fluxo normal (cliente mal configurado, disco cheio), nao erro de programa.
     */
    private static final class Recusa extends Exception {
        private final transient Response resposta;

        Recusa(Response resposta) {
            super(null, null, false, false);
            this.resposta = resposta;
        }
    }

    private static String tipoBase(String tipo) {
        if (tipo == null) {
            return "";
        }
        int pontoEVirgula = tipo.indexOf(';');
        return (pontoEVirgula >= 0 ? tipo.substring(0, pontoEVirgula) : tipo).trim().toLowerCase(Locale.ROOT);
    }

    private static Response indisponivel(String mensagem) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .type(MediaType.TEXT_PLAIN)
                .entity(mensagem)
                .build();
    }

    private static Response erro(Response.Status status, String mensagem) {
        return Response.status(status).type(MediaType.TEXT_PLAIN).entity(mensagem).build();
    }
}
