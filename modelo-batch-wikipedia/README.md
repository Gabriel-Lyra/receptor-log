# modelo-batch-wikipedia

Projeto modelo em **Spring Boot 3.5 + Spring Batch 5.2** (Java 17). A cada 5 minutos o job `extracaoWikipediaJob`
busca páginas aleatórias da Wikipedia em português, extrai dados e grava no H2 em memória.

## Fluxo

```
@Scheduled (cron, padrão 0 */5 * * * *)  ou  POST /api/jobs/extracao-wikipedia
  └─ extracaoWikipediaJob
       └─ extrairPaginasStep (chunk)
            reader     PaginaAleatoriaReader     -> API do MediaWiki (generator=random), N páginas por execução
            processor  PaginaWikipediaProcessor  -> descarta desambiguação/sem resumo, normaliza texto, extrai dados
            writer     PaginaExtraidaWriter      -> INSERT em pagina_wikipedia
```

Dados extraídos por página: `pageId`, título, URL, idioma, resumo (introdução em texto puro, truncado em 1000 caracteres),
quantidade de palavras do resumo, categorias visíveis, tamanho em bytes, data da última alteração e id da execução do job.

Falhas na chamada à Wikipedia (HTTP 4xx/5xx, timeout) viram `WikipediaIndisponivelException`, que é *skippable*:
a página é descartada com `WARN`. Passando de `wikipedia.limite-falhas` falhas na mesma execução, o step falha e o listener loga `ERROR`.

## Executar

```bash
./mvnw spring-boot:run          # Linux/macOS/Git Bash
mvnw.cmd spring-boot:run        # Windows (cmd/PowerShell)
```

| Recurso | URL |
|:--|:--|
| Disparar o job agora (síncrono) | `POST http://localhost:8080/api/jobs/extracao-wikipedia` |
| Últimas páginas extraídas | `GET http://localhost:8080/api/paginas?limite=20` |
| Console do H2 | `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:mem:modelo-batch-wikipedia`, usuário `sa`, sem senha) |
| Health | `http://localhost:8080/actuator/health` |

## Variáveis de ambiente

| Variável | Padrão | Descrição |
|:--|:--|:--|
| `EXTRACAO_WIKIPEDIA_CRON` | `0 */5 * * * *` | Cron do Spring (com segundos) |
| `AGENDAMENTO_HABILITADO` | `true` | Liga/desliga o agendamento |
| `WIKIPEDIA_PAGINAS_POR_EXECUCAO` | `5` | Páginas buscadas por execução |
| `WIKIPEDIA_BASE_URL` | `https://pt.wikipedia.org` | Troque para `https://en.wikipedia.org` etc. |
| `WIKIPEDIA_USER_AGENT` | `modelo-batch-wikipedia/1.0 (...)` | A [política da Wikimedia](https://meta.wikimedia.org/wiki/User-Agent_policy) pede um User-Agent com contato |
| `LOG_LEVEL` / `LOG_LEVEL_ROOT` | `INFO` / `INFO` | Nível de log do pacote da aplicação / root |
| `SERVER_PORT` | `8080` | Porta HTTP |

Para ver os logs de DEBUG: `LOG_LEVEL=DEBUG ./mvnw spring-boot:run`.

## Testes

```bash
./mvnw verify
```

- `PaginaWikipediaProcessorTest`: regras de extração (normalização, truncamento, desambiguação, sem resumo)
- `ExtracaoWikipediaJobTest`: job completo contra WireMock (sucesso e Wikipedia respondendo 429)
