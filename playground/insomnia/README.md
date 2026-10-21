# Testar o receptor na mão (Insomnia, Postman, curl)

O `POST /v1/logs` só aceita OTLP em protobuf (`application/x-protobuf`), então o corpo precisa ser um arquivo binário.
Os arquivos desta pasta já estão prontos.

| Arquivo | Conteúdo |
|:--|:--|
| `log-pokemon.bin` | 3 logs (INFO, WARN e ERROR com exceção) do serviço `modelo-quarkus-pokemon` |
| `log-pokemon.bin.gz` | O mesmo, comprimido (mande com `Content-Encoding: gzip`) |
| `log-outro-servico.bin` | 1 lote do serviço `servico-sem-token`, para testar o 403 |
| `gerar.py` | Recria os arquivos (`pip install opentelemetry-proto`; troque o `service.name` se precisar) |
| `ler.py` | Mostra um `.bin` ou `.bin.gz` como JSON legível: `python ler.py log-pokemon.bin` |

Os `.bin` são protobuf binário: abertos num editor de texto, aparecem cheios de `?`. Para ler, use o `ler.py` ou
`protoc --decode_raw < log-pokemon.bin` (sem nomes de campo, só números).

## Montando a requisição

- `POST <url-do-receptor>/v1/logs`
- Body: **Binary File** (Insomnia) / **binary** (Postman), apontando para `log-pokemon.bin`
- Headers:
  - `Content-Type: application/x-protobuf`
  - `Authorization: Bearer <token do modelo-quarkus-pokemon>`
  - `Content-Encoding: gzip` (só com o `.bin.gz`)

Com curl:

```bash
curl -i -X POST "<url-do-receptor>/v1/logs" \
  -H "Content-Type: application/x-protobuf" \
  -H "Authorization: Bearer <token>" \
  --data-binary @log-pokemon.bin
```

## O que esperar

| Teste | Resposta |
|:--|:--|
| Token certo | 2xx |
| Sem token ou token inválido | 401 |
| `log-outro-servico.bin` com o token do pokemon | 403 |
| `Content-Type` diferente de protobuf | 415 |
| `GET /q/health/ready` | 200 |

## Por que o arquivo não aparece no bucket logo depois do 2xx

O receptor só fecha o segmento com **30 min, 1 MB comprimido ou na troca de hora**. Para ver o arquivo no S3 durante o
teste:

- suba o receptor com `RECEPTOR_SEGMENTO_IDADE_MAXIMA=1m`; **ou**
- desligue o receptor (ou apague o pod) depois do envio: o desligamento fecha o segmento e envia ao S3.

O arquivo cai em `<prefixo>/servico=modelo-quarkus-pokemon/data=AAAA-MM-DD/hora=HH/`, com 3 linhas `teste insomnia`.
A hora da partição é a de **recebimento, em UTC**.
