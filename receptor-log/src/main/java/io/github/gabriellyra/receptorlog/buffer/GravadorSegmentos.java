package io.github.gabriellyra.receptorlog.buffer;

import io.github.gabriellyra.receptorlog.ConfiguracaoReceptor;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;

/**
 * Grava as linhas recebidas em segmentos locais, um aberto por servico, e fecha cada segmento quando ele fica
 * velho, grande ou muda a hora. Ao fechar, comprime e move para {@code prontos/}, ja no caminho que vai virar
 * a chave no bucket:
 *
 * <pre>
 * buffer/
 *   abertos/&lt;servico&gt;/&lt;epoch-ms&gt;-&lt;seq&gt;.jsonl.gz                    (sendo gravado, ja comprimido)
 *   prontos/servico=&lt;s&gt;/data=AAAA-MM-DD/hora=HH/&lt;instancia&gt;-&lt;inicio&gt;-&lt;seq&gt;.jsonl.gz  (aguardando envio)
 * </pre>
 *
 * <p>O segmento e gravado ja em gzip, para o limite de tamanho valer para o arquivo que vai ao bucket (padrao: fecha
 * com 30 min ou 1 MB comprimido). Cada gravacao termina com um "sync flush" do gzip: o que foi confirmado ao cliente
 * ja esta no disco em forma legivel, mesmo sem o fim do arquivo. Se o processo morrer, o proximo inicio repara o
 * gzip interrompido (ver {@link #recuperarSobras()}).
 *
 * <p>Particao por hora de <b>recebimento</b>, em UTC. Evento atrasado cai na hora em que chegou; o campo
 * {@code @timestamp} de cada linha continua com a hora real do evento.
 *
 * <p>Entrega "pelo menos uma vez": se a gravacao falhar no meio de uma requisicao, o cliente recebe erro e
 * reenvia, e as linhas que chegaram a ser gravadas aparecem duas vezes.
 */
@ApplicationScoped
public class GravadorSegmentos {

