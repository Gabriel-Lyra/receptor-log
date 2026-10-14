package io.github.gabriellyra.receptorlog.ingestao;

import io.github.gabriellyra.receptorlog.operacao.Contadores;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.util.Optional;

/**
 * Exige {@code Authorization: Bearer <token>} valido em {@code /v1/*} quando {@code RECEPTOR_TOKENS} esta configurado.
 * Se o token e de outro servico, quem recusa (403) e o {@link RecursoOtlpLogs}, que e quem le o {@code service.name}.
 *
 * <ul>
 *   <li>Roda antes de o corpo ser lido: requisicao sem token nao consome memoria nem disco.</li>
 *   <li>Aceita varios tokens, para trocar sem parar os servicos (publica o novo, troca os clientes, tira o antigo).</li>
 *   <li>Health ({@code /q/*}) fica de fora: o Kubernetes nao manda token.</li>
 * </ul>
 */
public class FiltroToken {

    private static final Logger LOG = Logger.getLogger(FiltroToken.class);

    private final VerificadorToken verificador;
    private final Contadores contadores;

    public FiltroToken(VerificadorToken verificador, Contadores contadores) {
        this.verificador = verificador;
        this.contadores = contadores;
    }

    @ServerRequestFilter(preMatching = true)
    public Optional<Response> conferir(ContainerRequestContext requisicao) {
        if (!requisicao.getUriInfo().getPath().startsWith("/v1/") || !verificador.exigido()
                || verificador.servicoDo(requisicao.getHeaderString(HttpHeaders.AUTHORIZATION)).isPresent()) {
            return Optional.empty();
        }
        contadores.recusadaToken();
        LOG.debugf("Requisicao sem token valido em %s", requisicao.getUriInfo().getPath());
        return Optional.of(Response.status(Response.Status.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .type(MediaType.TEXT_PLAIN)
                .entity("Token ausente ou invalido")
                .build());
    }
}
