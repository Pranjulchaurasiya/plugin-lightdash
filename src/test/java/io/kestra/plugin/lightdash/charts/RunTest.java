package io.kestra.plugin.lightdash.charts;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@KestraTest
@WireMockTest
class RunTest {

    @Inject
    private RunContextFactory runContextFactory;

    private static final String RESULTS_JSON = """
        {
          "status": "ok",
          "results": {
            "rows": [
              {
                "country": "US",
                "total_users": 1500
              },
              {
                "country": "FR",
                "total_users": 850
              }
            ],
            "cacheMetadata": {
              "cacheHit": true
            },
            "metricQuery": {
              "exploreName": "users"
            }
          }
        }
        """;

    @Test
    void runChartFetch(WireMockRuntimeInfo wm) throws Exception {
        stubFor(
            post(urlEqualTo("/api/v1/saved/chart-abc/results"))
                .withHeader("Authorization", equalTo("ApiKey test-token"))
                .willReturn(okJson(RESULTS_JSON))
        );

        RunContext runContext = runContextFactory.of(Map.of());

        Run task = Run.builder()
            .url(Property.ofValue(wm.getHttpBaseUrl()))
            .apiToken(Property.ofValue("test-token"))
            .chartUuid(Property.ofValue("chart-abc"))
            .fetchType(Property.ofValue(Run.FetchType.FETCH))
            .build();

        Run.Output output = task.run(runContext);

        assertThat(output.getSize(), is(2L));
        assertThat(output.getRows(), hasSize(2));
        assertThat(output.getRows().get(0).get("country"), is("US"));
        assertThat(output.getRows().get(1).get("country"), is("FR"));
        assertThat(output.getCacheMetadata(), notNullValue());
        assertThat(output.getCacheMetadata().get("cacheHit"), is(true));
    }

    @Test
    void runChartFetchOne(WireMockRuntimeInfo wm) throws Exception {
        stubFor(
            post(urlEqualTo("/api/v1/saved/chart-abc/results"))
                .withHeader("Authorization", equalTo("ApiKey test-token"))
                .willReturn(okJson(RESULTS_JSON))
        );

        RunContext runContext = runContextFactory.of(Map.of());

        Run task = Run.builder()
            .url(Property.ofValue(wm.getHttpBaseUrl()))
            .apiToken(Property.ofValue("test-token"))
            .chartUuid(Property.ofValue("chart-abc"))
            .fetchType(Property.ofValue(Run.FetchType.FETCH_ONE))
            .build();

        Run.Output output = task.run(runContext);

        assertThat(output.getSize(), is(2L));
        assertThat(output.getRow(), notNullValue());
        assertThat(output.getRow().get("country"), is("US"));
        assertThat(output.getRow().get("total_users"), is(1500));
    }

    @Test
    void runChartStore(WireMockRuntimeInfo wm) throws Exception {
        stubFor(
            post(urlEqualTo("/api/v1/saved/chart-abc/results"))
                .withHeader("Authorization", equalTo("ApiKey test-token"))
                .willReturn(okJson(RESULTS_JSON))
        );

        RunContext runContext = runContextFactory.of(Map.of());

        Run task = Run.builder()
            .url(Property.ofValue(wm.getHttpBaseUrl()))
            .apiToken(Property.ofValue("test-token"))
            .chartUuid(Property.ofValue("chart-abc"))
            .fetchType(Property.ofValue(Run.FetchType.STORE))
            .build();

        Run.Output output = task.run(runContext);

        assertThat(output.getSize(), is(2L));
        assertThat(output.getUri(), notNullValue());

        List<Object> storedRows = new ArrayList<>();
        try (InputStream is = runContext.storage().getFile(output.getUri())) {
            FileSerde.read(is, storedRows::add);
        }

        assertThat(storedRows, hasSize(2));
        @SuppressWarnings("unchecked")
        Map<String, Object> firstRow = (Map<String, Object>) storedRows.get(0);
        assertThat(firstRow.get("country"), is("US"));
    }
}
