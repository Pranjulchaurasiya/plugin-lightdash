package io.kestra.plugin.lightdash.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.List;
import java.util.Map;

@Getter
@Builder
@ToString
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class QueryResult {
    @Schema(description = """
        The result rows returned by the query.
        """)
    private List<Map<String, Object>> rows;

    @Schema(description = """
        Cache metadata for the query execution.
        """)
    private Map<String, Object> cacheMetadata;

    @Schema(description = """
        The underlying metric query definition.
        """)
    private Map<String, Object> metricQuery;
}
