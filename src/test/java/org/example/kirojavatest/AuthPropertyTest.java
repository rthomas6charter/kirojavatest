package org.example.kirojavatest;

// Feature: file-management-app, Property 18: Cookie signing round-trip
// Feature: file-management-app, Property 19: Invalid cookie rejection
// Feature: file-management-app, Property 20: Constant-time comparison correctness
// **Validates: Requirements 9.4, 9.5, 9.7, 9.8**

import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterTry;
import net.jqwik.api.lifecycle.BeforeTry;
import org.example.kirojavatest.web.AuthController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Property tests for the authentication subsystem.
 *
 * Property 18: Cookie signing round-trip
 * For any username and valid expiry, signing a cookie payload and then validating
 * it SHALL return the original username.
 *
 * Property 19: Invalid cookie rejection
 * For any cookie with a tampered signature or expired timestamp, validation SHALL
 * return null (reject the cookie).
 *
 * Property 20: Constant-time comparison correctness
 * For any two strings, the constant-time comparison function SHALL return true if
 * and only if the strings are character-by-character identical.
 */
class AuthPropertyTest {

    private static final String TEST_SECRET = "test-secret-for-property-tests";

    private Method signMethod;
    private Method validateCookieMethod;
    private Method constantTimeEqualsMethod;

    @BeforeTry
    void setUp() throws Exception {
        // Set the cookie secret via system property so AppConfig picks it up
        System.setProperty("auth.cookie.secret", TEST_SECRET);

        // Access private static methods via reflection
        signMethod = AuthController.class.getDeclaredMethod("sign", String.class);
        signMethod.setAccessible(true);

        validateCookieMethod = AuthController.class.getDeclaredMethod("validateCookie", String.class);
        validateCookieMethod.setAccessible(true);

        constantTimeEqualsMethod = AuthController.class.getDeclaredMethod("constantTimeEquals", String.class, String.class);
        constantTimeEqualsMethod.setAccessible(true);
    }

    @AfterTry
    void tearDown() {
        System.clearProperty("auth.cookie.secret");
    }

    // --- Property 18: Cookie signing round-trip ---

    @Property(tries = 100)
    void cookieSigningRoundTrip(
            @ForAll("validUsernames") String username,
            @ForAll("futureExpiries") long expiresAt
    ) throws Exception {
        // Build the cookie value the same way setRememberCookie does
        String payload = username + "|" + expiresAt;
        String signature = (String) signMethod.invoke(null, payload);
        String cookieValue = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + signature;

        // Validate should return the original username
        String result = (String) validateCookieMethod.invoke(null, cookieValue);
        assert result != null : "validateCookie returned null for valid cookie with username: " + username;
        assert result.equals(username)
                : "Expected username '" + username + "' but got '" + result + "'";
    }

    // --- Property 19: Invalid cookie rejection ---

    @Property(tries = 100)
    void tamperedSignatureRejected(
            @ForAll("validUsernames") String username,
            @ForAll("futureExpiries") long expiresAt,
            @ForAll("tamperedSuffixes") String tamperSuffix
    ) throws Exception {
        // Build a valid cookie, then tamper with the signature
        String payload = username + "|" + expiresAt;
        String signature = (String) signMethod.invoke(null, payload);

        // Tamper with signature by appending/replacing characters
        String tamperedSignature = signature + tamperSuffix;
        String cookieValue = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + tamperedSignature;

        String result = (String) validateCookieMethod.invoke(null, cookieValue);
        assert result == null
                : "validateCookie should reject tampered signature but returned: " + result;
    }

    @Property(tries = 100)
    void expiredCookieRejected(
            @ForAll("validUsernames") String username,
            @ForAll("pastExpiries") long expiresAt
    ) throws Exception {
        // Build a cookie with an expired timestamp
        String payload = username + "|" + expiresAt;
        String signature = (String) signMethod.invoke(null, payload);
        String cookieValue = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + signature;

        String result = (String) validateCookieMethod.invoke(null, cookieValue);
        assert result == null
                : "validateCookie should reject expired cookie but returned: " + result;
    }

    // --- Property 20: Constant-time comparison correctness ---

    @Property(tries = 100)
    void constantTimeEqualsIdenticalStrings(
            @ForAll("arbitraryStrings") String s
    ) throws Exception {
        boolean result = (boolean) constantTimeEqualsMethod.invoke(null, s, s);
        assert result : "constantTimeEquals should return true for identical strings: '" + s + "'";
    }

    @Property(tries = 100)
    void constantTimeEqualsDifferentStrings(
            @ForAll("arbitraryStrings") String a,
            @ForAll("arbitraryStrings") String b
    ) throws Exception {
        boolean result = (boolean) constantTimeEqualsMethod.invoke(null, a, b);
        boolean expected = a.equals(b);
        assert result == expected
                : "constantTimeEquals(" + a + ", " + b + ") = " + result
                + " but String.equals = " + expected;
    }

    // --- Generators ---

    @Provide
    Arbitrary<String> validUsernames() {
        // Usernames: alphanumeric, no pipe or dot (which are delimiters in payload/cookie)
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withCharRange('0', '9')
                .ofMinLength(1)
                .ofMaxLength(20);
    }

    @Provide
    Arbitrary<Long> futureExpiries() {
        // Expiry timestamps in the future (current time + 1 hour to +30 days)
        long now = System.currentTimeMillis();
        return Arbitraries.longs().between(now + 3_600_000L, now + 30L * 86_400_000L);
    }

    @Provide
    Arbitrary<Long> pastExpiries() {
        // Expiry timestamps in the past (1 second to 365 days ago)
        long now = System.currentTimeMillis();
        return Arbitraries.longs().between(now - 365L * 86_400_000L, now - 1000L);
    }

    @Provide
    Arbitrary<String> tamperedSuffixes() {
        // Non-empty strings to append to signatures to ensure they become invalid
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('0', '9')
                .ofMinLength(1)
                .ofMaxLength(5);
    }

    @Provide
    Arbitrary<String> arbitraryStrings() {
        // General ASCII strings for testing constant-time comparison
        return Arbitraries.strings()
                .withCharRange(' ', '~')
                .ofMinLength(0)
                .ofMaxLength(30);
    }
}
