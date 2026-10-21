import time, gzip
from opentelemetry.proto.collector.logs.v1.logs_service_pb2 import ExportLogsServiceRequest
from opentelemetry.proto.logs.v1.logs_pb2 import ResourceLogs, ScopeLogs, LogRecord
from opentelemetry.proto.resource.v1.resource_pb2 import Resource
from opentelemetry.proto.common.v1.common_pb2 import KeyValue, AnyValue, InstrumentationScope

def kv(k, v):
    return KeyValue(key=k, value=AnyValue(string_value=v))

def req(servico):
    agora = time.time_ns()
    regs = [
        LogRecord(time_unix_nano=agora, observed_time_unix_nano=agora, severity_number=9, severity_text="INFO",
                  body=AnyValue(string_value="teste insomnia - INFO"), attributes=[kv("origem", "insomnia")]),
        LogRecord(time_unix_nano=agora+1000, observed_time_unix_nano=agora+1000, severity_number=13, severity_text="WARN",
                  body=AnyValue(string_value="teste insomnia - WARN"), attributes=[kv("origem", "insomnia")]),
        LogRecord(time_unix_nano=agora+2000, observed_time_unix_nano=agora+2000, severity_number=17, severity_text="ERROR",
                  body=AnyValue(string_value="teste insomnia - ERROR"),
                  attributes=[kv("origem", "insomnia"), kv("exception.type", "java.lang.IllegalStateException"),
                              kv("exception.message", "erro de teste"),
                              kv("exception.stacktrace", "java.lang.IllegalStateException: erro de teste\n\tat Teste.main(Teste.java:1)")]),
    ]
    return ExportLogsServiceRequest(resource_logs=[ResourceLogs(
        resource=Resource(attributes=[kv("service.name", servico)]),
        scope_logs=[ScopeLogs(scope=InstrumentationScope(name="io.github.gabriellyra.teste.Insomnia"), log_records=regs)])]).SerializeToString()

p = req("modelo-quarkus-pokemon")
open("log-pokemon.bin", "wb").write(p)
open("log-pokemon.bin.gz", "wb").write(gzip.compress(p))
open("log-outro-servico.bin", "wb").write(req("servico-sem-token"))
# confere
r = ExportLogsServiceRequest(); r.ParseFromString(open("log-pokemon.bin","rb").read())
print(r.resource_logs[0].resource.attributes[0], len(r.resource_logs[0].scope_logs[0].log_records))
