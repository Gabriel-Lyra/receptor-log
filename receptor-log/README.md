# receptor-log

Serviço Quarkus que recebe os logs dos outros serviços via **OTLP/HTTP** (o protocolo padrão do OpenTelemetry) e os
grava como **NDJSON comprimido, particionado por serviço, data e hora**, pronto para ir ao S3.

Os serviços não sabem que existe S3. Cada um só configura para onde manda o próprio log.

```
serviços (Spring / Quarkus) --OTLP/HTTP, gzip, assíncrono--> receptor-log --> buffer local --> S3
```

Estado atual: **Fase 1 do [ROADMAP](../docs/ROADMAP.md) completa** (1.1 a 1.5): recebe, grava em disco local, envia ao S3, respeita
um teto de disco, desliga sem perder o que está aberto e exige token por serviço.

## Rodar

Requisitos: JDK 17+. O Maven vem pelo wrapper.

```powershell
.\mvnw.cmd package
java -jar target\quarkus-app\quarkus-run.jar
```

Escuta em `http://localhost:8080`. Endpoints:

| Caminho | Uso |
|:--|:--|
| `POST /v1/logs` | Recebe `application/x-protobuf` (com ou sem `Content-Encoding: gzip`) |
| `GET /q/health/ready`, `/q/health/live` | Probes do Kubernetes |

## Configuração

| Variável | Padrão | O que é |
|:--|:--|:--|
| `RECEPTOR_BUFFER_DIRETORIO` | `./buffer` | Onde ficam os segmentos até o envio (disco efêmero do pod) |
| `RECEPTOR_SEGMENTO_IDADE_MAXIMA` | `30m` | Tempo máximo de um segmento aberto. É também o máximo que se perde se o pod morrer de repente |
| `RECEPTOR_SEGMENTO_TAMANHO_MAXIMO_BYTES` | `1048576` (1 MB) | Tamanho máximo do segmento **comprimido** (o arquivo que vai ao bucket) |
| `RECEPTOR_INSTANCIA` | `$HOSTNAME` | Entra no nome do arquivo; no Kubernetes é o nome do pod, o que evita colisão entre réplicas |
| `PORTA` | `8080` | Porta HTTP |
| `LOG_LEVEL` | `INFO` | Nível do log do próprio receptor (`DEBUG` mostra cada requisição) |
| `RECEPTOR_S3_BUCKET` | vazio | Bucket de destino. **Vazio desliga o envio** (os arquivos ficam em `prontos/`) |
| `RECEPTOR_S3_ENDPOINT` | vazio (AWS) | Endpoint do S3 compatível, ex.: `http://localhost:9090` |
| `RECEPTOR_S3_PREFIXO` | vazio | Prefixo das chaves, ex.: `logs` |
| `RECEPTOR_S3_REGIAO` | `us-east-1` | Região assinada nas requisições |
| `RECEPTOR_S3_PATH_STYLE` | `true` | Endereço `endpoint/bucket/chave` (S3 compatível quase sempre exige) |
| `RECEPTOR_S3_CHAVE_ACESSO` / `RECEPTOR_S3_CHAVE_SECRETA` | vazio | Credencial fixa. Sem as duas, usa a cadeia padrão do SDK (`AWS_ACCESS_KEY_ID` etc.) |
| `RECEPTOR_S3_INTERVALO` | `5s` | De quanto em quanto tempo procura arquivos prontos |
| `RECEPTOR_S3_PRAZO_ENVIO_FINAL` | `12s` | Tempo máximo do envio final no desligamento |
| `RECEPTOR_BUFFER_TETO_BYTES` | `1073741824` (1 GB) | Espaço máximo do buffer. Deixe abaixo do ephemeral storage do pod, com folga |
| `RECEPTOR_TOKENS` | — | **Obrigatório.** Tokens por serviço: `servico:token,servico:token`. Sem ele o receptor não sobe |
| `RECEPTOR_TOKEN_OBRIGATORIO` | `true` | `false` só para testar localmente sem token (os perfis dev e test já usam `false`) |
| `QUARKUS_OTEL_EXPORTER_OTLP_LOGS_ENDPOINT` / `..._METRICS_ENDPOINT` | `http://localhost:4317` | Stack corporativa, para o log e as métricas do próprio receptor |
| `RECEPTOR_ESPERA_DESLIGAMENTO` | `5s` | Tempo em que a readiness fica em 503 antes de o receptor parar de aceitar |
| `RECEPTOR_RESUMO_INTERVALO` | `60s` | Intervalo da linha de resumo no log |

O corpo HTTP pode ter até 16 MB comprimido e 64 MB descomprimido. Acima disso, a resposta é 413.

## O que sai

