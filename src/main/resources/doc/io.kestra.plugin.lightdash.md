# Lightdash Plugin for Kestra

Connect Kestra workflows with **Lightdash**, the open-source BI platform powered by your dbt semantic layer.

---

## Overview

The Lightdash plugin enables orchestration of business intelligence workflows directly alongside your ELT and data pipelines:

- **Projects**: Discover organization projects and refresh dbt models / semantic layer caches after upstream transformations.
- **Charts**: Run saved chart queries with flexible output handling (`FETCH`, `FETCH_ONE`, or streaming to Kestra storage via `STORE`).
- **Dashboards**: List and get dashboard definitions, or watch for dashboard modifications using an automated polling trigger with KV Store watermark isolation.

---

## Authentication

Lightdash uses Personal Access Tokens (API keys) passed in the `Authorization: ApiKey <token>` header.

Generate a Personal Access Token in your Lightdash web UI:
1. Go to **Settings** > **Personal Access Tokens**.
2. Create a new token and store it securely in Kestra secrets (e.g. `LIGHTDASH_API_TOKEN`).

---

## Tasks & Triggers

### 1. Projects
- `io.kestra.plugin.lightdash.projects.List`: Lists all projects within the authenticated organization.
- `io.kestra.plugin.lightdash.projects.Refresh`: Triggers a dbt compilation / cache refresh for a project and optionally waits for scheduler job completion.

### 2. Charts
- `io.kestra.plugin.lightdash.charts.List`: Lists all saved charts and visualizations in a project.
- `io.kestra.plugin.lightdash.charts.Run`: Executes a saved chart query and returns results into execution outputs or Kestra internal storage.

### 3. Dashboards
- `io.kestra.plugin.lightdash.dashboards.List`: Lists all dashboards in a project.
- `io.kestra.plugin.lightdash.dashboards.Get`: Retrieves the full dashboard JSON definition, including tiles, filters, and spaces.
- `io.kestra.plugin.lightdash.dashboards.Trigger`: Polling trigger that detects created or modified dashboards using namespace KV store watermarks.
