package org.example.kirojavatest;

import org.example.kirojavatest.api.ApiController;
import org.example.kirojavatest.web.AuthController;
import org.example.kirojavatest.web.HomeController;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.github.jknack.handlebars.io.ClassPathTemplateLoader;
import com.github.jknack.handlebars.io.TemplateLoader;
import io.javalin.Javalin;
import io.javalin.rendering.FileRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {

    private static final Logger accessLog = LoggerFactory.getLogger("ACCESS");
    private static final Logger pollingLog = LoggerFactory.getLogger("ACCESS.POLLING");

    private static final java.util.Set<String> POLLING_PATHS = java.util.Set.of(
            "/api/files", "/api/files/duplicates", "/api/summary", "/api/files/state"
    );

    public static void main(String[] args) {
        TemplateLoader loader = new ClassPathTemplateLoader("/templates", ".hbs");
        Handlebars handlebars = new Handlebars(loader);

        FileRenderer hbsRenderer = (filePath, model, ctx) -> {
            try {
                Template template = handlebars.compile(filePath.replace(".hbs", ""));
                return template.apply(model);
            } catch (Exception e) {
                throw new RuntimeException("Failed to render template: " + filePath, e);
            }
        };

        Javalin app = Javalin.create(config -> {
            config.fileRenderer(hbsRenderer);
            config.staticFiles.add("/public");
            AuthController.requireAuth(config);
            AuthController.register(config);
            HomeController.register(config);
            ApiController.register(config);

            // Access logging
            config.routes.before(ctx -> ctx.attribute("requestStart", System.currentTimeMillis()));
            config.routes.after(ctx -> {
                Long start = ctx.attribute("requestStart");
                long duration = start != null ? System.currentTimeMillis() - start : 0;
                String msg = "{} {} {} {}ms - {}";
                Object[] args2 = {ctx.method(), ctx.path(), ctx.status(), duration, ctx.ip()};
                if (POLLING_PATHS.contains(ctx.path())) {
                    pollingLog.info(msg, args2);
                } else {
                    accessLog.info(msg, args2);
                }
            });

            // Disable browser caching for static resources when configured
            boolean cacheEnabled = Boolean.parseBoolean(AppConfig.get("static.cache.enabled", "true"));
            if (!cacheEnabled) {
                config.routes.after(ctx -> {
                    String path = ctx.path();
                    if (path.startsWith("/css/") || path.startsWith("/js/")) {
                        ctx.header("Cache-Control", "no-cache, no-store, must-revalidate");
                        ctx.header("Pragma", "no-cache");
                        ctx.header("Expires", "0");
                    }
                });
            }
        });

        int port = AppConfig.getInt("server.port", 8080);
        app.start(port);
    }
}
