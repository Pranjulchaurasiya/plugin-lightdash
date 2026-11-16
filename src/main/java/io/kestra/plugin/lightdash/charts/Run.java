package io.kestra.plugin.lightdash.charts;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.lightdash.AbstractLightdashTask;
import io.kestra.plugin.lightdash.models.QueryResult;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run a saved chart in Lightdash",
    description = """
        Executes the query associated with a saved Lightdash chart and fetches or stores its result rows."""
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
                    chartUuid: "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d"
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
                    chartUuid: "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d"
                    fetchType: STORE
                """
        )
    }
)
public class Run extends AbstractLightdashTask implements RunnableTask<Run.Output> {

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
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        String renderedChartUuid = runContext.render(this.chartUuid).as(String.class).orElseThrow();
        FetchType type = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        runContext.logger().info("Executing query for Lightdash chart '{}' with fetchType={}", renderedChartUuid, type);

        QueryResult queryResult = requestResults(
            runContext,
            "POST",
            "saved/" + renderedChartUuid + "/results",
            Map.of(),
            QueryResult.class
        );

        List<Map<String, Object>> rows = queryResult != null && queryResult.getRows() != null
            ? queryResult.getRows()
            : List.of();

        long rowCount = rows.size();
        runContext.logger().info("Received {} row(s) for chart '{}'.", rowCount, renderedChartUuid);

        Output.OutputBuilder builder = Output.builder()
            .size(rowCount)
            .cacheMetadata(queryResult != null ? queryResult.getCacheMetadata() : null)
            .metricQuery(queryResult != null ? queryResult.getMetricQuery() : null);

        switch (type) {
            case FETCH_ONE -> {
                if (!rows.isEmpty()) {
                    builder.row(rows.get(0));
                }
            }
            case FETCH -> builder.rows(rows);
            case STORE -> {
                File tempFile = runContext.workingDir().createTempFile(".ion").toFile();
                try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(tempFile))) {
                    for (Map<String, Object> row : rows) {
                        FileSerde.write(outputStream, row);
                    }
                }
                URI storageUri = runContext.storage().putFile(tempFile);
                builder.uri(storageUri);
            }
        }

        return builder.build();
    }

    public enum FetchType {
        FETCH,
        FETCH_ONE,
        STORE
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            description = """
                The list of result rows returned when `fetchType` is `FETCH`.
                """
        )
        private List<Map<String, Object>> rows;

        @Schema(
            description = """
                The first result row returned when `fetchType` is `FETCH_ONE`.
                """
        )
        private Map<String, Object> row;

        @Schema(
            description = """
                The URI of the stored file in Kestra internal storage when `fetchType` is `STORE`.
                """
        )
        private URI uri;

        @Schema(
            description = """
                The total number of rows returned by the query.
                """
        )
        private Long size;

        @Schema(
            description = """
                Cache metadata for the query execution.
                """
        )
        private Map<String, Object> cacheMetadata;

        @Schema(
            description = """
                The underlying metric query definition.
                """
        )
        private Map<String, Object> metricQuery;
    }
}