```
buffer/
  abertos/<servico>/<epoch-ms>-<seq>.jsonl.gz       ← sendo gravado (já comprimido)
  prontos/servico=<s>/data=AAAA-MM-DD/hora=HH/<instancia>-<inicio>-<seq>.jsonl.gz
```

O caminho dentro de `prontos/` é a futura chave no bucket. A partição é estilo Hive (`chave=valor`), que DuckDB, Trino
e Athena entendem sem configuração.

Um segmento fecha com o que vier primeiro: **30 min, 1 MB comprimido ou troca de hora**. São no máximo 2 arquivos por
hora por serviço e réplica (48 por dia). Um agendador confere a cada 5 s, então um serviço que parou de logar não deixa
segmento aberto para sempre. O corte acontece depois de gravar o lote que chegou, então o arquivo pode passar do limite
em até um lote (alguns KB).

O segmento é gravado **já em gzip**, com um *sync flush* a cada requisição: o que foi confirmado ao cliente já está no
disco em forma legível, mesmo sem o fim do arquivo. Se o processo morrer (`kill -9`, OOM), o próximo início lê o gzip
interrompido até onde dá, descarta uma eventual linha pela metade e publica o resto. Validado em 2026-10-14: `kill -9`
com segmento aberto, todas as linhas no bucket depois do reinício.

**No Kubernetes, monte o `buffer/` num volume `emptyDir`.** Ele sobrevive a reinício do container (OOM, crash), então
o segmento aberto é recuperado. Se o pod inteiro for removido (nó caindo, despejo), o disco vai junto e perdem-se até
30 min de log daquela réplica.

Cada linha é um evento em JSON, com chaves ECS com ponto:

```json
{"@timestamp":"2026-10-08T01:38:38.807259633Z","log.level":"ERROR","message":"Falha no passo de leitura",
 "service.name":"cliente-spring","service.version":"1.0.0","service.environment":"desenv","host.name":"pod-abc",
 "log.logger":"exemplo.ClienteSpring","process.thread.name":"main","process.thread.id":"1",
 "error.type":"java.lang.IllegalStateException","error.message":"Wikipedia indisponivel",
 "error.stack_trace":"java.lang.IllegalStateException: Wikipedia indisponivel\n\tat ...",
 "labels":{"job.execution.id":"42","job.name":"extracaoWikipedia"}}
```

- `labels` guarda tudo do evento que não tem campo ECS próprio, principalmente o **MDC**, mantendo o tipo do valor.
- `log.level` vem do `severity_number` do OTLP, que é padronizado. O texto do nível varia por framework.
- No Quarkus, o logger real vem no atributo `bridge.name`; o receptor usa ele no `log.logger`.

### Envio ao S3

A cada 5 s, o receptor envia os arquivos de `prontos/` com a chave `[prefixo/]servico=…/data=…/hora=…/<arquivo>.jsonl.gz`:

- **Mais antigo primeiro.** Apaga o arquivo local só depois de o S3 confirmar.
- **Para no primeiro erro** e espera antes de tentar de novo: 5 s, 10 s, 20 s, 40 s, 60 s, 60 s… Loga WARN na primeira
  falha e a cada 10 seguidas, e INFO quando volta a funcionar.
- **Compatível com S3 fora da AWS:** PUT sem `aws-chunked`, checksums só quando a operação exige, path-style por padrão.
- Cliente HTTP do próprio JDK (`url-connection-client`). Apache HttpClient e Netty estão excluídos do SDK.
- Objeto com `Content-Type: application/gzip` e **sem** `Content-Encoding`, para quem baixa não descomprimir duas vezes.
- Pastas vazias em `prontos/` paradas há mais de 10 min são apagadas.

Validado em 2026-10-14 com S3Mock 5.2.3: pokemon → receptor → bucket com as mesmas linhas que a stack corporativa
recebeu. S3 derrubado: um WARN, arquivos retidos, serviço cliente normal. S3 de volta: INFO e fila enviada em ordem.

### Teto do buffer

O disco do pod é efêmero e limitado: se encher, o Kubernetes despeja o pod e perde tudo de uma vez. Antes de cada
gravação, o receptor mede o buffer (no máximo uma vez por segundo):

- **Abaixo do teto:** grava.
- **No teto:** descarta os arquivos prontos **mais antigos** até ficar em 90% do teto, com WARN ("SEM chegar ao S3").
  Isso só acontece se o S3 estiver fora do ar há bastante tempo: perder o mais velho é melhor que parar de receber.
- **Ainda no teto só com segmentos abertos:** responde 503 e o cliente tenta de novo.

### Resumo no log

