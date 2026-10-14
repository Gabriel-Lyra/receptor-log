CREATE TABLE IF NOT EXISTS pagina_wikipedia (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    page_id                BIGINT       NOT NULL,
    titulo                 VARCHAR(500) NOT NULL,
    url                    VARCHAR(1000),
    idioma                 VARCHAR(20),
    resumo                 CHARACTER VARYING,
    quantidade_palavras    INT,
    quantidade_categorias  INT,
    categorias             CHARACTER VARYING,
    tamanho_bytes          BIGINT,
    ultima_alteracao       TIMESTAMP WITH TIME ZONE,
    id_execucao_job        BIGINT,
    data_extracao          TIMESTAMP    NOT NULL
);
