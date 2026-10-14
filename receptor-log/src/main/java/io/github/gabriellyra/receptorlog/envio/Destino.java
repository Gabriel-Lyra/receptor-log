package io.github.gabriellyra.receptorlog.envio;

import java.nio.file.Path;

/** Para onde vao os segmentos prontos. Separado do S3 para a logica de envio ser testada sem rede. */
public interface Destino {

    /**
     * Grava o arquivo com a chave informada. So retorna normalmente se o destino confirmou a gravacao;
     * qualquer problema vira excecao.
     */
    void enviar(String chave, Path arquivo) throws Exception;

    /** Texto curto para os logs (ex.: {@code s3://bucket em http://endpoint}). */
    String descricao();
}
