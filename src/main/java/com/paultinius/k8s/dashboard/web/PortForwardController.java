package com.paultinius.k8s.dashboard.web;

import com.paultinius.k8s.dashboard.forward.PortForwardService;
import com.paultinius.k8s.dashboard.model.ForwardView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/port-forwards")
public class PortForwardController {

    private final PortForwardService forwards;

    public PortForwardController(PortForwardService forwards) {
        this.forwards = forwards;
    }

    @GetMapping
    public List<ForwardView> list(@RequestParam(required = false) String cluster) {
        return forwards.list(cluster);
    }

    @PostMapping
    public ForwardView open(@Valid @RequestBody OpenRequest request) {
        return forwards.open(
                request.cluster(),
                request.namespace(),
                request.targetKind(),
                request.targetName(),
                request.remotePort(),
                request.localPort()
        );
    }

    @DeleteMapping("/{id}")
    public void close(@PathVariable String id) {
        forwards.close(id);
    }

    public record OpenRequest(
            @NotBlank String cluster,
            @NotBlank String namespace,
            @NotBlank String targetKind,
            @NotBlank String targetName,
            @Min(1) @Max(65535) int remotePort,
            @Min(0) @Max(65535) int localPort
    ) {
    }
}
