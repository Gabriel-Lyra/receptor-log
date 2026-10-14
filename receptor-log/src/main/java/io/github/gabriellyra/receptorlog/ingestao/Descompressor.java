package io.github.gabriellyra.receptorlog.ingestao;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/** Descompressao gzip com teto de tamanho, para um corpo pequeno nao virar gigabytes em memoria. */
final class Descompressor {

    private Descompressor() {
    }

    static final class LimiteExcedido extends IOException {
        LimiteExcedido(long limite) {
            super("Corpo descomprimido passou de " + limite + " bytes");
        }
    }

    static byte[] gunzip(byte[] comprimido, long limite) throws IOException {
        try (InputStream entrada = new GZIPInputStream(new ByteArrayInputStream(comprimido), 16 * 1024)) {
            ByteArrayOutputStream saida = new ByteArrayOutputStream(Math.max(1024, comprimido.length * 4));
            byte[] bloco = new byte[16 * 1024];
            long total = 0;
            int lidos;
            while ((lidos = entrada.read(bloco)) != -1) {
                total += lidos;
                if (total > limite) {
                    throw new LimiteExcedido(limite);
                }
                saida.write(bloco, 0, lidos);
            }
            return saida.toByteArray();
        }
    }
}
