package org.example.kirojavatest.services;

import org.example.kirojavatest.AppConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Client for the Ollama REST API (localhost).
 * Supports text generation, chat, and multimodal (image) prompts.
 */
public class OllamaClient {

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String defaultModel;
    private final Duration timeout;

    public OllamaClient() {
        this.baseUrl = AppConfig.get("ollama.base.url", "http://localhost:11434");
        this.defaultModel = AppConfig.get("ollama.model", "llava");
        int timeoutSecs = AppConfig.getInt("ollama.timeout.seconds", 120);
        this.timeout = Duration.ofSeconds(timeoutSecs);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * Generate a text completion.
     * @param prompt the text prompt
     * @return the generated response text
     */
    public String generate(String prompt) throws IOException, InterruptedException {
        return generate(prompt, defaultModel);
    }

    public String generate(String prompt, String model) throws IOException, InterruptedException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("stream", false);

        JsonNode response = post("/api/generate", body);
        return response.path("response").asText("");
    }

    /**
     * Generate a response from an image + text prompt (multimodal).
     * @param prompt the text prompt describing what to analyze
     * @param imagePath path to the image file
     * @return the generated response text
     */
    public String generateWithImage(String prompt, Path imagePath) throws IOException, InterruptedException {
        return generateWithImage(prompt, imagePath, defaultModel);
    }

    public String generateWithImage(String prompt, Path imagePath, String model) throws IOException, InterruptedException {
        byte[] imageBytes = Files.readAllBytes(imagePath);
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("stream", false);
        ArrayNode images = body.putArray("images");
        images.add(base64Image);

        JsonNode response = post("/api/generate", body);
        return response.path("response").asText("");
    }

    /**
     * Send a chat completion request.
     * @param messages list of chat messages (each with "role" and "content")
     * @return the assistant's response text
     */
    public String chat(List<ChatMessage> messages) throws IOException, InterruptedException {
        return chat(messages, defaultModel);
    }

    public String chat(List<ChatMessage> messages, String model) throws IOException, InterruptedException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        ArrayNode msgs = body.putArray("messages");
        for (ChatMessage msg : messages) {
            ObjectNode m = msgs.addObject();
            m.put("role", msg.role());
            m.put("content", msg.content());
            if (msg.images() != null && !msg.images().isEmpty()) {
                ArrayNode imgs = m.putArray("images");
                for (String img : msg.images()) {
                    imgs.add(img);
                }
            }
        }

        JsonNode response = post("/api/chat", body);
        return response.path("message").path("content").asText("");
    }

    /**
     * Generate embeddings for the given text.
     * @param text the input text
     * @return array of embedding values
     */
    public double[] embeddings(String text) throws IOException, InterruptedException {
        return embeddings(text, defaultModel);
    }

    public double[] embeddings(String text, String model) throws IOException, InterruptedException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("input", text);

        JsonNode response = post("/api/embed", body);
        JsonNode embeddingsNode = response.path("embeddings");
        if (embeddingsNode.isArray() && embeddingsNode.size() > 0) {
            JsonNode first = embeddingsNode.get(0);
            double[] result = new double[first.size()];
            for (int i = 0; i < first.size(); i++) {
                result[i] = first.get(i).asDouble();
            }
            return result;
        }
        return new double[0];
    }

    /**
     * List available models on the Ollama instance.
     * @return JSON node containing the models list
     */
    public JsonNode listModels() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tags"))
                .timeout(timeout)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(response.body());
    }

    /**
     * Check if the Ollama service is reachable.
     */
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // --- Internal ---

    private JsonNode post(String path, ObjectNode body) throws IOException, InterruptedException {
        String json = mapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("Ollama returned HTTP " + response.statusCode() + ": " + response.body());
        }
        return mapper.readTree(response.body());
    }

    /** Simple chat message record. */
    public record ChatMessage(String role, String content, List<String> images) {
        public ChatMessage(String role, String content) {
            this(role, content, null);
        }
    }
}
