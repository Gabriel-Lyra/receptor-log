package io.github.gabriellyra.receptorlog.operacao;

import java.util.Locale;

/** Tamanho legivel para os logs: 512 B, 3.1 KB, 12.4 MB, 1.0 GB. */
public final class Tamanhos {

    private Tamanhos() {
    }

    public static String legivel(long bytes) {
        if (bytes < 0) {
            return "?";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] unidades = {"KB", "MB", "GB", "TB"};
        double valor = bytes;
        int i = -1;
        while (valor >= 1024 && i < unidades.length - 1) {
            valor /= 1024;
            i++;
        }
        return String.format(Locale.ROOT, "%.1f %s", valor, unidades[i]);
    }
}