    private static final Logger LOG = Logger.getLogger(GravadorSegmentos.class);

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter INICIO =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);
    private static final Pattern NOME_ABERTO = Pattern.compile("(\\d+)-(\\d+)\\.jsonl(\\.gz)?");
    private static final Pattern CARACTER_INVALIDO = Pattern.compile("[^A-Za-z0-9._-]");
    private static final int TAMANHO_MAXIMO_NOME = 100;

    private final ConfiguracaoReceptor config;
    private final Clock relogio;
    private final Path abertos;
    private final Path prontos;
    private final ConcurrentHashMap<String, Object> travas = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SegmentoAberto> segmentos = new ConcurrentHashMap<>();
    private final AtomicLong sequencia = new AtomicLong();
    private volatile boolean encerrado;

    public GravadorSegmentos(ConfiguracaoReceptor config, Clock relogio) {
        this.config = config;
        this.relogio = relogio;
        Path raiz = config.buffer().diretorio().toAbsolutePath().normalize();
        this.abertos = raiz.resolve("abertos");
        this.prontos = raiz.resolve("prontos");
    }

    void iniciar(@Observes StartupEvent evento) throws IOException {
        Files.createDirectories(abertos);
        Files.createDirectories(prontos);
        recuperarSobras();
        LOG.infof("Buffer em %s (segmento: ate %s ou %d bytes)",
                abertos.getParent(), config.segmento().idadeMaxima(), config.segmento().tamanhoMaximoBytes());
    }

    /**
     * Fecha tudo antes de o Quarkus desligar; o envio do que sobrar e responsabilidade de quem consome prontos/.
     * Depois disso, {@link #gravar} recusa com {@link Encerrando} (o recurso responde 503 e o cliente reenvia para
     * outra replica).
     */
    void encerrar(@Observes @Priority(1) ShutdownEvent evento) {
        encerrado = true;
        // Uma gravacao que ja tinha passado da checagem pode abrir um segmento depois da primeira volta.
        for (int volta = 0; volta < 5 && (volta == 0 || !segmentos.isEmpty()); volta++) {
            fecharTodos();
        }
    }

    /** A gravacao chegou depois de o receptor comecar a desligar. */
    public static final class Encerrando extends IOException {
        Encerrando() {
            super("Receptor encerrando");
        }
    }

    /**
     * Acrescenta as linhas ao segmento aberto do servico. Quando retorna, os dados ja foram entregues ao sistema
     * operacional: sobrevivem a uma morte do processo, mas nao a perda do disco efemero.
     */
    public void gravar(String servico, List<String> linhas) throws IOException {
        if (linhas.isEmpty()) {
            return;
        }
        String nome = nomeSeguro(servico);
        synchronized (trava(nome)) {
            if (encerrado) {
                throw new Encerrando();
            }
            Instant agora = relogio.instant();
            SegmentoAberto segmento = segmentos.get(nome);
            if (segmento != null && segmento.venceu(agora)) {
                fechar(nome, segmento);
                segmento = null;
            }
            if (segmento == null) {
                segmento = abrir(nome, agora);
            }
            try {
                segmento.escrever(linhas);
            } catch (IOException e) {
                // Segmento possivelmente com linha pela metade: fecha para nao misturar com o que vier depois.
                fecharSemExcecao(nome, segmento);
                throw e;
            }
            if (segmento.bytesComprimidos() >= config.segmento().tamanhoMaximoBytes()) {
                fechar(nome, segmento);
            }
        }
    }

    /** Fecha os segmentos que passaram da idade ou da hora, mesmo sem chegar log novo. */
    @Scheduled(every = "${receptor.segmento.verificacao:5s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void fecharVencidos() {
        Instant agora = relogio.instant();
        for (String nome : List.copyOf(segmentos.keySet())) {
            synchronized (trava(nome)) {
                SegmentoAberto segmento = segmentos.get(nome);
                if (segmento != null && segmento.venceu(agora)) {
                    fecharSemExcecao(nome, segmento);
                }
            }
        }
    }

    public void fecharTodos() {
        for (String nome : List.copyOf(segmentos.keySet())) {
            synchronized (trava(nome)) {
                SegmentoAberto segmento = segmentos.get(nome);
                if (segmento != null) {
                    fecharSemExcecao(nome, segmento);
                }
            }
        }
    }

    public Path diretorioProntos() {
        return prontos;
    }

    /** Transforma o service.name em nome de diretorio valido e sem caminho relativo. */
    static String nomeSeguro(String servico) {
        String nome = CARACTER_INVALIDO.matcher(servico.trim()).replaceAll("_");
        if (nome.length() > TAMANHO_MAXIMO_NOME) {
            nome = nome.substring(0, TAMANHO_MAXIMO_NOME);
        }
        if (nome.isEmpty() || nome.chars().allMatch(c -> c == '.')) {
            return "_";
        }
        return nome;
    }

    private Object trava(String nome) {
        return travas.computeIfAbsent(nome, n -> new Object());
    }

    private SegmentoAberto abrir(String nome, Instant agora) throws IOException {
        Path diretorio = abertos.resolve(nome);
        Files.createDirectories(diretorio);
        Path arquivo = diretorio.resolve(agora.toEpochMilli() + "-" + sequencia.incrementAndGet() + ".jsonl.gz");
        SegmentoAberto segmento = new SegmentoAberto(arquivo, agora);
        segmentos.put(nome, segmento);
        return segmento;
    }

    private void fecharSemExcecao(String nome, SegmentoAberto segmento) {
        try {
            fechar(nome, segmento);
        } catch (IOException e) {
            LOG.errorf(e, "Falha ao fechar o segmento %s; ele fica em abertos/ e sera recuperado no proximo inicio",
                    segmento.arquivo);
        }
    }

    private void fechar(String nome, SegmentoAberto segmento) throws IOException {
        segmentos.remove(nome, segmento);
        segmento.fecharArquivo();
        if (segmento.linhas == 0) {
            Files.deleteIfExists(segmento.arquivo);
            return;
        }
        mover(segmento.arquivo, destino(nome, segmento.abertoEm));
    }

    private Path destino(String nome, Instant abertoEm) {
        return prontos
                .resolve("servico=" + nome)
                .resolve("data=" + DATA.format(abertoEm))
                .resolve("hora=" + HORA.format(abertoEm))
                .resolve(config.instancia() + "-" + INICIO.format(abertoEm) + "-" + sequencia.incrementAndGet()
                        + ".jsonl.gz");
    }

    /** Move para prontos/ de uma vez (mesmo disco): quem le prontos/ nunca ve arquivo pela metade. */
    private static void mover(Path origem, Path destino) throws IOException {
        Files.createDirectories(destino.getParent());
        try {
            Files.move(origem, destino, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(origem, destino, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Grava o conteudo ja descomprimido como um novo .jsonl.gz em prontos/ (via .tmp + move). */
    private void publicarConteudo(String nome, byte[] conteudo, Instant abertoEm) throws IOException {
        Path destino = destino(nome, abertoEm);
        Files.createDirectories(destino.getParent());
        Path temporario = destino.resolveSibling(destino.getFileName() + ".tmp");
        try (OutputStream saida = new GZIPOutputStream(Files.newOutputStream(temporario), 64 * 1024)) {
            saida.write(conteudo);
        }
        mover(temporario, destino);
    }

    /**
     * Segmentos que ficaram abertos de uma execucao anterior (o processo morreu antes de fecha-los).
     *
     * <p>Um {@code .jsonl.gz} interrompido nao tem o fim do gzip; le-se ate onde da, descarta-se a ultima linha se
     * ficou pela metade e publica-se o resto. Um {@code .jsonl} em texto puro (versao anterior do receptor) e
     * comprimido e publicado.
     */
    private void recuperarSobras() throws IOException {
        List<Path> sobras = new ArrayList<>();
        try (Stream<Path> arquivos = Files.walk(abertos)) {
            arquivos.filter(Files::isRegularFile).forEach(sobras::add);
        }
        for (Path arquivo : sobras) {
            String nome = arquivo.getParent().getFileName().toString();
            String nomeArquivo = arquivo.getFileName().toString();
            Matcher m = NOME_ABERTO.matcher(nomeArquivo);
            Instant abertoEm = m.matches()
                    ? Instant.ofEpochMilli(Long.parseLong(m.group(1)))
                    : Files.getLastModifiedTime(arquivo).toInstant();
            try {
                byte[] conteudo = nomeArquivo.endsWith(".gz") ? lerGzipInterrompido(arquivo) : Files.readAllBytes(arquivo);
                byte[] linhasCompletas = ateUltimaQuebra(conteudo);
                if (linhasCompletas.length > 0) {
                    publicarConteudo(nome, linhasCompletas, abertoEm);
                    LOG.infof("Segmento de execucao anterior recuperado: %s (%d bytes de log)",
                            arquivo, linhasCompletas.length);
                }
                if (linhasCompletas.length < conteudo.length) {
                    LOG.warnf("Segmento %s terminava com uma linha incompleta (%d bytes), descartada",
                            arquivo, conteudo.length - linhasCompletas.length);
                }
                Files.delete(arquivo);
            } catch (IOException e) {
                LOG.errorf(e, "Nao foi possivel recuperar o segmento %s", arquivo);
            }
        }
    }

    /** Le um gzip possivelmente sem o final: devolve tudo o que deu para descomprimir. */
    static byte[] lerGzipInterrompido(Path arquivo) throws IOException {
        ByteArrayOutputStream lido = new ByteArrayOutputStream();
        try (InputStream entrada = new GZIPInputStream(Files.newInputStream(arquivo), 64 * 1024)) {
            byte[] bloco = new byte[64 * 1024];
            int n;
            while ((n = entrada.read(bloco)) > 0) {
                lido.write(bloco, 0, n);
            }
        } catch (EOFException | ZipException interrompido) {
            // Fim do arquivo antes do fim do gzip: o que foi lido ate aqui vale.
        }
        return lido.toByteArray();
    }

    private static byte[] ateUltimaQuebra(byte[] conteudo) {
        int fim = conteudo.length;
        while (fim > 0 && conteudo[fim - 1] != '\n') {
            fim--;
        }
        return fim == conteudo.length ? conteudo : java.util.Arrays.copyOf(conteudo, fim);
    }

    private final class SegmentoAberto {
        final Path arquivo;
        final Instant abertoEm;
        final ContadorBytes contador;
        final Writer escritor;
        long linhas;

        SegmentoAberto(Path arquivo, Instant abertoEm) throws IOException {
            this.arquivo = arquivo;
            this.abertoEm = abertoEm;
            this.contador = new ContadorBytes(
                    Files.newOutputStream(arquivo, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
            // syncFlush=true: cada flush entrega ao disco um trecho de gzip ja legivel.
            this.escritor = new BufferedWriter(new OutputStreamWriter(
                    new GZIPOutputStream(contador, 64 * 1024, true), StandardCharsets.UTF_8), 64 * 1024);
        }

        void escrever(List<String> linhas) throws IOException {
            for (String linha : linhas) {
                escritor.write(linha);
                escritor.write('\n');
            }
            escritor.flush();
            this.linhas += linhas.size();
        }

        /** Tamanho do arquivo comprimido ate agora (o que vai para o bucket, sem o fim do gzip). */
        long bytesComprimidos() {
            return contador.bytes;
        }

        boolean venceu(Instant agora) {
            boolean velho = !agora.isBefore(abertoEm.plus(config.segmento().idadeMaxima()));
            boolean outraHora = !HORA.format(agora).equals(HORA.format(abertoEm))
                    || !DATA.format(agora).equals(DATA.format(abertoEm));
            return velho || outraHora;
        }

        void fecharArquivo() throws IOException {
            escritor.close();
        }
    }

    /** Conta os bytes que chegam ao arquivo (depois da compressao). */
    private static final class ContadorBytes extends FilterOutputStream {
        long bytes;

        ContadorBytes(OutputStream destino) {
            super(destino);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            bytes++;
        }

        @Override
        public void write(byte[] b, int inicio, int tamanho) throws IOException {
            out.write(b, inicio, tamanho);
            bytes += tamanho;
        }
    }
}
