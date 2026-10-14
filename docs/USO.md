# Guia de uso

Este guia tem três partes:

1. [Rodar tudo na sua máquina (playground)](#parte-1--rodar-tudo-na-sua-máquina-playground): para ver funcionando.
2. [Ligar o segundo caminho num serviço](#parte-2--ligar-o-segundo-caminho-num-serviço): para usar de verdade.
3. [Referência de configuração](#parte-3--referência-de-configuração): todas as opções do receptor.

Se alguma palavra não fizer sentido, veja o [glossário](#glossário) no fim.

---

## Parte 1 — Rodar tudo na sua máquina (playground)

![O playground: tudo na sua máquina](img/playground.png)

O objetivo é ver, com os próprios olhos, o mesmo log chegando aos dois lugares: num arquivo que imita a observabilidade
da empresa e num S3 de mentira. Leva uns 15 minutos na primeira vez.

### O que você precisa

- **Java 17 ou mais novo** instalado. Para conferir, abra um terminal e digite `java -version`.
- Uns 500 MB livres em disco.
- Nada mais: cada projeto já traz o próprio Maven (os arquivos `mvnw`), que baixa o que for preciso.

> Os comandos abaixo são para **PowerShell (Windows)**. Quem usa Linux ou macOS encontra a versão equivalente no
> quadro "Linux / macOS" de cada passo.

### Passo 0 — Baixar o código e as peças de mentira (uma vez só)

Abra um terminal na pasta onde quer deixar o projeto:

```powershell
git clone https://github.com/Gabriel-Lyra/receptor-log.git
cd receptor-log
$BASE = (Get-Location).Path

# 1) O coletor que imita a observabilidade da empresa (OpenTelemetry Collector)
cd "$BASE\playground"
Invoke-WebRequest -UseBasicParsing -OutFile otelcol.tar.gz `
  -Uri "https://github.com/open-telemetry/opentelemetry-collector-releases/releases/download/v0.137.0/otelcol_0.137.0_windows_amd64.tar.gz"
tar -xzf otelcol.tar.gz otelcol.exe
Remove-Item otelcol.tar.gz
New-Item -ItemType Directory -Force saida | Out-Null

# 2) O S3 de mentira (S3Mock)
Invoke-WebRequest -UseBasicParsing -OutFile s3mock.jar `
  -Uri "https://repo1.maven.org/maven2/com/adobe/testing/s3mock/5.2.3/s3mock-5.2.3-exec.jar"

# 3) Compilar o receptor e o serviço de exemplo
cd "$BASE\receptor-log";           .\mvnw.cmd -q package -DskipTests
cd "$BASE\modelo-quarkus-pokemon"; .\mvnw.cmd -q package -DskipTests
```

<details><summary>Linux / macOS</summary>

```bash
git clone https://github.com/Gabriel-Lyra/receptor-log.git
cd receptor-log && BASE=$(pwd)

cd "$BASE/playground"
# Linux: linux_amd64 · Mac com chip Apple: darwin_arm64 · Mac Intel: darwin_amd64
curl -L -o otelcol.tar.gz \
  https://github.com/open-telemetry/opentelemetry-collector-releases/releases/download/v0.137.0/otelcol_0.137.0_linux_amd64.tar.gz
tar -xzf otelcol.tar.gz otelcol && rm otelcol.tar.gz
mkdir -p saida
curl -L -o s3mock.jar https://repo1.maven.org/maven2/com/adobe/testing/s3mock/5.2.3/s3mock-5.2.3-exec.jar

cd "$BASE/receptor-log"           && ./mvnw -q package -DskipTests
cd "$BASE/modelo-quarkus-pokemon" && ./mvnw -q package -DskipTests
```

Nos passos seguintes, troque `$env:NOME = "valor"` por `export NOME="valor"`, `\` por `/` nos caminhos e
`.\otelcol.exe` por `./otelcol`.
</details>

Agora abra **quatro terminais**. Em cada um, primeiro vá até a pasta do projeto e defina `$BASE`:

```powershell
cd C:\caminho\para\receptor-log      # onde você fez o git clone
$BASE = (Get-Location).Path
```

### Terminal 0 — o S3 de mentira

```powershell
$env:COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS = "logs-demo"
java -jar "$BASE\playground\s3mock.jar"
```

Espere aparecer `Started S3Mock`. Ele cria um bucket chamado `logs-demo`. Tudo o que ele guarda **some quando você
fecha o terminal**, o que é ótimo para testes.

### Terminal 1 — o coletor que imita a empresa

```powershell
cd "$BASE\playground"
.\otelcol.exe --config coletor-corporativo.yaml
```

No lugar de painéis e alertas, ele escreve tudo o que recebe em `playground\saida\corporativo-logs.json`.

### Terminal 2 — o receptor-log

```powershell
cd "$BASE\receptor-log"
$env:PORTA = "8090"
$env:RECEPTOR_SEGMENTO_IDADE_MAXIMA = "10s"   # só no playground; o normal é 30 min
$env:RECEPTOR_S3_BUCKET = "logs-demo"
$env:RECEPTOR_S3_ENDPOINT = "http://127.0.0.1:9090"
$env:RECEPTOR_S3_PREFIXO = "logs"
$env:RECEPTOR_S3_CHAVE_ACESSO = "teste"        # o S3 de mentira aceita qualquer credencial
$env:RECEPTOR_S3_CHAVE_SECRETA = "teste"
$env:RECEPTOR_TOKENS = "modelo-quarkus-pokemon:segredo-playground"
$env:RECEPTOR_RESUMO_INTERVALO = "20s"         # resumo mais frequente, para acompanhar
java -jar target\quarkus-app\quarkus-run.jar
```

O que cada linha faz:

- `PORTA`: a porta em que o receptor escuta.
- `RECEPTOR_SEGMENTO_IDADE_MAXIMA`: de quanto em quanto tempo o pacote fecha. Aqui são 10 segundos para você não
  esperar meia hora.
- `RECEPTOR_S3_*`: onde fica o S3 e qual bucket usar.
- `RECEPTOR_TOKENS`: a lista de quem pode mandar log, no formato `nome-do-serviço:senha`. **Sem ela, o receptor se
  recusa a subir**, de propósito.

### Terminal 3 — o serviço de exemplo

```powershell
cd "$BASE\modelo-quarkus-pokemon"
# Caminho 1: para onde a "empresa" recebe
$env:QUARKUS_OTEL_EXPORTER_OTLP_LOGS_ENDPOINT    = "http://127.0.0.1:4317"
$env:QUARKUS_OTEL_EXPORTER_OTLP_METRICS_ENDPOINT = "http://127.0.0.1:4317"
$env:QUARKUS_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT  = "http://127.0.0.1:4317"
# Caminho 2: o receptor-log, com a senha deste serviço
$env:RECEPTOR_LOG_ENDPOINT = "http://127.0.0.1:8090"
$env:RECEPTOR_LOG_TOKEN = "segredo-playground"
java -jar target\quarkus-app\quarkus-run.jar
```

### Gerar log

Num quinto terminal (ou no navegador), use o serviço de exemplo:

```powershell
Invoke-RestMethod http://localhost:8081/pokemons/1
Invoke-RestMethod -Method Post http://localhost:8081/pokemons -ContentType application/json `
  -Body '{"numeroPokedex":25,"nome":"Outro Pikachu","tipoPrimario":"ELETRICO"}'
```

O segundo comando dá erro **de propósito** (o número 25 já existe), para gerar um log de aviso.

### Conferir os dois caminhos

**Caminho 1:** abra `playground\saida\corporativo-logs.json`. As mensagens do pokemon estão lá.

**Caminho 2:** espere uns 20 segundos e liste o bucket:

```powershell
(Invoke-RestMethod "http://localhost:9090/logs-demo?list-type=2").ListBucketResult.Contents | Select-Object Key, Size
```

Vai aparecer algo como `logs/servico=modelo-quarkus-pokemon/data=2026-10-14/hora=13/....jsonl.gz`. Para ler um arquivo
(troque a chave pela que apareceu):

```powershell
$chave = "logs/servico=modelo-quarkus-pokemon/data=.../hora=.../....jsonl.gz"
Invoke-WebRequest "http://localhost:9090/logs-demo/$chave" -OutFile obj.gz -UseBasicParsing
$gz = New-Object IO.Compression.GZipStream([IO.File]::OpenRead("$PWD\obj.gz"), [IO.Compression.CompressionMode]::Decompress)
(New-Object IO.StreamReader($gz)).ReadToEnd(); $gz.Close()
```

<details><summary>Linux / macOS</summary>

```bash
curl -s "http://localhost:9090/logs-demo?list-type=2" | grep -o '<Key>[^<]*</Key>'
curl -s "http://localhost:9090/logs-demo/<chave>" | gunzip
```
</details>

**Resultado esperado:** as mesmas mensagens nos dois lugares. 🎉

### Testes de "e se der errado?"

Com tudo rodando, experimente:

| Experimento | Como | O que deve acontecer |
|:--|:--|:--|
| S3 cai | Feche o terminal 0 e gere log | O receptor avisa no log e guarda os pacotes no disco (`receptor-log\buffer\prontos\`). Suba o terminal 0 de novo: ele avisa que voltou e envia tudo, em ordem |
| Receptor cai | Feche o terminal 2 e gere log | O pokemon continua normal e o caminho 1 continua recebendo. Só a cópia de longo prazo daquele período se perde |
| Desligar com calma | No terminal 2, use `RECEPTOR_SEGMENTO_IDADE_MAXIMA = "30m"`, gere log e aperte Ctrl+C | O receptor espera uns segundos, fecha o pacote aberto e envia antes de sair ("Envio final: 1 arquivo(s) enviado(s)") |
| Queda brusca | Mesma coisa, mas mate o processo pelo Gerenciador de Tarefas (ou `kill -9`) e suba de novo | Ao subir, ele recupera o pacote que estava aberto ("Segmento de execucao anterior recuperado") e envia |
| Disco cheio | Com o S3 desligado, suba o receptor com `RECEPTOR_BUFFER_TETO_BYTES = "4000"` e `RECEPTOR_SEGMENTO_IDADE_MAXIMA = "1s"` | Aparece o aviso "Buffer no teto" e o resumo "HOUVE PERDA DE LOG" |
| Senha errada | Suba o pokemon com outro `RECEPTOR_LOG_TOKEN` | O pokemon não mostra nada (ele não percebe), mas o resumo do receptor conta "token invalido" |
| Senha de outro serviço | Receptor com `RECEPTOR_TOKENS = "modelo-quarkus-pokemon:a,modelo-batch-wikipedia:b"` e pokemon com `RECEPTOR_LOG_TOKEN = "b"` | Recusado: um serviço não consegue gravar log em nome de outro |
| Log do próprio receptor | Abra `playground\saida\corporativo-logs.json` | Há linhas com `receptor-log` como serviço: é por ali que um alarme veria problemas do receptor |

---

## Parte 2 — Ligar o segundo caminho num serviço

> ⚠️ **Antes de usar com dados reais:** o mascaramento de dados pessoais ainda não existe (é o último passo do
> [roadmap](ROADMAP.md)). Até lá, use só com dados de teste.

### Serviço Quarkus (pronto)

O serviço continua mandando log para a observabilidade da empresa como sempre, e passa a mandar **também** para o
receptor. São três mudanças, todas copiadas do `modelo-quarkus-pokemon`:

**1. Dependências no `pom.xml`** (as versões vêm do Quarkus, não precisa informar):

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-opentelemetry</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
    <exclusions>
        <exclusion>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-exporter-sender-okhttp</artifactId>
        </exclusion>
    </exclusions>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-sender-jdk</artifactId>
</dependency>
```

**2. A classe [`EnvioReceptorLog.java`](../modelo-quarkus-pokemon/src/main/java/io/github/gabriellyra/modelo/pokemon/config/EnvioReceptorLog.java)**:
copie para o seu projeto e ajuste o nome do pacote. Ela acrescenta o segundo envio sem tirar o primeiro.

**3. No `application.properties`:**

```properties
quarkus.otel.logs.enabled=true
receptor-log.endpoint=${RECEPTOR_LOG_ENDPOINT:}
receptor-log.token=${RECEPTOR_LOG_TOKEN:}
%test.quarkus.otel.sdk.disabled=true
```

Depois, no deploy de cada ambiente, defina:

| Variável | Exemplo | Observação |
|:--|:--|:--|
| `RECEPTOR_LOG_ENDPOINT` | `http://receptor-log:8080` | Endereço do receptor. **Vazio desliga o segundo caminho** |
| `RECEPTOR_LOG_TOKEN` | (de um secret) | A senha deste serviço, que precisa estar em `RECEPTOR_TOKENS` no receptor |

E no receptor, acrescente o serviço em `RECEPTOR_TOKENS` (`nome-do-servico:senha`). O nome tem de ser o mesmo de
`quarkus.application.name` do serviço.

**Cuidados:**

- **Não** crie um bean `LogRecordExporter` próprio nem aponte `quarkus.otel.exporter.otlp.logs.endpoint` para o
  receptor: isso **tira** o serviço da observabilidade da empresa.
- A classe usa uma interface interna do Quarkus. A cada atualização do Quarkus, repita o playground para conferir que
  os dois caminhos continuam recebendo.
- Se o receptor estiver fora do ar ou a senha estiver errada, o serviço **não avisa nada**. Quem percebe é o resumo do
  receptor (veja [Operação](#operação)).

### Serviço Spring (ainda não pronto)

Falta escrever a "extensão do agente" que soma o segundo caminho (passo 2.1 do [roadmap](ROADMAP.md)). O
[`receptor-log/README.md`](../receptor-log/README.md#spring-boot-agente-java-sem-mudança-de-código) mostra como
mandar o log de um serviço Spring **só** para o receptor, o que serve para testes.

### Subir o receptor num cluster Kubernetes

O roteiro completo de produção ainda está no roadmap (passo 3.4). O mínimo:

- **Pelo menos 2 réplicas**, atrás de um Service (e de um ingress, se houver serviços em outros clusters).
- **Pasta `buffer/` num volume `emptyDir`**, para que um reinício do container não perca o pacote aberto.
- **`RECEPTOR_TOKENS` e as credenciais do S3 em secrets**, nunca no código.
- **Probes:** readiness em `/q/health/ready` e liveness em `/q/health/live`.
- **Teto do buffer** (`RECEPTOR_BUFFER_TETO_BYTES`) abaixo do limite de disco efêmero do pod, com folga.
- O desligamento cabe nos 30 segundos padrão do Kubernetes; não reduza o `terminationGracePeriodSeconds`.

### Operação

A cada minuto, o receptor escreve uma linha de resumo no próprio log:

```
Resumo: 1520 evento(s) em 48 requisicao(oes); 2 arquivo(s) enviado(s) ao S3 (310.4 KB); fila 0 arquivo(s);
buffer 12.0 KB de 1.0 GB; recusadas: 0 sem espaco, 0 encerrando, 0 token invalido, 0 servico diferente do token;
descartados por falta de espaco: 0 arquivo(s) (0 B)
```

(Na tela, tudo isso sai numa linha só.)

Se algo se perdeu ou foi recusado, a linha sai como **WARN** e contém **"HOUVE PERDA DE LOG"**. É essa frase que o
alarme deve procurar. Como o log do receptor vai para a observabilidade da empresa, dá para criar o alarme lá.

### Consultar os logs guardados

Os arquivos no bucket são texto JSON compactado, uma linha por evento. Um jeito simples é o [DuckDB](https://duckdb.org):

```sql
INSTALL httpfs; LOAD httpfs;
-- configure as credenciais do S3 conforme a documentação do DuckDB
SELECT "@timestamp", "log.level", message
FROM read_json_auto('s3://SEU-BUCKET/logs/servico=modelo-quarkus-pokemon/data=2026-10-14/*/*.jsonl.gz',
                    hive_partitioning = true)
WHERE "log.level" = 'ERROR'
ORDER BY "@timestamp";
```

Um guia completo de consulta está no roadmap (passo 4.1).

---

## Parte 3 — Referência de configuração

Todas as opções do receptor são variáveis de ambiente.

### Essenciais

| Variável | Padrão | Para que serve |
|:--|:--|:--|
| `RECEPTOR_TOKENS` | — | **Obrigatória.** Quem pode mandar log: `servico:senha,servico:senha`. Um serviço pode ter duas senhas ao mesmo tempo, para trocar sem parar nada |
| `RECEPTOR_S3_BUCKET` | vazio | Bucket de destino. **Vazio desliga o envio** (os pacotes ficam no disco) |
| `RECEPTOR_S3_ENDPOINT` | vazio (AWS) | Endereço do S3, se não for o da AWS (ex.: um S3 compatível da empresa) |
| `RECEPTOR_S3_CHAVE_ACESSO` / `RECEPTOR_S3_CHAVE_SECRETA` | vazio | Credencial do S3. Sem as duas, usa a cadeia padrão da AWS (`AWS_ACCESS_KEY_ID` etc.) |

### Pacotes e disco

| Variável | Padrão | Para que serve |
|:--|:--|:--|
| `RECEPTOR_SEGMENTO_IDADE_MAXIMA` | `30m` | Tempo máximo de um pacote aberto. É também o máximo que se perde se o pod morrer de repente |
| `RECEPTOR_SEGMENTO_TAMANHO_MAXIMO_BYTES` | `1048576` (1 MB) | Tamanho máximo do pacote **já compactado** |
| `RECEPTOR_BUFFER_DIRETORIO` | `./buffer` | Pasta onde os pacotes esperam o envio |
| `RECEPTOR_BUFFER_TETO_BYTES` | `1073741824` (1 GB) | Espaço máximo em disco. Acima dele, descarta os pacotes mais antigos |

### S3 (ajuste fino)

| Variável | Padrão | Para que serve |
|:--|:--|:--|
| `RECEPTOR_S3_PREFIXO` | vazio | Pasta inicial dentro do bucket (ex.: `logs`) |
| `RECEPTOR_S3_REGIAO` | `us-east-1` | Região do S3 |
| `RECEPTOR_S3_PATH_STYLE` | `true` | Formato do endereço; S3 compatíveis quase sempre precisam de `true` |
| `RECEPTOR_S3_INTERVALO` | `5s` | De quanto em quanto tempo procura pacotes prontos para enviar |
| `RECEPTOR_S3_PRAZO_ENVIO_FINAL` | `12s` | Tempo máximo para enviar o que sobrou ao desligar |

### Outros

| Variável | Padrão | Para que serve |
|:--|:--|:--|
| `PORTA` | `8080` | Porta HTTP |
| `LOG_LEVEL` | `INFO` | Detalhe do log do próprio receptor (`DEBUG` mostra cada requisição) |
| `RECEPTOR_INSTANCIA` | nome do pod | Entra no nome do arquivo, para duas réplicas nunca gerarem o mesmo nome |
| `RECEPTOR_RESUMO_INTERVALO` | `60s` | Intervalo da linha de resumo |
| `RECEPTOR_ESPERA_DESLIGAMENTO` | `5s` | Tempo em que o receptor avisa que vai sair antes de parar de aceitar |
| `RECEPTOR_TOKEN_OBRIGATORIO` | `true` | `false` só para testes locais sem senha |
| `QUARKUS_OTEL_EXPORTER_OTLP_LOGS_ENDPOINT` / `..._METRICS_ENDPOINT` | `http://localhost:4317` | Para onde vai o log do **próprio** receptor (a observabilidade da empresa) |

### Respostas do receptor

| Código | Significado |
|:--|:--|
| 200 | Recebido e guardado |
| 401 | Sem senha ou senha desconhecida |
| 403 | Senha de outro serviço |
| 400 / 413 / 415 | Conteúdo inválido, grande demais ou em formato errado |
| 503 | Disco cheio ou receptor desligando: o cliente tenta de novo sozinho |

---

## Glossário

- **Log:** registro de texto que um programa escreve sobre o que está fazendo.
- **Observabilidade:** conjunto de ferramentas para ver logs, métricas e rastros de sistemas (painéis, buscas, alertas).
- **S3 / bucket:** serviço de armazenamento de arquivos; o bucket é um "contêiner" de arquivos dentro dele.
- **OpenTelemetry (OTel / OTLP):** padrão aberto para enviar logs e métricas. OTLP é o "idioma" usado no envio.
- **Coletor (collector):** programa que recebe dados de observabilidade e os repassa.
- **Token:** uma senha que o serviço apresenta ao receptor.
- **Pacote / segmento:** um arquivo compactado (`.jsonl.gz`) com muitas linhas de log.
- **Buffer:** a pasta no disco onde os pacotes esperam para ir ao S3.
- **Pod / réplica:** uma cópia do programa rodando no Kubernetes. Duas réplicas são duas cópias iguais dividindo o trabalho.
- **JSON / NDJSON:** formato de texto estruturado. NDJSON é "um JSON por linha".
- **UTC:** horário universal; no horário de Brasília, são 3 horas a menos.
