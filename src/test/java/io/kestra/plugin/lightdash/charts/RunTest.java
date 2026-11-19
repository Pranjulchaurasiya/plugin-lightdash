package io.kestra.plugin.lightdash.charts;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@WireMockTest
class RunTest {

    @Inject
    private RunContextFactory runContextFactory;

    private static final String EXEC_RESPONSE_JSON = """
        {
          "status": "ok",
          "results": {
            "queryUuid": "q-uuid-456"
          }
        }
        """;

    private static final String RESULTS_JSON = """
        {
          "status": "ok",
          "results": {
            "status": "ready",
            "queryUuid": "q-uuid-456",
            "rows": [
              {
                "country": {
                  "value": {
                    "raw": "US",
                    "formatted": "United States"
                  }
                },
                "total_users": {
                  "value": {
                    "raw": 1500,
                    "formatted": "1,500"
                  }
                }
              },
              {
                "country": {
                  "value": {
                    "raw": "FR",
                    "formatted": "France"
                  }
                },
                "total_users": {
                  "value": {
                    "raw": 850,
                    "formatted": "850"
                  }
                }
              }
            ]
          }
        }
        """;

    @Test
    void runChartFetch(WireMockRuntimeInfo wm) throws Exception {
        stubFor(
            post(urlEqualTo("/api/v2/projects/proj-123/query/chart"))
                .withHeader("Authorization", equalTo("ApiKey test-token"))
                .willReturn(okJson(EXEC_RESPONSE_JSON))
        );

        stubFor(
            get(urlEqualTo("/api/v2/projects/proj-123/query/q-uuid-456"))
                .withHeader("Authorization", equalTo("ApiKey test-token"))
                .willReturn(okJson(RESULTS_JSON))
        );

        var runContext = runContextFactory.of(Map.of());

        var task = Run.builder()
            .url(Property.ofValue(wm.getHttpBaseUrl()))
            .apiToken(Property.ofValue("test-token"))
            .projectUuid(Property.ofValue("proj-123"))
            .chartUuid(Property.ofValue("chart-abc"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        var output = task.run(runContext);

        assertThat(output.getSize(), is(2L));
        assertThat(output.getQueryUuid(), is("q-uuid-456"));
        assertThat(output.getRows(), hasSize(2));
        assertThat(output.getRows().get(0).get("country"), is("US"));
        assertThat(output.getRows().get(0).get("total_users"), is(1500));
        assertThat(output.getRows().get(1).get("country"), is("FR"));
        assertThat(output.getRows().get(1).get("total_users"), is(850));
    }

    @Test
    void runChartFetchOne(WireMockRuntimeInfo wm) throws Exception {
        stubFor(
            post(urlEqualTo("/api/v2/projects/proj-123/query/chart"))
                .withHeader("Authorization", equalTo("ApiKey test-token"))
                .willReturn(okJson(EXEC_RESPONSE_JSON))
        );

        stubFor(
            get(urlEqualTo("/api/v2/projects/proj-123/query/q-uuid-456"))
                .withHeader("Authorization", equalTo("ApiKey test-token"))
                .willReturn(okJson(RESULTS_JSON))
        );

        var runContext = runContextFactory.of(Map.of());

        var task = Run.builder()
            .url(Property.ofValue(wm.getHttpBaseUrl()))
            .apiToken(Property.ofValue("test-token"))
            .projectUuid(Property.ofValue("proj-123"))
            .chartUuid(Property.ofValue("chart-abc"))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();

        var output = task.run(runContext);

        assertThat(output.getSize(), is(2L));
        assertThat(output.getQueryUuid(), is("q-uuid-456"));
        assertThat(output.getRow(), notNullValue());
        assertThat(output.getRow().get("country"), is("US"));
        assertThat(output.getRow().get("total_users"), is(1500));
    }

    @Test
    void runChartStore(WireMockRuntimeInfo wm) throws Exception {
        stubFor(
            post(urlEqualTo("/api/v2/projects/proj-123/query/chart"))
                .withHeader("Authorization", equalTo("ApiKey test-token"))
                .willReturn(okJson(EXEC_RESPONSE_JSON))
        );

        stubFor(
            get(urlEqualTo("/api/v2/projects/proj-123/query/q-uuid-456"))
                .withHeader("Authorization", equalTo("ApiKey test-token"))
                .willReturn(okJson(RESULTS_JSON))
        );

        var runContext = runContextFactory.of(Map.of());

        var task = Run.builder()
            .url(Property.ofValue(wm.getHttpBaseUrl()))
            .apiToken(Property.ofValue("test-token"))
            .projectUuid(Property.ofValue("proj-123"))
            .chartUuid(Property.ofValue("chart-abc"))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();

        var output = task.run(runContext);

        assertThat(output.getSize(), is(2L));
        assertThat(output.getUri(), notNullValue());

        var storedRows = new ArrayList<Object>();
        try (var is = runContext.storage().getFile(output.getUri())) {
            FileSerde.read(is, storedRows::add);
        }

        assertThat(storedRows, hasSize(2));
        @SuppressWarnings("unchecked")
        var firstRow = (Map<String, Object>) storedRows.get(0);
        assertThat(firstRow.get("country"), is("US"));
        assertThat(firstRow.get("total_users"), is(1500));
    }

    @Test
    void missingProjectUuidThrowsIllegalArgumentException() {
        var runContext = runContextFactory.of(Map.of());

        var task = Run.builder()
            .url(Property.ofValue("http://localhost:8080"))
            .apiToken(Property.ofValue("test-token"))
            .chartUuid(Property.ofValue("chart-abc"))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("`projectUuid` is required"));
    }

    @Test
    void missingChartUuidThrowsIllegalArgumentException() {
        var runContext = runContextFactory.of(Map.of());

        var task = Run.builder()
            .url(Property.ofValue("http://localhost:8080"))
            .apiToken(Property.ofValue("test-token"))
            .projectUuid(Property.ofValue("proj-123"))
            .build();

        var ex = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(ex.getMessage(), containsString("`chartUuid` is required"));
    }
}
