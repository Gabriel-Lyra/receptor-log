"""Mostra um corpo OTLP de logs (.bin ou .bin.gz) como JSON legivel.

Uso:  python ler.py log-pokemon.bin
Requer: pip install opentelemetry-proto
"""
import gzip
import sys

from google.protobuf.json_format import MessageToJson
from opentelemetry.proto.collector.logs.v1.logs_service_pb2 import ExportLogsServiceRequest

if len(sys.argv) != 2:
    sys.exit("uso: python ler.py <arquivo.bin | arquivo.bin.gz>")

with open(sys.argv[1], "rb") as f:
    dados = f.read()
if dados[:2] == b"\x1f\x8b":  # assinatura do gzip
    dados = gzip.decompress(dados)

requisicao = ExportLogsServiceRequest()
requisicao.ParseFromString(dados)
print(MessageToJson(requisicao, ensure_ascii=False))
