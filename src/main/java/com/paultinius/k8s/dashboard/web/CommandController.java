package com.paultinius.k8s.dashboard.web;

import com.paultinius.k8s.dashboard.cluster.ClusterRegistry;
import com.paultinius.k8s.dashboard.shell.CommandResult;
import com.paultinius.k8s.dashboard.shell.CommandService;
import com.paultinius.k8s.dashboard.shell.Completion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/commands")
public class CommandController {

    private final ClusterRegistry clusters;
    private final CommandService commands;

    public CommandController(ClusterRegistry clusters, CommandService commands) {
        this.clusters = clusters;
        this.commands = commands;
    }

    @PostMapping
    public CommandResult run(
            @RequestParam(required = false) String cluster,
            @Valid @RequestBody RunRequest request
    ) {
        return commands.execute(clusters.require(cluster), request.command(), request.namespace(), request.id());
    }

    @PostMapping("/complete")
    public Completion complete(
            @RequestParam(required = false) String cluster,
            @Valid @RequestBody CompleteRequest request
    ) {
        return commands.complete(clusters.require(cluster), request.line(), request.cursor(), request.namespace());
    }

    @DeleteMapping("/{id}")
    public Map<String, String> cancel(@PathVariable String id) {
        commands.cancel(id);
        return Map.of("status", "cancelled");
    }

    public record RunRequest(
            @NotBlank @Size(max = 4_000) String command,
            @Size(max = 253) String namespace,
            @Size(max = 64) String id
    ) {
    }

    public record CompleteRequest(
            @NotNull @Size(max = 4_000) String line,
            @Min(0) @Max(4_000) int cursor,
            @Size(max = 253) String namespace
    ) {
    }
}
