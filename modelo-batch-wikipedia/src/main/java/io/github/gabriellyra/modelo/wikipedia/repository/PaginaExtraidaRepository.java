package io.github.gabriellyra.modelo.wikipedia.repository;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.github.gabriellyra.modelo.wikipedia.model.PaginaExtraida;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class PaginaExtraidaRepository {

    private final JdbcClient jdbcClient;

    public void salvar(PaginaExtraida pagina) {
        jdbcClient.sql("""
                INSERT INTO pagina_wikipedia
                    (page_id, titulo, url, idioma, resumo, quantidade_palavras, quantidade_categorias,
                     categorias, tamanho_bytes, ultima_alteracao, id_execucao_job, data_extracao)
                VALUES
                    (:pageId, :titulo, :url, :idioma, :resumo, :quantidadePalavras, :quantidadeCategorias,
                     :categorias, :tamanhoBytes, :ultimaAlteracao, :idExecucaoJob, :dataExtracao)
                """)
                .param("pageId", pagina.pageId())
                .param("titulo", pagina.titulo())
                .param("url", pagina.url())
                .param("idioma", pagina.idioma())
                .param("resumo", pagina.resumo())
                .param("quantidadePalavras", pagina.quantidadePalavras())
                .param("quantidadeCategorias", pagina.quantidadeCategorias())
                .param("categorias", pagina.categorias())
                .param("tamanhoBytes", pagina.tamanhoBytes())
                .param("ultimaAlteracao", pagina.ultimaAlteracao() == null ? null : Timestamp.from(pagina.ultimaAlteracao()))
                .param("idExecucaoJob", pagina.idExecucaoJob())
                .param("dataExtracao", pagina.dataExtracao())
                .update();
    }

    public List<PaginaExtraida> listarMaisRecentes(int limite) {
        return jdbcClient.sql("""
                SELECT id, page_id, titulo, url, idioma, resumo, quantidade_palavras, quantidade_categorias,
                       categorias, tamanho_bytes, ultima_alteracao, id_execucao_job, data_extracao
                  FROM pagina_wikipedia
                 ORDER BY id DESC
                 LIMIT :limite
                """)
                .param("limite", limite)
                .query((rs, n) -> new PaginaExtraida(
                        rs.getLong("id"),
                        rs.getLong("page_id"),
                        rs.getString("titulo"),
                        rs.getString("url"),
                        rs.getString("idioma"),
                        rs.getString("resumo"),
                        rs.getInt("quantidade_palavras"),
                        rs.getInt("quantidade_categorias"),
                        rs.getString("categorias"),
                        rs.getObject("tamanho_bytes", Long.class),
                        rs.getTimestamp("ultima_alteracao") == null ? null : rs.getTimestamp("ultima_alteracao").toInstant(),
                        rs.getObject("id_execucao_job", Long.class),
                        rs.getTimestamp("data_extracao").toLocalDateTime()))
                .list();
    }

    public long contar() {
        return jdbcClient.sql("SELECT COUNT(*) FROM pagina_wikipedia").query(Long.class).single();
    }
}
