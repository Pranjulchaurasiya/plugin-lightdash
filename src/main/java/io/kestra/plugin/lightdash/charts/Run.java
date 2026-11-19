package io.kestra.plugin.lightdash.charts;

import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.lightdash.AbstractLightdashTask;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run a saved chart in Lightdash",
    description = """
        Executes the query associated with a saved Lightdash chart using the v2 async execution flow and fetches or stores its result rows."""
)
@Plugin(
    examples = {
        @Example(
            title = "Run a chart and fetch all result rows",
            full = true,
            code = """
                id: run_lightdash_chart
                namespace: company.analytics

                tasks:
                  - id: run_chart
                    type: io.kestra.plugin.lightdash.charts.Run
                    url: "https://app.lightdash.cloud"
                    apiToken: "{{ secret('LIGHTDASH_API_TOKEN') }}"
                    projectUuid: "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d"
                    chartUuid: "d4e5f6a7-b8c9-0d1e-2f3a-4b5c6d7e8f9a"
                    fetchType: FETCH
                """
        ),
        @Example(
            title = "Run a chart and store results to Kestra internal storage",
            full = true,
            code = """
                id: store_lightdash_chart
                namespace: company.analytics

                tasks:
                  - id: store_chart
                    type: io.kestra.plugin.lightdash.charts.Run
                    url: "https://app.lightdash.cloud"
                    apiToken: "{{ secret('LIGHTDASH_API_TOKEN') }}"
                    projectUuid: "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d"
                    chartUuid: "d4e5f6a7-b8c9-0d1e-2f3a-4b5c6d7e8f9a"
                    fetchType: STORE
                """
        )
    }
)
public class Run extends AbstractLightdashTask implements RunnableTask<Run.Output> {

    @Schema(
        title = "Project UUID",
        description = """
            The unique identifier of the Lightdash project containing the saved chart."""
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> projectUuid;

    @Schema(
        title = "Chart UUID",
        description = """
            The unique identifier of the saved chart to run."""
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> chartUuid;

    @Schema(
        title = "Fetch type",
        description = """
            The output handling strategy: `FETCH` to retrieve all rows into task outputs, `FETCH_ONE` for the first row, or `STORE` to stream into Kestra internal storage."""
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Schema(
        title = "Polling interval",
        description = """
            Interval between consecutive query result polling requests."""
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<Duration> pollInterval = Property.ofValue(Duration.ofMillis(500));

    @Schema(
        title = "Maximum wait duration",
        description = """
            Maximum time to wait for the query to finish executing before timing out."""
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<Duration> maxWait = Property.ofValue(Duration.ofMinutes(5));

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rProjectUuid = runContext.render(this.projectUuid).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("`projectUuid` is required for Run chart task."));
        var rChartUuid = runContext.render(this.chartUuid).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("`chartUuid` is required for Run chart task."));
        var type = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        var interval = runContext.render(this.pollInterval).as(Duration.class).orElse(Duration.ofMillis(500));
        var maxTimeout = runContext.render(this.maxWait).as(Duration.class).orElse(Duration.ofMinutes(5));

        runContext.logger().info("Executing saved chart query for Lightdash chart '{}' in project '{}' with fetchType={}", rChartUuid, rProjectUuid, type);

        var execResponse = requestResults(
            runContext,
            "POST",
            "api/v2/projects/" + rProjectUuid + "/query/chart",
            Map.of("chartUuid", rChartUuid),
            new TypeReference<Map<String, Object>>() {}
        );

        var queryUuid = execResponse != null ? (String) execResponse.get("queryUuid") : null;
        if (queryUuid == null || queryUuid.isBlank()) {
            throw new IllegalStateException("Failed to obtain queryUuid from Lightdash execute saved chart response: " + execResponse);
        }

        runContext.logger().debug("Started async query with queryUuid '{}'", queryUuid);

        var deadline = Instant.now().plus(maxTimeout);
        Map<String, Object> queryResults = null;

        while (Instant.now().isBefore(deadline)) {
            var getResult = requestResults(
                runContext,
                "GET",
                "api/v2/projects/" + rProjectUuid + "/query/" + queryUuid,
                null,
                new TypeReference<Map<String, Object>>() {}
            );

            var status = getResult != null ? (String) getResult.get("status") : null;
            if ("ready".equalsIgnoreCase(status)) {
                queryResults = getResult;
                break;
            }

            if ("error".equalsIgnoreCase(status) || "expired".equalsIgnoreCase(status)) {
                var errorMsg = getResult != null ? getResult.get("error") : "unknown error";
                throw new IllegalStateException("Lightdash query '" + queryUuid + "' failed with status '" + status + "': " + errorMsg);
            }

            TimeUnit.MILLISECONDS.sleep(interval.toMillis());
        }

        if (queryResults == null) {
            throw new IllegalStateException("Timed out waiting for Lightdash chart query '" + queryUuid + "' after " + maxTimeout);
        }

        var rawRowsObj = queryResults.get("rows");
        java.util.List<?> rawRowsList = rawRowsObj instanceof java.util.List<?> list ? list : java.util.List.of();
        var rows = flattenRows(rawRowsList);

        var rowCount = (long) rows.size();
        runContext.logger().info("Received and flattened {} row(s) for chart '{}'.", rowCount, rChartUuid);

        var builder = Output.builder()
            .queryUuid(queryUuid)
            .size(rowCount);

        switch (type) {
            case FETCH_ONE -> {
                if (!rows.isEmpty()) {
                    builder.row(rows.get(0));
                }
            }
            case FETCH -> builder.rows(rows);
            case STORE -> {
                var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
                try (var outputStream = new BufferedOutputStream(new FileOutputStream(tempFile))) {
                    for (var row : rows) {
                        FileSerde.write(outputStream, row);
                    }
                }
                var storageUri = runContext.storage().putFile(tempFile);
                builder.uri(storageUri);
            }
        }

        return builder.build();
    }

    public static java.util.List<Map<String, Object>> flattenRows(java.util.List<?> rawRows) {
        if (rawRows == null || rawRows.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<Map<String, Object>> flattened = new ArrayList<>(rawRows.size());
        for (var item : rawRows) {
            if (item instanceof Map<?, ?> rowMap) {
                Map<String, Object> flatRow = new LinkedHashMap<>();
                for (var entry : rowMap.entrySet()) {
                    var key = String.valueOf(entry.getKey());
                    flatRow.put(key, extractRawValue(entry.getValue()));
                }
                flattened.add(flatRow);
            }
        }
        return flattened;
    }

    private static Object extractRawValue(Object cell) {
        if (cell instanceof Map<?, ?> map) {
            if (map.containsKey("value") && map.get("value") instanceof Map<?, ?> valueMap) {
                if (valueMap.containsKey("raw")) {
                    return valueMap.get("raw");
                } else if (valueMap.containsKey("formatted")) {
                    return valueMap.get("formatted");
                }
            }
        }
        return cell;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            description = """
                The UUID of the executed asynchronous query.
                """
        )
        private String queryUuid;

        @Schema(
            description = """
                The list of flattened result rows returned when `fetchType` is `FETCH`.
                """
        )
        private java.util.List<Map<String, Object>> rows;

        @Schema(
            description = """
                The first flattened result row returned when `fetchType` is `FETCH_ONE`.
                """
        )
        private Map<String, Object> row;

        @Schema(
            description = """
                The URI of the stored results file when `fetchType` is `STORE`.
                """
        )
        private URI uri;

        @Schema(
            description = """
                The total number of rows returned by the query.
                """
        )
        private Long size;
    }
}
