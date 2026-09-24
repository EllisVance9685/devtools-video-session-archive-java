package dev.example.archive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class SessionArchive {
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;
    private final String bucket;
    private final String apiKey;

    public SessionArchive(ObjectMapper json, @Value("${archive.base-url}") String baseUrl,
                          @Value("${archive.bucket}") String bucket, @Value("${archive.key}") String apiKey) {
        this.json = json;
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.bucket = bucket;
        this.apiKey = apiKey;
    }

    public static void main(String[] args) {
        SpringApplication.run(SessionArchive.class, args);
    }

    public record Start(String sessionId, String kind, String identity) {}
    public record Session(String room, String token, String bucket, String objectKey, String uploadUrl) {}

    static String objectKey(Start input) {
        if (input == null || input.sessionId() == null || !input.sessionId().matches("[a-zA-Z0-9-]{1,64}")
                || input.identity() == null || !input.identity().matches("[a-zA-Z0-9-]{1,64}")
                || !java.util.Set.of("build", "release", "diagnostic").contains(input.kind())) {
            throw new IllegalArgumentException("sessionId and identity must be alphanumeric; kind must be build, release or diagnostic");
        }
        return input.kind() + "/" + input.sessionId() + "/capture.webm";
    }

    @PostMapping("/sessions")
    public Session start(@RequestBody Start input, @RequestHeader("Idempotency-Key") String requestId) throws Exception {
        String key = objectKey(input);
        if (!requestId.matches("[a-zA-Z0-9-]{1,128}")) throw new IllegalArgumentException("Invalid Idempotency-Key");
        // One credential and one base URL cover room access and private object storage.
        call("POST", "/v1/storage/bucket/create", Map.of("name", bucket), requestId + "-bucket");
        String room = "devtools-" + input.sessionId();
        call("POST", "/v1/rtc/room/create", Map.of("name", room), requestId + "-room");
        JsonNode token = call("POST", "/v1/rtc/token/issue",
                Map.of("room", room, "identity", input.identity(), "can_publish", true, "can_subscribe", true), requestId + "-token");
        JsonNode signed = call("POST", "/v1/storage/object/presign/" + segment(bucket) + "/" + segment(key),
                Map.of("op", "put", "expires_seconds", 900, "content_type", "video/webm", "idempotency_key", requestId + "-upload"), requestId + "-upload");
        return new Session(room, token.path("token").asText(), bucket, key, signed.path("url").asText());
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private JsonNode call(String method, String path, Object body, String requestId) throws Exception {
        String payload = json.writeValueAsString(body);
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json").header("Idempotency-Key", requestId)
                    .method(method, HttpRequest.BodyPublishers.ofString(payload)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode envelope = json.readTree(response.body());
            if (response.statusCode() == 429 && attempt < 3) {
                long delay = response.headers().firstValue("Retry-After").map(SessionArchive::retrySeconds)
                        .orElse(1L << attempt);
                Thread.sleep(Math.min(delay, 30) * 1000);
                continue;
            }
            if (!envelope.path("ok").asBoolean(false)) {
                JsonNode error = envelope.path("error");
                throw new RemoteError(error.path("code").asText("REMOTE_ERROR"),
                        error.path("message").asText("Request rejected"), response.statusCode());
            }
            if (response.statusCode() >= 500) throw new RemoteError("REMOTE_ERROR", "Upstream unavailable", 502);
            return envelope.path("data");
        }
        throw new RemoteError("RATE_LIMITED", "Please retry later", 429);
    }

    private static long retrySeconds(String raw) {
        try { return Math.max(0, Long.parseLong(raw)); }
        catch (NumberFormatException ignored) { return 1; }
    }

    static class RemoteError extends RuntimeException {
        final String code;
        final int status;
        RemoteError(String code, String message, int status) {
            super(message); this.code = code; this.status = status;
        }
    }

    @ExceptionHandler(RemoteError.class)
    public ResponseEntity<Map<String, String>> remote(RemoteError error) {
        int status = error.status >= 400 && error.status < 500 ? error.status : 502;
        return ResponseEntity.status(status).body(Map.of("code", error.code, "message", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", error.getMessage()));
    }
}
