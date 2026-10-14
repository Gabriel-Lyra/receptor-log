package io.github.gabriellyra.receptorlog.envio;

import io.github.gabriellyra.receptorlog.buffer.ArquivosProntos;
import io.github.gabriellyra.receptorlog.operacao.Contadores;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Envia os segmentos de {@code prontos/} para o destino.
 *
 * <ul>
 *   <li>Mais antigo primeiro (pela hora em que o segmento ficou pronto).</li>
 *   <li>Apaga o arquivo local so depois de o destino confirmar.</li>
 *   <li>Para no primeiro erro e espera antes de tentar de novo: 5 s, 10 s, 20 s, 40 s, 60 s, 60 s...</li>
 *   <li>Loga WARN na primeira falha e a cada 10 seguidas, e INFO quando o envio volta.</li>
 * </ul>
 *
 * <p>Arquivos {@code .tmp} sao ignorados: o gravador comprime para {@code .tmp} e so depois renomeia, entao
 * so arquivo completo tem o nome final.
 *
 * <p>Entrega "pelo menos uma vez": se o processo morrer entre a confirmacao do destino e a exclusao local, o
 * arquivo sobe de novo na proxima execucao (mesma chave, sobrescreve o objeto).
 */
public final class EnviadorProntos {

    private static final Logger LOG = Logger.getLogger(EnviadorProntos.class);

    private final Path prontos;
    private final Destino destino;
    private final String prefixo;
    private final Clock relogio;
    private final Duration esperaInicial;
    private final Duration esperaMaxima;
    private final Duration idadePastaVazia;
    private final Contadores contadores;

    private int falhasSeguidas;
    private Instant proximaTentativa = Instant.MIN;
    private long enviadosTotal;

    public EnviadorProntos(Path prontos, Destino destino, String prefixo, Clock relogio,
                           Duration esperaInicial, Duration esperaMaxima, Duration idadePastaVazia,
                           Contadores contadores) {
        this.prontos = prontos;
        this.destino = destino;
        this.prefixo = normalizarPrefixo(prefixo);
        this.relogio = relogio;
        this.esperaInicial = esperaInicial;
        this.esperaMaxima = esperaMaxima;
        this.idadePastaVazia = idadePastaVazia;
        this.contadores = contadores;
    }

    /** Uma rodada do agendador: envia o que der (se nao estiver esperando depois de uma falha) e faz limpeza. */
    public synchronized Resultado ciclo() {
        Resultado resultado = relogio.instant().isBefore(proximaTentativa)
                ? new Resultado(0, contarPendentes(), true)
                : enviarPendentes();
        limparPastasVazias();
        return resultado;
    }

    /** Envia tudo o que estiver pronto, ate acabar ou ate a primeira falha. */
    public synchronized Resultado enviarPendentes() {
        return enviarPendentes(Instant.MAX);
    }

    /**
     * Envia o que estiver pronto ate acabar, ate a primeira falha ou ate o prazo (usado no desligamento). Ignora a
     * espera de falhas anteriores: quem chama decide quando tentar.
     */
    public synchronized Resultado enviarPendentes(Instant prazo) {
        List<ArquivosProntos.Arquivo> pendentes = listarPendentes();
        int enviados = 0;
        for (ArquivosProntos.Arquivo arquivo : pendentes) {
            if (!relogio.instant().isBefore(prazo)) {
                return new Resultado(enviados, pendentes.size() - enviados, true);
            }
            String chave = chave(arquivo.caminho());
            try {
                destino.enviar(chave, arquivo.caminho());
                Files.deleteIfExists(arquivo.caminho());
            } catch (NoSuchFileException sumiu) {
                // Descartado pelo controle de espaco enquanto esperava na fila: nada a enviar.
                continue;
            } catch (Exception e) {
                registrarFalha(chave, pendentes.size() - enviados, e);
                return new Resultado(enviados, pendentes.size() - enviados, true);
            }
            enviados++;
            enviadosTotal++;
            if (contadores != null) {
                contadores.enviado(arquivo.bytes());
            }
            if (falhasSeguidas > 0) {
                LOG.infof("Envio para %s voltou a funcionar depois de %d falha(s) seguida(s); %d arquivo(s) na fila",
                        destino.descricao(), falhasSeguidas, pendentes.size() - enviados);
                falhasSeguidas = 0;
                proximaTentativa = Instant.MIN;
            }
            LOG.debugf("Enviado %s", chave);
        }
        return new Resultado(enviados, 0, false);
    }

