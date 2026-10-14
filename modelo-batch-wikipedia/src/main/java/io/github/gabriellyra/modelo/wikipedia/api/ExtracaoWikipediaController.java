package io.github.gabriellyra.modelo.wikipedia.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.gabriellyra.modelo.wikipedia.job.ExtracaoWikipediaLauncher;
import io.github.gabriellyra.modelo.wikipedia.model.PaginaExtraida;
import io.github.gabriellyra.modelo.wikipedia.repository.PaginaExtraidaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ExtracaoWikipediaController {

    private static final int LIMITE_MAXIMO = 100;

    private final ExtracaoWikipediaLauncher launcher;
    private final PaginaExtraidaRepository repository;

    /** Dispara o job imediatamente (sincrono), sem esperar o agendamento. */
    @PostMapping("/jobs/extracao-wikipedia")
    public ExecucaoJobResponse executarAgora() {
        log.info("Execucao manual do job solicitada via API");
        return ExecucaoJobResponse.de(launcher.executar("manual"));
    }

    @GetMapping("/paginas")
    public List<PaginaExtraida> listar(@RequestParam(defaultValue = "20") int limite) {
        int limiteAplicado = Math.max(1, Math.min(limite, LIMITE_MAXIMO));
        log.debug("Listando as {} paginas extraidas mais recentes", limiteAplicado);
        return repository.listarMaisRecentes(limiteAplicado);
    }
}
