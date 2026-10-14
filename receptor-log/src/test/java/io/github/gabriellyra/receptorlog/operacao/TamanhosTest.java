package io.github.gabriellyra.receptorlog.operacao;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TamanhosTest {

    @Test
    void escolheAUnidade() {
        assertEquals("0 B", Tamanhos.legivel(0));
        assertEquals("1023 B", Tamanhos.legivel(1023));
        assertEquals("3.1 KB", Tamanhos.legivel(3150));
        assertEquals("1.0 GB", Tamanhos.legivel(1073741824L));
        assertEquals("?", Tamanhos.legivel(-1));
    }
}