Uma linha por minuto com eventos recebidos, arquivos enviados, fila, uso do buffer, recusas (sem espaço, encerrando,
token) e descartes. Sai em **WARN com "HOUVE PERDA DE LOG"** se algo foi descartado ou recusado por falta de espaço;
é o que o alarme deve observar. Minuto sem atividade e sem fila não gera linha.

### Desligamento

Cabe nos 30 s padrão do `terminationGracePeriodSeconds`:

1. **5 s** com a readiness em 503: o Kubernetes tira o pod do Service, mas o que ainda chega é aceito.
2. Até **10 s** esperando requisições em andamento.
3. Fecha os segmentos abertos e faz o **envio final** em até 12 s. O que não subir fica no disco (e se perde se o
   pod for removido).

Requisição que chega depois de os segmentos fecharem recebe 503. Validado em 2026-10-14: o processo saiu em 7 s
e o segmento que estava aberto chegou ao bucket.

O cliente envia em lotes (a cada ~1 s). Com **uma réplica só**, o lote que sai no último segundo da espera encontra
o receptor fechado e se perde. Com **2 ou mais réplicas**, o reenvio do cliente deve cair na outra réplica (confirmar
no piloto).

### Token por serviço

`RECEPTOR_TOKENS=modelo-quarkus-pokemon:abc,modelo-batch-wikipedia:def` (num secret). `/v1/logs` exige
`Authorization: Bearer <token>`, e **cada token só manda log com o `service.name` dele**:

- Token ausente ou desconhecido: **401**, conferido antes de ler o corpo (não gasta memória nem disco).
- Token válido, mas o log diz ser de outro serviço: **403**. Um serviço não consegue gravar log em nome de outro.
- Troca sem parada: repita o serviço com o token novo (`pokemon:velho,pokemon:novo`), troque o serviço, tire o velho.
- **Sem `RECEPTOR_TOKENS`, o receptor não sobe.** Só os perfis dev e test (ou `RECEPTOR_TOKEN_OBRIGATORIO=false`)
  aceitam rodar aberto.
- Comparação em tempo constante. `/q/health` não exige token.

O serviço cliente **não mostra** a recusa (401/403) no log dele. Quem enxerga é o resumo do receptor ("token invalido",
"servico diferente do token"), que sai em WARN com "HOUVE PERDA DE LOG".

### Log do próprio receptor

Vai para o console e para a **stack corporativa** (extensão `quarkus-opentelemetry`, com os endpoints
`QUARKUS_OTEL_EXPORTER_OTLP_*_ENDPOINT` definidos no deploy). É lá que o alarme encontra o WARN "HOUVE PERDA DE LOG".
O receptor nunca manda log para si mesmo. Traces desligados: cada POST viraria um trace, ruído para a monitoração.

### Decisões que valem saber

- **Partição em UTC, pela hora de recebimento.** 22h em Brasília (UTC-3) cai em `hora=01` do dia seguinte. O `@timestamp`
  continua com a hora real do evento. Um evento atrasado cai na hora em que chegou.
- **Um fluxo só, sem separar info de debug.** O nível está em cada linha, e filtrar por `log.level` na consulta
  substitui ter um arquivo de INFO e outro de DEBUG, sem duplicar as linhas INFO+.
- **Entrega "pelo menos uma vez".** Se a gravação falhar no meio de uma requisição, o cliente recebe 503 e reenvia, e
  parte das linhas pode aparecer duplicada.
- **O que está em `abertos/` sobrevive a um `kill -9` do processo**, mas não à perda do disco. Ao iniciar, o receptor
  repara e publica o que ficou aberto da execução anterior. No SIGTERM, fecha tudo e envia antes de sair.

### Respostas HTTP

O cliente decide se reenvia a partir do código, conforme a especificação do OTLP:

| Código | Quando | Cliente |
|:--|:--|:--|
| 200 | Gravado no buffer | — |
| 400 | Protobuf ou gzip inválido | Descarta |
| 413 | Corpo grande demais | Descarta |
| 415 | `Content-Type` diferente de protobuf (ex.: JSON) | Descarta |
| 401 | Token ausente ou inválido | Descarta |
| 403 | Token de outro serviço (`service.name` diferente do dono do token) | Descarta |
| 503 | Falha ao gravar no disco, buffer no teto só com abertos, ou receptor desligando | Reenvia com espera |

## Configurar os serviços clientes

Validado em 2026-10-07 com um Spring Boot 3.5 e um Quarkus 3.33 mínimos apontando para o receptor (DEBUG, MDC, exceção
com stack trace e envio no desligamento). Ainda **não** foi validado dentro de uma imagem de produção.

> **Atenção:** a configuração Spring abaixo manda o log **só** para o receptor. Para a segunda via (stack corporativa
> + receptor) falta a extensão do agente (passo 2.1 do ROADMAP).

