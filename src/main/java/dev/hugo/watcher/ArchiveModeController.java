package dev.hugo.watcher;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
public class ArchiveModeController {
    private final ArchiveModeService modes;
    private final ArchiveModeProperties properties;

    public ArchiveModeController(ArchiveModeService modes, ArchiveModeProperties properties) {
        this.modes = modes;
        this.properties = properties;
    }

    @GetMapping("/internal/archive-mode")
    public ResponseEntity<ModeResponse> get(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (!authorized(authorization)) return unauthorized();
        return ResponseEntity.ok(new ModeResponse(modes.getMode()));
    }

    @PutMapping("/internal/archive-mode")
    public ResponseEntity<ModeResponse> set(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                            @RequestBody(required = false) ModeRequest request) {
        if (!authorized(authorization)) return unauthorized();
        if (request == null || request.mode() == null) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(new ModeResponse(modes.setMode(request.mode())));
    }

    private boolean authorized(String authorization) {
        String expected = properties.getApiToken();
        if (expected == null || expected.isBlank() || authorization == null || !authorization.startsWith("Bearer ")) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                authorization.substring(7).getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<ModeResponse> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer").build();
    }

    public record ModeRequest(ArchiveMode mode) { }
    public record ModeResponse(ArchiveMode mode) { }
}