    public synchronized int falhasSeguidas() {
        return falhasSeguidas;
    }

    public synchronized long enviadosTotal() {
        return enviadosTotal;
    }

    /** Chave no bucket: prefixo + caminho relativo a prontos/, sempre com "/". */
    String chave(Path arquivo) {
        StringBuilder chave = new StringBuilder(prefixo);
        Path relativo = prontos.relativize(arquivo);
        for (int i = 0; i < relativo.getNameCount(); i++) {
            if (i > 0) {
                chave.append('/');
            }
            chave.append(relativo.getName(i));
        }
        return chave.toString();
    }

    private void registrarFalha(String chave, int naFila, Exception e) {
        falhasSeguidas++;
        Duration espera = espera(falhasSeguidas);
        proximaTentativa = relogio.instant().plus(espera);
        if (falhasSeguidas == 1 || falhasSeguidas % 10 == 0) {
            LOG.warnf("Falha ao enviar %s para %s (%d falha(s) seguida(s); %d arquivo(s) na fila). "
                            + "Nova tentativa em %d s. Causa: %s",
                    chave, destino.descricao(), falhasSeguidas, naFila, espera.toSeconds(), mensagem(e));
        }
        LOG.debugf(e, "Detalhe da falha ao enviar %s", chave);
    }

    /** 5 s na primeira falha, dobrando ate o maximo. */
    Duration espera(int falhas) {
        Duration espera = esperaInicial;
        for (int i = 1; i < falhas && espera.compareTo(esperaMaxima) < 0; i++) {
            espera = espera.multipliedBy(2);
        }
        return espera.compareTo(esperaMaxima) > 0 ? esperaMaxima : espera;
    }

    private List<ArquivosProntos.Arquivo> listarPendentes() {
        try {
            return ArquivosProntos.doMaisAntigo(prontos);
        } catch (IOException e) {
            LOG.errorf(e, "Nao foi possivel listar %s", prontos);
            return List.of();
        }
    }

    private int contarPendentes() {
        return listarPendentes().size();
    }

    /**
     * Apaga pastas vazias de horas que ja passaram. A idade minima evita apagar uma pasta que o gravador acabou
     * de criar e ainda vai usar.
     */
    void limparPastasVazias() {
        Instant limite = relogio.instant().minus(idadePastaVazia);
        List<Path> pastas;
        try (Stream<Path> caminhos = Files.walk(prontos)) {
            pastas = caminhos.filter(Files::isDirectory)
                    .filter(p -> !p.equals(prontos))
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            return;
        }
        for (Path pasta : pastas) {
            try (Stream<Path> conteudo = Files.list(pasta)) {
                if (conteudo.findAny().isPresent()) {
                    continue;
                }
            } catch (IOException e) {
                continue;
            }
            try {
                if (Files.getLastModifiedTime(pasta).toInstant().isBefore(limite)) {
                    Files.delete(pasta);
                }
            } catch (DirectoryNotEmptyException | NoSuchFileException corrida) {
                // O gravador usou a pasta nesse meio tempo, ou outra limpeza ja apagou.
            } catch (IOException e) {
                LOG.debugf(e, "Nao foi possivel apagar a pasta vazia %s", pasta);
            }
        }
    }

    private static String normalizarPrefixo(String prefixo) {
        if (prefixo == null) {
            return "";
        }
        String limpo = prefixo.strip().replace('\\', '/');
        while (limpo.startsWith("/")) {
            limpo = limpo.substring(1);
        }
        while (limpo.endsWith("/")) {
            limpo = limpo.substring(0, limpo.length() - 1);
        }
        return limpo.isEmpty() ? "" : limpo + "/";
    }

    private static String mensagem(Throwable e) {
        String texto = e.getMessage();
        return e.getClass().getSimpleName() + (texto == null ? "" : ": " + texto.lines().findFirst().orElse(""));
    }

    /**
     * Resultado de uma rodada.
     *
     * @param enviados  arquivos enviados nesta rodada
     * @param naFila    arquivos que ficaram para depois
     * @param esperando true se parou por falha ou esta aguardando a espera de uma falha anterior
     */
    public record Resultado(int enviados, int naFila, boolean esperando) {
    }
}
