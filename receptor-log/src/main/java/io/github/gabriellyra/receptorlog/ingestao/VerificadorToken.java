package io.github.gabriellyra.receptorlog.ingestao;

import io.github.gabriellyra.receptorlog.ConfiguracaoReceptor;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Tokens por servico ({@code RECEPTOR_TOKENS=servico:token,...}): diz a qual servico um token pertence.
 *
 * <ul>
 *   <li>Sem token configurado, o receptor nao sobe (exceto com {@code receptor.token-obrigatorio=false}, que so os
 *       perfis dev e test usam).</li>
 *   <li>Compara o resumo SHA-256 contra todos os tokens, sem parar no primeiro que bate, para o tempo de resposta
 *       nao revelar quanto do token estava certo.</li>
 *   <li>Separado do {@link FiltroToken} porque o Quarkus cria os filtros HTTP antes de a configuracao estar
 *       disponivel; este bean so e criado no primeiro uso (e no inicio, pelo aviso abaixo).</li>
 * </ul>
 */
@ApplicationScoped
public class VerificadorToken {

    private static final Logger LOG = Logger.getLogger(VerificadorToken.class);
    private static final String PREFIXO = "bearer ";

    record TokenServico(String servico, byte[] resumo) {
    }

    private final List<TokenServico> aceitos;
    private final boolean obrigatorio;

    public VerificadorToken(ConfiguracaoReceptor config) {
        this.obrigatorio = config.tokenObrigatorio();
        List<TokenServico> lidos = new ArrayList<>();
        for (String entrada : config.tokens().orElse(List.of())) {
            String limpa = entrada.strip();
            if (limpa.isEmpty()) {
                continue;
            }
            int separador = limpa.indexOf(':');
            String servico = separador > 0 ? limpa.substring(0, separador).strip() : "";
            String token = separador > 0 ? limpa.substring(separador + 1).strip() : "";
            if (servico.isEmpty() || token.isEmpty()) {
                // Nao loga a entrada: pode ser um token sem o servico na frente.
                throw new IllegalArgumentException(
                        "Entrada invalida em RECEPTOR_TOKENS: use servico:token separados por virgula");
            }
            lidos.add(new TokenServico(servico, resumo(token)));
        }
        this.aceitos = List.copyOf(lidos);
    }

    void verificarNoInicio(@Observes StartupEvent evento) {
        if (aceitos.isEmpty()) {
            if (obrigatorio) {
                throw new IllegalStateException("RECEPTOR_TOKENS vazio: o receptor nao sobe sem token. Formato: "
                        + "RECEPTOR_TOKENS=servico1:token1,servico2:token2 (para testar localmente sem token: "
                        + "RECEPTOR_TOKEN_OBRIGATORIO=false)");
            }
            LOG.warn("Sem token (RECEPTOR_TOKENS vazio e RECEPTOR_TOKEN_OBRIGATORIO=false): qualquer um que alcance "
                    + "o receptor pode enviar log em nome de qualquer servico");
            return;
        }
        TreeSet<String> servicos = new TreeSet<>();
        aceitos.forEach(t -> servicos.add(t.servico()));
        LOG.infof("Token exigido em /v1/logs para %d servico(s): %s", servicos.size(), String.join(", ", servicos));
    }

    public boolean exigido() {
        return !aceitos.isEmpty();
    }

    /** O servico dono do token em {@code Authorization: Bearer <token>}, ou vazio se o token nao vale. */
    public Optional<String> servicoDo(String cabecalho) {
        if (cabecalho == null || cabecalho.length() <= PREFIXO.length()
                || !cabecalho.substring(0, PREFIXO.length()).toLowerCase(Locale.ROOT).equals(PREFIXO)) {
            return Optional.empty();
        }
        byte[] recebido = resumo(cabecalho.substring(PREFIXO.length()).strip());
        String encontrado = null;
        for (TokenServico aceito : aceitos) {
            if (MessageDigest.isEqual(aceito.resumo(), recebido)) {
                encontrado = aceito.servico();
            }
        }
        return Optional.ofNullable(encontrado);
    }

    private static byte[] resumo(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
