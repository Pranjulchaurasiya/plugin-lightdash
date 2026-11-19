package io.kestra.plugin.lightdash;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.http.client.configurations.TimeoutConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.time.Duration;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractLightdashTask extends Task {
    @Schema(
        title = "Lightdash instance base URL",
        description = """
            The base URL of your Lightdash instance, e.g. `https://app.lightdash.cloud`.
            Can be provided with or without a trailing slash or `/api/v1` path."""
    )
    @NotNull
    @PluginProperty(group = "connection")
    @Builder.Default
    protected Property<String> url = Property.ofValue("https://app.lightdash.cloud");

    @Schema(
        title = "Lightdash Personal Access Token (API Key)",
        description = """
            The Personal Access Token generated from your Lightdash personal settings.
            Passed via the `Authorization: ApiKey <token>` header."""
    )
    @NotNull
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    protected Property<String> apiToken;

    @Schema(
        title = "HTTP client timeout",
        description = """
            The timeout duration for HTTP requests made to the Lightdash API."""
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    protected Property<Duration> timeout = Property.ofValue(Duration.ofSeconds(30));

    /**
     * Resolves the normalized base API URI (e.g. `https://app.lightdash.cloud/api/v1/`).
     */
    protected URI resolveBaseApiUri(RunContext runContext) throws IllegalVariableEvaluationException {
        var rawUrl = runContext.render(this.url).as(String.class)
            .orElse("https://app.lightdash.cloud").trim();
        while (rawUrl.endsWith("/")) {
            rawUrl = rawUrl.substring(0, rawUrl.length() - 1);
        }
        if (!rawUrl.endsWith("/api/v1") && !rawUrl.endsWith("/api/v2")) {
            rawUrl = rawUrl + "/api/v1";
        }
        return URI.create(rawUrl + "/");
    }

    /**
     * Executes an HTTP request against the Lightdash API and returns the raw response body.
     */
    protected String executeRequest(
        RunContext runContext,
        String method,
        String endpointPath,
        Object body
    ) throws Exception {
        var baseUri = resolveBaseApiUri(runContext);
        URI fullUri;
        if (endpointPath.startsWith("/api/") || endpointPath.startsWith("api/")) {
            var path = endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath;
            var rootUrl = baseUri.toString().replaceAll("/api/v[12]/$", "");
            fullUri = URI.create(rootUrl + path);
        } else {
            var resolvedPath = endpointPath.startsWith("/") ? endpointPath.substring(1) : endpointPath;
            fullUri = baseUri.resolve(resolvedPath);
        }

        var token = runContext.render(this.apiToken).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("`apiToken` is required for Lightdash tasks."));
        var reqTimeout = runContext.render(this.timeout).as(Duration.class).orElse(Duration.ofSeconds(30));

        var timeoutConfig = TimeoutConfiguration.builder()
            .connectTimeout(Property.ofValue(reqTimeout))
            .readIdleTimeout(Property.ofValue(reqTimeout))
            .build();

        var httpConfig = HttpConfiguration.builder()
            .timeout(timeoutConfig)
            .build();

        var reqBuilder = HttpRequest.builder()
            .uri(fullUri)
            .method(method)
            .addHeader("Authorization", "ApiKey " + token)
            .addHeader("Accept", "application/json");

        if (body != null) {
            reqBuilder.addHeader("Content-Type", "application/json");
            if (body instanceof String strBody) {
                reqBuilder.body(HttpRequest.StringRequestBody.builder().content(strBody).build());
            } else {
                var jsonBody = JacksonMapper.ofJson().writeValueAsString(body);
                reqBuilder.body(HttpRequest.StringRequestBody.builder().content(jsonBody).build());
            }
        }

        var request = reqBuilder.build();

        try (var client = new HttpClient(runContext, httpConfig)) {
            HttpResponse<String> response;
            try {
                response = client.request(request, String.class);
            } catch (HttpClientResponseException e) {
                var resp = e.getResponse();
                var statusCode = resp != null && resp.getStatus() != null ? resp.getStatus().getCode() : 0;
                var errorBody = resp != null && resp.getBody() != null ? resp.getBody().toString() : "";

                var errorMsg = switch (statusCode) {
                    case 401 -> "Lightdash authentication failed (401 Unauthorized). Please verify that your `apiToken` is valid and active.";
                    case 403 -> "Lightdash permission denied (403 Forbidden). Ensure your personal access token has access to this project or resource.";
                    case 404 -> "Lightdash resource not found (404 Not Found) at " + fullUri + ". Verify your `projectUuid`, `chartUuid`, or `dashboardUuid`.";
                    default -> "Lightdash API request to " + fullUri + " failed with status " + statusCode + (errorBody.isEmpty() ? "" : ": " + errorBody);
                };
                throw new IllegalStateException(errorMsg, e);
            }

            var responseBody = response.getBody();
            if (response.getStatus().getCode() >= 400) {
                var errorMsg = switch (response.getStatus().getCode()) {
                    case 401 -> "Lightdash authentication failed (401 Unauthorized). Please verify that your `apiToken` is valid and active.";
                    case 403 -> "Lightdash permission denied (403 Forbidden). Ensure your personal access token has access to this project or resource.";
                    case 404 -> "Lightdash resource not found (404 Not Found) at " + fullUri + ". Verify your `projectUuid`, `chartUuid`, or `dashboardUuid`.";
                    default -> "Lightdash API request to " + fullUri + " failed with status " + response.getStatus().getCode() + ": " + responseBody;
                };
                throw new IllegalStateException(errorMsg);
            }

            if (responseBody == null || responseBody.isBlank()) {
                throw new IllegalStateException("Empty response body received from Lightdash API during " + method + " " + fullUri);
            }

            return responseBody;
        }
    }

    /**
     * Executes an HTTP request and unpacks the standard Lightdash `{"status": "ok", "results": ...}` payload into target type.
     */
    protected <T> T requestResults(
        RunContext runContext,
        String method,
        String endpointPath,
        Object body,
        Class<T> responseType
    ) throws Exception {
        var responseBody = executeRequest(runContext, method, endpointPath, body);
        if (responseType == Void.class || responseBody == null || responseBody.isBlank()) {
            return null;
        }

        var rootNode = JacksonMapper.ofJson().readTree(responseBody);
        if (rootNode.has("status") && !"ok".equalsIgnoreCase(rootNode.get("status").asText())) {
            throw new IllegalStateException("Lightdash API responded with non-ok status: " + responseBody);
        }

        var resultsNode = rootNode.has("results") ? rootNode.get("results") : rootNode;
        return JacksonMapper.ofJson().treeToValue(resultsNode, responseType);
    }

    /**
     * Executes an HTTP request and unpacks the standard Lightdash `{"status": "ok", "results": ...}` payload using a TypeReference.
     */
    protected <T> T requestResults(
        RunContext runContext,
        String method,
        String endpointPath,
        Object body,
        TypeReference<T> typeRef
    ) throws Exception {
        var responseBody = executeRequest(runContext, method, endpointPath, body);
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }

        var rootNode = JacksonMapper.ofJson().readTree(responseBody);
        if (rootNode.has("status") && !"ok".equalsIgnoreCase(rootNode.get("status").asText())) {
            throw new IllegalStateException("Lightdash API responded with non-ok status: " + responseBody);
        }

        var resultsNode = rootNode.has("results") ? rootNode.get("results") : rootNode;
        return JacksonMapper.ofJson().convertValue(resultsNode, typeRef);
    }

    /**
     * Executes an HTTP request and maps the complete raw response to target type.
     */
    protected <T> T makeRequest(
        RunContext runContext,
        String method,
        String endpointPath,
        Object body,
        Class<T> responseType
    ) throws Exception {
        var responseBody = executeRequest(runContext, method, endpointPath, body);
        if (responseType == Void.class || responseBody == null || responseBody.isBlank()) {
            return null;
        }

        if (responseType == String.class) {
            @SuppressWarnings("unchecked")
            T casted = (T) responseBody;
            return casted;
        }

        return JacksonMapper.ofJson().readValue(responseBody, responseType);
    }
}
