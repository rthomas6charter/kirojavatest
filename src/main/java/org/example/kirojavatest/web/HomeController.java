package org.example.kirojavatest.web;

import org.example.kirojavatest.AppConfig;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HomeController {

    private static final List<Map<String, String>> MOCK_DATA = List.of(
        Map.of("name", "Alpine Meadow", "category", "Landscape", "location", "Colorado", "dateAdded", "2026-01-15", "status", "Active", "statusClass", "active"),
        Map.of("name", "Cedar Workshop", "category", "Craft", "location", "Vermont", "dateAdded", "2025-11-03", "status", "Pending", "statusClass", "pending"),
        Map.of("name", "River Stone Co", "category", "Materials", "location", "Oregon", "dateAdded", "2026-02-20", "status", "Active", "statusClass", "active"),
        Map.of("name", "Birch & Fern", "category", "Botanical", "location", "Maine", "dateAdded", "2025-09-12", "status", "Inactive", "statusClass", "inactive"),
        Map.of("name", "Terracotta Labs", "category", "Research", "location", "New Mexico", "dateAdded", "2026-03-08", "status", "Active", "statusClass", "active")
    );

    /** Build a template model map that always includes the logged-in user and app name. */
    static Map<String, Object> model(Context ctx, Map<String, Object> extra) {
        Map<String, Object> m = new HashMap<>(extra);
        String user = ctx.sessionAttribute("user");
        if (user != null) {
            m.put("user", user);
        }
        m.put("appName", AppConfig.get("app.name", "App"));
        return m;
    }

    public static void register(JavalinConfig config) {
        config.routes.get("/", ctx -> ctx.render("home.hbs", model(ctx, Map.of(
                "title", "Home",
                "isHome", true
        ))));

        config.routes.get("/about", ctx -> ctx.render("about.hbs", model(ctx, Map.of(
                "title", "About",
                "isAbout", true
        ))));

        config.routes.get("/settings", ctx -> ctx.render("settings.hbs", model(ctx, Map.of(
                "title", "Settings",
                "isSettings", true
        ))));

        config.routes.get("/search", ctx -> {
            String query = ctx.queryParam("q");
            List<Map<String, String>> results;
            if (query != null && !query.isBlank()) {
                String q = query.toLowerCase();
                results = MOCK_DATA.stream()
                        .filter(row -> row.values().stream().anyMatch(v -> v.toLowerCase().contains(q)))
                        .toList();
            } else {
                results = MOCK_DATA;
            }
            ctx.render("search.hbs", model(ctx, Map.of(
                    "title", "Search",
                    "isSearch", true,
                    "query", query != null ? query : "",
                    "results", results
            )));
        });

        config.routes.get("/faces", ctx -> ctx.render("faces.hbs", model(ctx, Map.of(
                "title", "Faces",
                "isFaces", true
        ))));

        config.routes.get("/admin", ctx -> ctx.render("admin.hbs", model(ctx, Map.of(
                "title", "Admin",
                "isAdmin", true
        ))));
    }
}
