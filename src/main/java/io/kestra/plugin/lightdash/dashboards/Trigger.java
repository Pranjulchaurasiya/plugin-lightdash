package io.kestra.plugin.lightdash.dashboards;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.http.client.configurations.TimeoutConfiguration;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.*;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.plugin.lightdash.models.DashboardSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

@SuperBuilder
@ToString
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger execution on Lightdash dashboard update",
    description = """
        Periodically polls Lightdash for dashboards that have been created or modified in a project,
        using the namespace KV store to advance an update timestamp watermark for exactly-once firing."""
)
@Plugin(
    examples = {
        @Example(
            title = "Trigger a flow whenever a dashboard is modified in Lightdash",
            full = true,
            code = """
                id: on_dashboard_updated
                namespace: company.analytics

                triggers:
                  - id: watch_dashboards
                    type: io.kestra.plugin.lightdash.dashboards.Trigger
                    url: "https://app.lightdash.cloud"
                    apiToken: "{{ secret('LIGHTDASH_API_TOKEN') }}"
                    projectUuid: "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d"
                    interval: PT1M

                tasks:
                  - id: notify
                    type: io.kestra.plugin.core.log.Log
                    message: "Dashboard updated: {{ trigger.dashboard.name }} (UUID: {{ trigger.dashboard.uuid }})"
                """
        )
    }
)
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<Trigger.Output> {

    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();

    @Schema(
        title = "Lightdash instance base URL",
        description = """
            The base URL of your Lightdash instance, e.g. `https://app.lightdash.cloud`."""
    )
    @NotNull
    @PluginProperty(group = "connection")
    @Builder.Default
    private Property<String> url = Property.ofValue("https://app.lightdash.cloud");

    @Schema(
        title = "Lightdash Personal Access Token (API Key)",
        description = """
            The Personal Access Token generated from your Lightdash personal settings."""
    )
    @NotNull
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Property<String> apiToken;

    @Schema(
        title = "Project UUID",
        description = """
            The unique identifier of the Lightdash project to monitor."""
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> projectUuid;

    @Schema(
        title = "Polling interval",
        description = """
            Interval between trigger evaluations. Defaults to 1 minute."""
    )
    @PluginProperty(group = "execution")
    @Builder.Default
    private final Duration interval = Duration.ofMinutes(1);

    @Override
    public Duration getInterval() {
        return this.interval;
    }

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();
        var logger = runContext.logger();

        String rawUrl = runContext.render(this.url).as(String.class).orElse("https://app.lightdash.cloud").trim();
        while (rawUrl.endsWith("/")) {
            rawUrl = rawUrl.substring(0, rawUrl.length() - 1);
        }
        if (!rawUrl.endsWith("/api/v1") && !rawUrl.endsWith("/api/v2")) {
            rawUrl = rawUrl + "/api/v1";
        }
        URI baseUri = URI.create(rawUrl + "/");

        String token = runContext.render(this.apiToken).as(String.class).orElseThrow();
        String renderedProjectUuid = runContext.render(this.projectUuid).as(String.class).orElseThrow();

        URI requestUri = baseUri.resolve("projects/" + renderedProjectUuid + "/dashboards");

        TimeoutConfiguration timeoutConfig = TimeoutConfiguration.builder()
            .connectTimeout(Property.ofValue(Duration.ofSeconds(30)))
            .readIdleTimeout(Property.ofValue(Duration.ofSeconds(30)))
            .build();

        HttpConfiguration httpConfig = HttpConfiguration.builder()
            .timeout(timeoutConfig)
            .build();

        HttpRequest request = HttpRequest.builder()
            .uri(requestUri)
            .method("GET")
            .addHeader("Authorization", "ApiKey " + token)
            .addHeader("Accept", "application/json")
            .build();

        java.util.List<DashboardSummary> dashboards;
        try (HttpClient client = new HttpClient(runContext, httpConfig)) {
            HttpResponse<String> response = client.request(request, String.class);
            if (response.getStatus().getCode() >= 400) {
                logger.warn("Lightdash trigger request failed with status {}: {}", response.getStatus().getCode(), response.getBody());
                return Optional.empty();
            }

            JsonNode root = MAPPER.readTree(response.getBody());
            JsonNode resultsNode = root.has("results") ? root.get("results") : root;
            dashboards = MAPPER.convertValue(resultsNode, new TypeReference<java.util.List<DashboardSummary>>() {});
        }

        if (dashboards == null || dashboards.isEmpty()) {
            return Optional.empty();
        }

        var kvStore = runContext.namespaceKv(context.getNamespace());
        String flowId = context.getFlowId() != null ? context.getFlowId() : "default";
        String triggerId = this.getId() != null ? this.getId() : "lightdash";
        String stateKey = "lightdash_watermark_" + flowId.length() + "_" + flowId + "_" + triggerId.length() + "_" + triggerId + "_" + sha256Hex(renderedProjectUuid);

        Instant watermark = null;
        try {
            var kvVal = kvStore.getValue(stateKey);
            if (kvVal.isPresent() && kvVal.get().value() != null) {
                Object val = kvVal.get().value();
                String raw = val instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : val.toString();
                watermark = Instant.parse(raw);
            }
        } catch (Exception e) {
            logger.warn("Failed to parse Lightdash trigger watermark for key '{}': {}", stateKey, e.getMessage());
        }

        // Find the latest updatedAt among current dashboards
        Instant maxCurrent = dashboards.stream()
            .map(DashboardSummary::getUpdatedAt)
            .filter(Objects::nonNull)
            .map(this::parseInstantSafe)
            .filter(Objects::nonNull)
            .max(Comparator.naturalOrder())
            .orElse(Instant.now());

        if (watermark == null) {
            // First run: initialize watermark to the current latest timestamp
            kvStore.put(
                stateKey,
                new KVValueAndMetadata(
                    new KVMetadata("Lightdash trigger watermark", (Duration) null),
                    maxCurrent.toString()
                )
            );
            logger.info("Initialized Lightdash trigger watermark for project '{}' at {}", renderedProjectUuid, maxCurrent);
            return Optional.empty();
        }

        final Instant currentWatermark = watermark;
        java.util.List<DashboardSummary> newlyUpdated = dashboards.stream()
            .filter(d -> {
                Instant upd = parseInstantSafe(d.getUpdatedAt());
                return upd != null && upd.isAfter(currentWatermark);
            })
            .sorted(Comparator.comparing(d -> parseInstantSafe(d.getUpdatedAt())))
            .toList();

        if (newlyUpdated.isEmpty()) {
            return Optional.empty();
        }

        Instant newWatermark = parseInstantSafe(newlyUpdated.get(newlyUpdated.size() - 1).getUpdatedAt());
        if (newWatermark != null && newWatermark.isAfter(currentWatermark)) {
            kvStore.put(
                stateKey,
                new KVValueAndMetadata(
                    new KVMetadata("Lightdash trigger watermark", (Duration) null),
                    newWatermark.toString()
                )
            );
        }

        DashboardSummary primary = newlyUpdated.get(0);
        Output output = Output.builder()
            .dashboardUuid(primary.getUuid())
            .dashboardName(primary.getName())
            .updatedAt(primary.getUpdatedAt())
            .dashboard(primary)
            .dashboards(newlyUpdated)
            .count(newlyUpdated.size())
            .build();

        logger.info("Lightdash trigger firing: {} modified dashboard(s) found in project '{}'", newlyUpdated.size(), renderedProjectUuid);
        return Optional.of(TriggerService.generateExecution(this, conditionContext, context, output));
    }

    private Instant parseInstantSafe(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(timestamp);
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            description = """
                The UUID of the primary modified dashboard.
                """
        )
        private String dashboardUuid;

        @Schema(
            description = """
                The name of the primary modified dashboard.
                """
        )
        private String dashboardName;

        @Schema(
            description = """
                The update timestamp of the primary modified dashboard.
                """
        )
        private String updatedAt;

        @Schema(
            description = """
                The primary or first modified dashboard.
                """
        )
        private DashboardSummary dashboard;

        @Schema(
            description = """
                The list of all modified dashboards detected in this polling window.
                """
        )
        private java.util.List<DashboardSummary> dashboards;

        @Schema(
            description = """
                The count of modified dashboards detected.
                """
        )
        private Integer count;
    }
}