### Spring Boot: agente Java, sem mudança de código

O agente é um jar do Maven Central. O `pom.xml` copia ele para `target/otel/` no `package`:

```xml
<plugin>
    <artifactId>maven-dependency-plugin</artifactId>
    <executions>
        <execution>
            <id>agente-opentelemetry</id>
            <phase>package</phase>
            <goals><goal>copy</goal></goals>
            <configuration>
                <artifactItems>
                    <artifactItem>
                        <groupId>io.opentelemetry.javaagent</groupId>
                        <artifactId>opentelemetry-javaagent</artifactId>
                        <version>2.32.0</version>
                        <destFileName>opentelemetry-javaagent.jar</destFileName>
                    </artifactItem>
                </artifactItems>
                <outputDirectory>${project.build.directory}/otel</outputDirectory>
            </configuration>
        </execution>
    </executions>
</plugin>
```

Dockerfile:

```dockerfile
# Troque pela imagem base Java da sua empresa, se houver
FROM eclipse-temurin:17-jre
COPY target/*.jar /deployments/app.jar
COPY target/otel/opentelemetry-javaagent.jar /deployments/otel/opentelemetry-javaagent.jar
ENV JAVA_TOOL_OPTIONS="-javaagent:/deployments/otel/opentelemetry-javaagent.jar" \
    OTEL_SERVICE_NAME=nome-do-servico \
    OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf \
    OTEL_EXPORTER_OTLP_COMPRESSION=gzip \
    OTEL_TRACES_EXPORTER=none \
    OTEL_METRICS_EXPORTER=none \
    OTEL_LOGS_EXPORTER=otlp \
    OTEL_INSTRUMENTATION_COMMON_DEFAULT_ENABLED=false \
    OTEL_INSTRUMENTATION_LOGBACK_APPENDER_ENABLED=true \
    OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_MDC_ATTRIBUTES_INCLUDED=* \
    OTEL_INSTRUMENTATION_LOGBACK_APPENDER_EXPERIMENTAL_LOG_ATTRIBUTES=true
ENTRYPOINT ["java", "-jar", "/deployments/app.jar"]
```

Armadilhas:

- O agente fica em `target/otel/` e não em `target/`. Senão o `COPY target/*.jar` pega dois jars e o build da imagem
  falha.
- Se o `.dockerignore` começa com `*`, acrescente `!target/otel/*`.
- `OTEL_EXPORTER_OTLP_ENDPOINT` muda por ambiente: `http://receptor-log:8080` no mesmo cluster ou a URL do ingress em
  outro. Defina pelo mesmo mecanismo que o serviço já usa para variáveis por ambiente, não no Dockerfile.
- Projeto com Log4j2 em vez de Logback: a instrumentação é a `log4j-appender`
  (`OTEL_INSTRUMENTATION_LOG4J_APPENDER_ENABLED=true`). Não foi validado; confira na documentação do agente o nome
  das propriedades de MDC dele.
- O agente envia o que passa pelo nível do logger. Se o serviço está em DEBUG, o DEBUG vai para o receptor.
- Com o receptor fora do ar, o serviço continua funcionando e os logs desse período se perdem. O encerramento do pod
  demora uns 10 s a mais, porque o agente tenta enviar o que sobrou.

### Quarkus: segunda via (validado)

O serviço continua configurado como pede a stack corporativa de observabilidade (exportador OTLP gRPC para o collector
do nó) e **soma** um segundo envio para o receptor com a classe `EnvioReceptorLog`. Exemplo pronto em
`../modelo-quarkus-pokemon` (classe em `config/`, dependências no `pom.xml`, variáveis no README de lá).

```properties
quarkus.otel.logs.enabled=true
receptor-log.endpoint=${RECEPTOR_LOG_ENDPOINT:}
receptor-log.token=${RECEPTOR_LOG_TOKEN:}
%test.quarkus.otel.sdk.disabled=true
```

**Não** aponte `quarkus.otel.exporter.otlp.logs.endpoint` para o receptor nem crie um bean `LogRecordExporter`: os
dois tiram o serviço da stack corporativa. O `service.name` vem de `quarkus.application.name`.

## Testes

```powershell
.\mvnw.cmd test
```

São 53 testes: conversão OTLP→ECS (incluindo o formato real do Quarkus), rotação e recuperação de segmentos, o
endpoint de ponta a ponta com `@QuarkusTest`, a lógica de envio (ordem, falha, espera, prazo, limpeza), o PUT do SDK
de verdade contra um S3 falso em memória, o teto do buffer, a recusa depois do desligamento, o token por serviço
e a recuperação de gzip interrompido.
