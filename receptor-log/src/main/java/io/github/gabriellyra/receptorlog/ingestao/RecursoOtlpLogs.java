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
        String tipoBase = tipoBase(tipo);
        if (!PROTOBUF.equals(tipoBase)) {
            // O exportador Java usa protobuf por padrao. JSON aqui quase sempre e configuracao errada no cliente.
            LOG.warnf("Content-Type nao suportado: %s (use OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf)", tipo);
            return erro(Response.Status.UNSUPPORTED_MEDIA_TYPE, "Use " + PROTOBUF);
        }

        byte[] mensagem = corpo == null ? new byte[0] : corpo;
        String codificacaoBase = codificacao == null ? "" : codificacao.trim().toLowerCase(Locale.ROOT);
        if (codificacaoBase.equals("gzip")) {
            try {
                mensagem = Descompressor.gunzip(mensagem, config.requisicao().tamanhoMaximoDescomprimidoBytes());
            } catch (Descompressor.LimiteExcedido e) {
                LOG.warn(e.getMessage());
                return erro(Response.Status.REQUEST_ENTITY_TOO_LARGE, e.getMessage());
            } catch (IOException e) {
                return erro(Response.Status.BAD_REQUEST, "gzip invalido");
            }
        } else if (!codificacaoBase.isEmpty() && !codificacaoBase.equals("identity")) {
            return erro(Response.Status.UNSUPPORTED_MEDIA_TYPE, "Content-Encoding nao suportado: " + codificacao);
        }

        ExportLogsServiceRequest requisicao;
        try {
            requisicao = ExportLogsServiceRequest.parseFrom(mensagem);
        } catch (InvalidProtocolBufferException e) {
            return erro(Response.Status.BAD_REQUEST, "Mensagem protobuf invalida");
        }

        Map<String, List<String>> porServico = conversor.converter(requisicao);
        if (verificador.exigido()) {
            // O filtro ja garantiu que o token vale; aqui confere se ele e do servico que esta mandando.
            String dono = verificador.servicoDo(autorizacao).orElse("");
            for (String servico : porServico.keySet()) {
                if (!servico.equals(dono)) {
                    contadores.recusadaServico();
                    LOG.debugf("Token de '%s' usado para mandar log de '%s': 403", dono, servico);
                    return erro(Response.Status.FORBIDDEN,
                            "Este token so pode enviar log de '" + dono + "', e a requisicao tem '" + servico + "'");
                }
            }
        }
        if (!espaco.liberar()) {
            contadores.recusadaSemEspaco();
            LOG.warn("Buffer no teto so com segmentos abertos: requisicao recusada com 503");
            return indisponivel("Buffer cheio");
        }
        try {
            for (Map.Entry<String, List<String>> lote : porServico.entrySet()) {
                gravador.gravar(lote.getKey(), lote.getValue());
            }
        } catch (GravadorSegmentos.Encerrando e) {
            contadores.recusadaEncerrando();
            return indisponivel("Receptor encerrando");
        } catch (IOException e) {
            LOG.errorf(e, "Falha ao gravar no buffer local");
            return indisponivel("Falha temporaria ao gravar");
        }
        contadores.aceita(porServico.values().stream().mapToInt(List::size).sum());

        if (LOG.isDebugEnabled()) {
            LOG.debugf("Recebidos %d eventos de %s",
                    porServico.values().stream().mapToInt(List::size).sum(), porServico.keySet());
        }
        return Response.ok(ExportLogsServiceResponse.getDefaultInstance().toByteArray(), PROTOBUF).build();
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
