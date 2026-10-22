# Decisões

Este arquivo explica **por que** o projeto é do jeito que é. Cada decisão diz o que foi escolhido, o motivo, o que
ficou de fora e o preço que se aceitou pagar. Quando uma decisão mudar, atualize aqui em vez de apagar: o histórico
ajuda quem chega depois.

**Quem decidiu:**

- **Dono do projeto:** escolha feita explicitamente pelo responsável (Gabriel).
- **Proposta aprovada:** proposta técnica feita durante o desenvolvimento, revisada e aprovada pelo dono do projeto.

| # | Decisão | Quem decidiu |
|:--|:--|:--|
| [D01](#d01--um-segundo-caminho-independente) | Um segundo caminho, independente da observabilidade da empresa | Dono do projeto |
| [D02](#d02--um-receptor-central-em-vez-de-cada-serviço-gravar-no-s3) | Um receptor central em vez de cada serviço gravar no S3 | Dono do projeto |
| [D03](#d03--o-receptor-é-um-serviço-java-comum) | O receptor é um serviço Java comum | Proposta aprovada |
| [D04](#d04--o-idioma-do-envio-é-o-opentelemetry-otlphttp) | O "idioma" do envio é o OpenTelemetry (OTLP/HTTP) | Proposta aprovada |
| [D05](#d05--no-quarkus-somar-um-envio-nunca-substituir) | No Quarkus, somar um envio, nunca substituir | Proposta aprovada |
| [D06](#d06--formato-uma-linha-json-por-evento-tudo-junto) | Formato: uma linha JSON por evento, tudo junto | Proposta aprovada |
| [D07](#d07--organização-no-bucket-por-serviço-e-dia-em-utc) | Organização no bucket por serviço e dia, em UTC | Proposta aprovada |
| [D08](#d08--pacotes-de-30-minutos-ou-1-mb) | Pacotes de 30 minutos ou 1 MB | **Dono do projeto** |
| [D09](#d09--gravar-já-compactado-e-recuperar-depois-de-uma-queda) | Gravar já compactado e recuperar depois de uma queda | Proposta aprovada |
| [D10](#d10--envio-ao-s3-em-ordem-e-só-apagar-depois-da-confirmação) | Envio ao S3 em ordem, só apagando depois da confirmação | Proposta aprovada |
| [D11](#d11--teto-de-disco-perder-o-mais-antigo-para-não-parar) | Teto de disco: perder o mais antigo para não parar | Proposta aprovada |
| [D12](#d12--pode-duplicar-não-pode-perder-calado) | Pode duplicar, não pode perder calado | Proposta aprovada |
| [D13](#d13--desligar-sem-perder-o-que-está-aberto) | Desligar sem perder o que está aberto | Proposta aprovada |
| [D14](#d14--token-obrigatório) | Token obrigatório | **Dono do projeto** |
| [D15](#d15--um-token-por-serviço) | Um token por serviço | **Dono do projeto** |
| [D16](#d16--o-log-do-receptor-vai-para-a-observabilidade-da-empresa) | O log do receptor vai para a observabilidade da empresa | **Dono do projeto** |
| [D17](#d17--alarme-pelo-resumo-do-receptor) | Alarme pelo resumo do receptor | Proposta aprovada |
| [D18](#d18--mascarar-dados-pessoais-é-o-último-passo) | Mascarar dados pessoais é o último passo | **Dono do projeto** |
| [D19](#d19--prazo-de-guarda-ainda-em-aberto) | Prazo de guarda ainda em aberto | Em aberto |
| [D20](#d20--pelo-menos-duas-réplicas) | Pelo menos duas réplicas | Proposta aprovada |

---

## D01 — Um segundo caminho, independente

**Decisão:** cada serviço manda o mesmo log para dois lugares: a observabilidade da empresa (como sempre) e o
receptor-log (novo). Um não sabe do outro.

**Por quê:** a observabilidade da empresa guarda poucos dias e pertence a outro time, com quem não há canal direto.
Qualquer solução que exigisse mudar a ferramenta deles ficaria parada esperando.

**O que ficou de fora:**

- Ler da fila interna que alimenta a observabilidade: o dono é outro time.
- Mudar o coletor da empresa para mandar uma cópia: também exige o outro time.
- Puxar os logs pela API de consulta antes de expirarem: frágil (se falhar um dia, perde-se o dia) e invisível para
  o dono da ferramenta.
- Fazer o receptor repassar para a observabilidade da empresa: colocaria um componente nosso no caminho crítico deles.
- Plugins de banco de dados na ferramenta de painéis: ajudam a consultar, não a guardar.

**Preço:** o time passa a operar um componente a mais (o receptor).

## D02 — Um receptor central em vez de cada serviço gravar no S3

**Decisão:** os serviços só "falam" com o receptor. Só o receptor conhece o S3.

**Por quê:** a primeira tentativa do projeto gerava, dentro de cada serviço, o código para gravar arquivos de log e
enviá-los ao S3. Isso espalhava credenciais do S3 por todos os serviços, repetia a mesma lógica em cada um e tornava
qualquer correção uma mudança em dezenas de lugares. Essa abordagem foi descontinuada.

**Preço:** se o receptor cair, a cópia de longo prazo daquele período se perde (os serviços não guardam nada).

## D03 — O receptor é um serviço Java comum

**Decisão:** o receptor é escrito em Java (Quarkus), como os outros serviços do time.

**Por quê:** o ambiente de deploy só aceita imagens de serviços Java construídas pelo processo padrão. Ferramentas
prontas de coleta (Fluent Bit, Vector, OpenTelemetry Collector) e formatos como DaemonSet ou sidecar não são
permitidos.

**Preço:** mais código para manter do que usar uma ferramenta pronta.

## D04 — O "idioma" do envio é o OpenTelemetry (OTLP/HTTP)

**Decisão:** os serviços mandam o log usando o padrão aberto OpenTelemetry, por HTTP, em formato binário (protobuf)
compactado.

**Por quê:** é o mesmo padrão que a observabilidade da empresa já usa, então os serviços já têm as bibliotecas. Para
Spring existe um agente pronto. Os exportadores Java falam HTTP/protobuf nativamente.

**O que ficou de fora:** aceitar JSON ou gRPC no receptor (ninguém precisa hoje).

## D05 — No Quarkus, somar um envio, nunca substituir

**Decisão:** uma pequena classe (`EnvioReceptorLog`) acrescenta o envio ao receptor sem mexer no envio padrão.

**Por quê:** a forma "óbvia", criar um exportador próprio, **substitui** o da empresa. Isso foi testado: a
observabilidade da empresa recebeu zero logs.

**Preço:** a classe usa uma interface interna do Quarkus, que pode mudar numa atualização. Por isso o teste dos dois
caminhos deve ser repetido a cada atualização do Quarkus.

## D06 — Formato: uma linha JSON por evento, tudo junto

**Decisão:** cada evento vira uma linha JSON com nomes de campo padronizados (padrão ECS: `@timestamp`, `log.level`,
`message`…). Informação extra do evento (como o MDC) vai em `labels`. Não há arquivos separados para INFO e DEBUG.

**Por quê:** JSON por linha é lido por quase qualquer ferramenta. Separar por nível duplicaria as linhas e complicaria
a consulta; filtrar por `log.level` resolve.

## D07 — Organização no bucket por serviço e dia, em UTC

**Decisão:** `servico=X/data=AAAA-MM-DD/<inicio>-<instancia>-<seq>.jsonl.gz`, usando a **hora em que o log chegou**,
em **UTC**. O nome começa pelo início do pacote (`20261022T190000123Z`).

**Por quê:** `servico=` e `data=` seguem o formato "partição Hive", entendido sem configuração por DuckDB, Athena e
Trino, que leem só as pastas necessárias. Com o nome começando pela data e hora, a lista de um dia sai em ordem
cronológica, e uma hora se acha pelo prefixo do nome (`data=2026-10-22/20261022T19*`), que o S3 lista tão rápido quanto
uma pasta. UTC evita confusão com horário de verão.

**Antes (até 2026-10-22):** havia também uma pasta `hora=HH/`. Ela foi tirada porque gerava 24 pastas por dia com no
máximo 2 arquivos por réplica cada, poluindo o console. Também não foi para o outro extremo (tudo direto em
`servico=X/`): seriam ~35 mil arquivos por ano numa pasta só, com 2 réplicas.

**Preço:** 22h em Brasília aparece em `data=` do dia seguinte, com nome começando em `…T01`. O horário real de cada
evento continua no campo `@timestamp`. Um evento que chega atrasado fica no arquivo da hora em que chegou.

## D08 — Pacotes de 30 minutos ou 1 MB

**Decisão:** o receptor fecha o pacote quando ele completa **30 minutos** ou **1 MB já compactado**, o que vier
primeiro (ou quando vira a hora).

**Por quê:** a primeira versão fechava pacotes a cada 60 segundos, o que geraria uma enorme quantidade de arquivos
pequenos no bucket, mais caros de guardar e de consultar. Com a regra nova são no máximo 2 arquivos por hora por
serviço e réplica.

**Preço aceito pelo dono do projeto:** se o pod for removido de repente (máquina caindo, por exemplo), perdem-se até
30 minutos de log daquela réplica. Montar a pasta do buffer num volume `emptyDir` faz com que um simples reinício do
container não perca nada (ver D09).

## D09 — Gravar já compactado e recuperar depois de uma queda

**Decisão:** o pacote é gravado no disco já em gzip, e a cada requisição recebida o receptor garante que o que chegou
está escrito no disco. Se o processo morrer, na próxima vez que subir ele lê o pacote interrompido, descarta só uma
eventual linha pela metade e envia o resto.

**Por quê:** com pacotes de até 30 minutos (D08), perder o pacote aberto em toda queda seria caro demais.

**Validado:** processo morto à força com pacote aberto; todas as linhas chegaram ao bucket depois do reinício.

## D10 — Envio ao S3 em ordem e só apagar depois da confirmação

**Decisão:**

- envia sempre o pacote mais antigo primeiro;
- apaga o arquivo local só depois de o S3 confirmar;
- no primeiro erro, para e espera antes de tentar de novo (5, 10, 20, 40 e 60 segundos);
- funciona com S3 fora da AWS (S3 compatíveis): envio sem "chunked encoding" e endereço no formato `endpoint/bucket`;
- usa o cliente HTTP que já vem no Java, sem bibliotecas extras de rede.

**Por quê:** com o S3 fora do ar, o receptor deve segurar os pacotes e, quando o S3 voltar, enviar tudo na ordem
certa, sem perder nada e sem encher o log de erros.

**Validado:** S3 derrubado e religado; um aviso na queda, um aviso na volta e a fila inteira enviada em ordem.

## D11 — Teto de disco: perder o mais antigo para não parar

**Decisão:** o buffer tem um teto (padrão 1 GB). Se chegar nele, o receptor descarta os pacotes prontos **mais
antigos** até voltar a 90% e avisa no log. Se não houver pacote pronto para descartar, ele recusa novos logs (503) e
os serviços tentam de novo.

**Por quê:** se o disco do pod enche, o Kubernetes mata o pod e perde **tudo** de uma vez. Perder o mais velho é o
mal menor, e só acontece se o S3 ficar fora do ar por muito tempo.

## D12 — Pode duplicar, não pode perder calado

**Decisão:** a entrega é "pelo menos uma vez". Se algo falhar no meio de uma gravação, o serviço reenvia e algumas
linhas podem aparecer duas vezes.

**Por quê:** garantir "exatamente uma vez" exigiria muito mais complexidade. Uma linha duplicada é fácil de tratar na
consulta; uma linha perdida sem aviso, não.

## D13 — Desligar sem perder o que está aberto

**Decisão:** ao receber o pedido de desligamento, o receptor:

1. avisa o Kubernetes que vai sair, mas continua aceitando por 5 segundos;
2. espera até 10 segundos pelas requisições em andamento;
3. fecha os pacotes e envia tudo em até 12 segundos.

Tudo cabe nos 30 segundos padrão do Kubernetes.

**Validado:** o processo saiu em 7 segundos e o pacote que estava aberto chegou ao bucket.

**Limite conhecido:** com **uma réplica só**, o lote que o serviço envia no último segundo pode encontrar o receptor
já fechado e se perder. Com duas ou mais réplicas, o reenvio deve cair na outra (a confirmar num piloto).

## D14 — Token obrigatório

**Decisão:** sem a lista de tokens (`RECEPTOR_TOKENS`), o receptor **não sobe**. Só os modos de desenvolvimento e de
teste rodam sem senha.

**Por quê (nas palavras do dono do projeto):** precisa funcionar "como vai rodar no ambiente". Esquecer de configurar
a senha deve ser um erro visível na hora, não uma porta aberta descoberta meses depois.

## D15 — Um token por serviço

**Decisão:** cada serviço tem o próprio token, e um token só pode mandar log em nome do serviço dono dele. Token de
outro serviço recebe 403.

**Por quê:** um serviço não deve conseguir gravar log fingindo ser outro, o que estragaria a confiança no histórico.
Também permite trocar ou revogar a senha de um serviço sem afetar os demais. Um serviço pode ter duas senhas ao mesmo
tempo, para trocar sem parar nada.

**Detalhes de segurança:** a senha é conferida antes de ler o conteúdo da requisição (quem não tem senha não gasta
memória nem disco), e a comparação leva sempre o mesmo tempo (não dá para adivinhar a senha medindo a demora).

## D16 — O log do receptor vai para a observabilidade da empresa

**Decisão:** o receptor manda o **próprio** log e as próprias métricas para a observabilidade da empresa, como
qualquer outro serviço. Ele nunca manda log para si mesmo.

**Por quê:** é lá que o time já olha e onde se pode criar um alarme. Antes, o log do receptor ficava só no console e
ninguém veria um problema.

## D17 — Alarme pelo resumo do receptor

**Decisão:** a cada minuto, o receptor escreve uma linha de resumo. Se algo foi descartado ou recusado (disco cheio,
token inválido, token de outro serviço), a linha sai como aviso e contém **"HOUVE PERDA DE LOG"**.

**Por quê:** os serviços **não percebem** quando o receptor está fora do ar ou recusa a senha deles: continuam
funcionando e não escrevem nada. O único lugar que enxerga essas falhas é o receptor.

## D18 — Mascarar dados pessoais é o último passo

**Decisão:** esconder dados pessoais (documentos, nomes etc.) antes de gravar é obrigatório, mas fica para o **fim**,
depois que todo o resto estiver funcionando de ponta a ponta. **Nenhum dado real passa pelo receptor antes disso.**

**Por quê:** o dono do projeto preferiu ter a estrutura inteira testada e estável antes de tratar o conteúdo dos logs.
O mascaramento será feito no receptor, num lugar só, e não em cada serviço.

## D19 — Prazo de guarda ainda em aberto

**Situação:** fala-se em guardar até **5 anos**, mas o prazo ainda não foi confirmado (quem exige e por quê). Sem
exigência formal, um prazo menor reduz custo e risco de privacidade. Quando definido, o próprio bucket apagará os
arquivos antigos sozinho (regra de *lifecycle*), possivelmente movendo-os antes para uma classe de armazenamento mais
barata.

## D20 — Pelo menos duas réplicas

**Decisão:** em produção, o receptor roda com duas ou mais cópias.

**Por quê:** cobre o limite do desligamento (D13) e evita que uma única queda interrompa o recebimento.

---

## Testes que sustentam estas decisões

- **70 testes automáticos:** 53 no receptor, 11 no exemplo Quarkus e 6 no exemplo Spring Batch.
- **Testes manuais de ponta a ponta no playground:** os dois caminhos recebendo as mesmas linhas, S3 caindo e voltando,
  teto de disco, desligamento, queda brusca, falta de token, token de outro serviço e log do receptor chegando à
  "empresa". O roteiro para repeti-los está no [guia de uso](USO.md#testes-de-e-se-der-errado).
