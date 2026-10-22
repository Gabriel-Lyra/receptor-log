# Roadmap

Objetivo: guardar no S3, por prazo longo, os logs dos serviços Spring e Quarkus. A observabilidade da empresa continua
recebendo tudo (com retenção curta); o receptor-log é um caminho **paralelo e independente** (ver
[decisões](DECISOES.md)).

```
serviço ──OTLP gRPC──> observabilidade da empresa (poucos dias)    ← já existe, não mexemos
        └─OTLP HTTP──> receptor-log ──> buffer no pod ──> S3 (anos)  ← este repositório
```

Legenda: ✅ concluído · ▶️ próximo · ⬜ pendente

## Fase 1 — Receptor ✅

| # | Passo | Status |
|:--|:--|:--|
| 1.1 | Recebe OTLP/HTTP (protobuf, gzip) e grava NDJSON no padrão ECS, em pacotes já compactados por serviço/data. Fecha com **30 min ou 1 MB compactado** ou na troca de hora. Recupera o pacote interrompido depois de uma queda | ✅ |
| 1.2 | Envio ao S3: mais antigo primeiro, apaga só depois da confirmação, para no primeiro erro, espera 5→60 s, compatível com S3 fora da AWS | ✅ |
| 1.3 | Teto do buffer (1 GB): descarta os prontos mais antigos até 90%; 503 quando só há pacotes abertos; resumo por minuto com "HOUVE PERDA DE LOG" | ✅ |
| 1.4 | Desligamento: 5 s ainda aceitando, até 10 s esperando requisições, envio final em até 12 s | ✅ |
| 1.5 | Token **por serviço**: 401 sem token válido, 403 com token de outro serviço; sem tokens o receptor não sobe | ✅ |

53 testes automáticos. O log e as métricas do próprio receptor vão para a observabilidade da empresa.

## Fase 2 — Serviços clientes

| # | Passo | Status |
|:--|:--|:--|
| 2.1 | Spring: agente Java do OpenTelemetry + **extensão do agente** que soma o envio ao receptor. Aplicar no `modelo-batch-wikipedia` | ▶️ |
| 2.2 | Quarkus: classe `EnvioReceptorLog` soma o envio ao receptor ao envio padrão. Aplicado no `modelo-quarkus-pokemon` (11 testes; os dois destinos recebem os mesmos registros) | ✅ |
| 2.3 | Spring Batch: nome do job, id da execução e nome do step no MDC, para achar uma execução no bucket | ⬜ |
| 2.4 | Descobrir a versão mínima do Quarkus que tem a interface usada (é interna) e retestar a cada atualização | ⬜ |
| 2.5 | Guia de adoção por serviço | ⬜ |

## Fase 3 — Operação

| # | Passo | Status |
|:--|:--|:--|
| 3.1 | Alarme a partir do resumo do receptor ("HOUVE PERDA DE LOG", falhas de envio, token inválido). A falha é **silenciosa** no serviço cliente | ⬜ |
| 3.2 | Teste de carga e dimensionamento (réplicas, CPU, memória, disco) | ⬜ |
| 3.3 | Teste de ponta a ponta automatizado com S3Mock | ⬜ |
| 3.4 | Deploy: Dockerfile, pipeline, Service, ingress, probes, **buffer em `emptyDir`**, tokens e credenciais em secrets. Validar numa imagem de verdade | ⬜ |

## Fase 4 — Consulta

| # | Passo | Status |
|:--|:--|:--|
| 4.1 | Guia de consulta com DuckDB direto no bucket | ⬜ |

## Fase 5 — Dados (último passo, antes de qualquer dado real)

| # | Passo | Status |
|:--|:--|:--|
| 5.1 | Mascaramento central no receptor (documentos, nomes e outros dados pessoais) antes de gravar. **Nenhum dado real antes dele** | ⬜ |
| 5.2 | Lifecycle do bucket: apagar no prazo definido e mover para armazenamento mais barato, se houver. **Confirmar o prazo** | ⬜ |

## Descartado

- Gerar, dentro de cada serviço, o código de gravação de log e envio ao S3: substituído pelo receptor central.
- Ler a fila interna da observabilidade ou mexer no coletor dela: exige o outro time.
- Puxar os logs pela API de consulta da ferramenta de painéis: frágil e invisível para o dono.
- Receptor repassando para a observabilidade da empresa: põe o receptor no caminho crítico de outro time.
- Plugins de banco de dados na ferramenta de painéis: só ajudam a consultar, não a guardar.
- Bean `LogRecordExporter` próprio no Quarkus: **substitui** o envio padrão (testado: a observabilidade recebeu 0 logs).
- OTLP em JSON e gRPC no receptor: os exportadores Java falam HTTP/protobuf.
