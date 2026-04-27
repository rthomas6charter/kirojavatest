package org.example.kirojavatest.services;

import org.example.kirojavatest.AppConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * Client for the face detection/recognition sidecar service.
 */
public class FaceServiceClient {

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final Duration timeout;

    public FaceServiceClient() {
        this.baseUrl = AppConfig.get("face.service.url", "http://localhost:5000");
        int timeoutSecs = AppConfig.getInt("face.service.timeout.seconds", 60);
        this.timeout = Duration.ofSeconds(timeoutSecs);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    /** Check if the face service is reachable. */
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** Detect faces in an image file. Returns JSON with face bounding boxes, scores, age, gender, embeddings. */
    public JsonNode detect(Path imagePath) throws IOException, InterruptedException {
        return postMultipart("/detect", "file", imagePath);
    }

    /** Detect faces and match against known faces database. */
    public JsonNode identify(Path imagePath) throws IOException, InterruptedException {
        return postMultipart("/identify", "file", imagePath);
    }

    /** Register a face with a name for future identification. */
    public JsonNode register(String name, Path imagePath) throws IOException, InterruptedException {
        return postMultipart("/register?name=" + name, "file", imagePath);
    }

    /** List all registered face names. */
    public JsonNode listKnown() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/known"))
                .timeout(timeout)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(response.body());
    }

    /** Remove all registered faces for a given name. */
    public JsonNode removeKnown(String name) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/known/" + name))
                .timeout(timeout)
                .method("DELETE", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(response.body());
    }

    // --- Multipart upload helper ---

    private JsonNode postMultipart(String path, String fieldName, Path filePath) throws IOException, InterruptedException {
        String boundary = UUID.randomUUID().toString();
        byte[] fileBytes = Files.readAllBytes(filePath);
        String fileName = filePath.getFileName().toString();
        String mimeType = Files.probeContentType(filePath);
        if (mimeType == null) mimeType = "application/octet-stream";

        byte[] body = buildMultipartBody(boundary, fieldName, fileName, mimeType, fileBytes);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("Face service returned HTTP " + response.statusCode() + ": " + response.body());
        }
        return mapper.readTree(response.body());
    }

    private byte[] buildMultipartBody(String boundary, String fieldName, String fileName, String mimeType, byte[] fileBytes) throws IOException {
        var baos = new java.io.ByteArrayOutputStream();
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + mimeType + "\r\n\r\n";
        baos.write(header.getBytes());
        baos.write(fileBytes);
        String footer = "\r\n--" + boundary + "--\r\n";
        baos.write(footer.getBytes());
        return baos.toByteArray();
    }
}
