package io.kestra.plugin.lightdash;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
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
public abstract class AbstractLightdashTask extends Task implements LightdashConnection {
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

    @Override
    public Property<String> getUrl() {
        return this.url;
    }

    @Override
    public Property<String> getApiToken() {
        return this.apiToken;
    }

    @Override
    public Property<Duration> getTimeout() {
        return this.timeout;
    }

    /**
     * Resolves the normalized base API URI (e.g. `https://app.lightdash.cloud/api/v1/`).
     */
    protected URI resolveBaseApiUri(RunContext runContext) throws IllegalVariableEvaluationException {
        return LightdashClient.resolveBaseApiUri(runContext, this.url);
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
        return LightdashClient.execute(runContext, this.url, this.apiToken, this.timeout, method, endpointPath, body);
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
