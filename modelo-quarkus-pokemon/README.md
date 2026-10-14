# modelo-quarkus-pokemon

Projeto modelo em **Quarkus 3.33 (LTS)** (Java 17). API REST de pokémons com inserção, atualização, busca e
**exclusão lógica**, persistida em H2 em memória e populada com 7 pokémons no startup (`import.sql`).

## Endpoints

| Método | Rota | Resposta |
|:--|:--|:--|
| `POST` | `/pokemons` | `201` + `Location`; `409` se já existe pokémon **ativo** com o mesmo `numeroPokedex`; `400` se inválido |
| `PUT` | `/pokemons/{id}` | `200`; `404` se não existe ou foi excluído |
| `GET` | `/pokemons/{id}` | `200`; `404` se não existe ou foi excluído |
| `GET` | `/pokemons?nome=char&tipo=FOGO` | Lista apenas ativos. `nome` filtra por trecho; `tipo` casa com tipo primário ou secundário |
| `DELETE` | `/pokemons/{id}` | `204`. Marca `ativo=false` e preenche `excluido_em`; o registro continua na tabela |

Tipos aceitos: `NORMAL, FOGO, AGUA, GRAMA, ELETRICO, GELO, LUTADOR, VENENOSO, TERRESTRE, VOADOR, PSIQUICO, INSETO, PEDRA, FANTASMA, DRAGAO, SOMBRIO, METALICO, FADA`.

Exemplo de corpo para `POST`/`PUT`:

```json
{ "numeroPokedex": 143, "nome": "Snorlax", "tipoPrimario": "NORMAL", "tipoSecundario": null, "alturaMetros": 2.1, "pesoKg": 460 }
```

O número de um pokémon excluído pode ser reutilizado, porque a unicidade vale só entre ativos.

## Executar

```bash
./mvnw quarkus:dev              # Linux/macOS/Git Bash
mvnw.cmd quarkus:dev            # Windows
```

Ou empacotado: `./mvnw package` e `java -jar target/quarkus-app/quarkus-run.jar`.

| Recurso | URL |
|:--|:--|
| API | `http://localhost:8081/pokemons` |
| Swagger UI | `http://localhost:8081/q/swagger-ui` |
| Health | `http://localhost:8081/q/health` |

## Variáveis de ambiente

| Variável | Padrão | Descrição |
|:--|:--|:--|
| `SERVER_PORT` | `8081` | Porta HTTP (8081 para não colidir com o modelo batch) |
| `LOG_LEVEL` / `LOG_LEVEL_ROOT` | `INFO` / `INFO` | Nível de log do pacote da aplicação / root |
| `LOG_SQL` | `false` | Loga o SQL gerado pelo Hibernate |

## Testes

```bash
./mvnw verify
```

`PokemonResourceTest` (11 testes `@QuarkusTest` + RestAssured): carga inicial, filtros, CRUD, conflito de número,
validação, exclusão lógica (inclusive conferindo no banco que o registro continua lá com `ativo=false`) e reutilização de número.

Nos testes, a telemetria fica desligada (`%test.quarkus.otel.sdk.disabled=true`) para não tentar falar com um collector
que não existe.

## Logs: stack corporativa + segunda via

O serviço manda o mesmo log para dois lugares:

1. **Stack corporativa de observabilidade** (7 dias): extensão `quarkus-opentelemetry`, configurada como pede a doc
   corporativa, com os endpoints `QUARKUS_OTEL_EXPORTER_OTLP_{LOGS,METRICS,TRACES}_ENDPOINT` no `values.yaml`.
2. **receptor-log** (prazo longo, S3): a classe `config/EnvioReceptorLog.java` soma um segundo envio OTLP/HTTP ao do
   Quarkus, sem tirar o primeiro.

| Variável | Padrão | Descrição |
|:--|:--|:--|
| `RECEPTOR_LOG_ENDPOINT` | vazio | URL do receptor-log (ex.: `http://receptor-log:8080`). **Vazio desliga a segunda via** |
| `RECEPTOR_LOG_TOKEN` | vazio | Token enviado como `Authorization: Bearer`; tem de estar em `RECEPTOR_TOKENS` do receptor |

Dependências extras no `pom.xml`: `opentelemetry-exporter-otlp` (sem o sender okhttp) e `opentelemetry-exporter-sender-jdk`.

Cuidados:

- **Não** crie um bean `LogRecordExporter` próprio: ele substitui o exportador do Quarkus e o serviço some da stack
  corporativa.
- A interface `AutoConfiguredOpenTelemetrySdkBuilderCustomizer` é interna do Quarkus. Repita o teste dos dois destinos
  a cada upgrade (roteiro no [guia de uso](../docs/USO.md)).
- Com o receptor fora do ar ou com token errado, o serviço segue normal e a falha não aparece no log (só em DEBUG).
  Quem enxerga é o resumo do receptor.
