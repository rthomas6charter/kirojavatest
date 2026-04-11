package org.example.kirojavatest.web;

import org.example.kirojavatest.AppConfig;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class AuthController {

    private static final String COOKIE_NAME = "remember";

    public static void register(JavalinConfig config) {
        // Show login form
        config.routes.get("/login", ctx -> {
            if (ctx.sessionAttribute("user") != null) {
                ctx.redirect("/");
                return;
            }
            ctx.render("login.hbs", Map.of());
        });

        // Handle login submission
        config.routes.post("/login", ctx -> {
            String username = ctx.formParam("username");
            String password = ctx.formParam("password");

            String validUser = AppConfig.get("auth.username", "admin");
            String validPass = AppConfig.get("auth.password", "admin");

            if (validUser.equals(username) && validPass.equals(password)) {
                ctx.sessionAttribute("user", username);
                setRememberCookie(ctx, username);
                ctx.redirect("/");
            } else {
                Map<String, Object> model = new HashMap<>();
                model.put("error", "Invalid username or password");
                model.put("username", username != null ? username : "");
                ctx.render("login.hbs", model);
            }
        });

        // Logout — clear session and cookie
        config.routes.get("/logout", ctx -> {
            ctx.req().getSession().invalidate();
            ctx.removeCookie(COOKIE_NAME);
            ctx.redirect("/login");
        });
    }

    /** Before-handler: restore session from cookie or redirect to /login */
    public static void requireAuth(JavalinConfig config) {
        config.routes.before(ctx -> {
            String path = ctx.path();
            if (path.equals("/login") || path.startsWith("/css/")
                    || path.startsWith("/js/") || path.startsWith("/api/")) {
                return;
            }

            // Already authenticated in this session
            if (ctx.sessionAttribute("user") != null) {
                return;
            }

            // Try to restore from remember cookie
            String cookieVal = ctx.cookie(COOKIE_NAME);
            if (cookieVal != null) {
                String username = validateCookie(cookieVal);
                if (username != null) {
                    ctx.sessionAttribute("user", username);
                    return;
                }
                // Invalid/expired cookie — clear it
                ctx.removeCookie(COOKIE_NAME);
            }

            ctx.redirect("/login");
        });
    }

    // --- Cookie helpers ---

    private static void setRememberCookie(Context ctx, String username) {
        int maxAgeDays = AppConfig.getInt("auth.cookie.maxage.days", 30);
        long expiresAt = System.currentTimeMillis() + (long) maxAgeDays * 86_400_000L;
        String payload = username + "|" + expiresAt;
        String signature = sign(payload);
        String value = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + signature;

        Cookie cookie = new Cookie(COOKIE_NAME, value);
        cookie.setMaxAge(maxAgeDays * 86_400);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSameSite(SameSite.LAX);
        ctx.cookie(cookie);
    }

    /** Returns the username if the cookie is valid and not expired, null otherwise. */
    private static String validateCookie(String cookieValue) {
        try {
            String[] parts = cookieValue.split("\\.", 2);
            if (parts.length != 2) return null;

            String payload = new String(
                    Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String expectedSig = sign(payload);
            if (!constantTimeEquals(expectedSig, parts[1])) return null;

            String[] fields = payload.split("\\|", 2);
            if (fields.length != 2) return null;

            long expiresAt = Long.parseLong(fields[1]);
            if (System.currentTimeMillis() > expiresAt) return null;

            return fields[0];
        } catch (Exception e) {
            return null;
        }
    }

    private static String sign(String data) {
        try {
            String secret = AppConfig.get("auth.cookie.secret", "change-me-in-production");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign cookie", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
