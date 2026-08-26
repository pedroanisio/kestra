package io.kestra.webserver.services.ai.openai;

import java.time.Duration;
import java.util.Map;

import io.kestra.webserver.services.ai.AiConfiguration;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.bind.annotation.Bindable;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Configuration for an OpenAI or OpenAI-compatible Copilot provider.
 *
 * <p>The field that matters most here is {@code baseUrl}. Any endpoint implementing the
 * OpenAI Chat Completions API — DigitalOcean Serverless Inference, vLLM, LM Studio,
 * Ollama's compatibility layer, an internal gateway — is reachable by pointing it there;
 * left unset it is OpenAI itself.
 *
 * @see OpenAiAiService
 */
public record OpenAiConfiguration(
    @Schema(description = "Base URL of the OpenAI-compatible API, including the version path (e.g. https://inference.do-ai.run/v1). Defaults to OpenAI.", nullable = true)
    @Nullable String baseUrl,
    String apiKey,
    @Bindable(defaultValue = OpenAiConfiguration.DEFAULT_MODEL_NAME) String modelName,
    @Bindable(defaultValue = "0.7") Double temperature,
    @Nullable Double topP,
    @Bindable(defaultValue = "8000") int maxTokens,
    @Schema(description = "Sent with every request. Use for gateways that require an extra header alongside the bearer token.", nullable = true)
    @Nullable Map<String, String> customHeaders,
    @Schema(description = "OpenAI's strict function-calling mode. Off by default because most OpenAI-COMPATIBLE endpoints do not implement it and reject the request.", nullable = true)
    @Bindable(defaultValue = "false") boolean strictTools,
    @Schema(description = "OpenAI's strict JSON-schema mode. Off by default for the same reason as strictTools.", nullable = true)
    @Bindable(defaultValue = "false") boolean strictJsonSchema,
    @Schema(description = "Client certificate in PEM format, for an endpoint behind mutual TLS.", nullable = true)
    @Nullable String clientPem,
    @Schema(description = "Not required but can be useful to add further trust", nullable = true)
    @Nullable String caPem,
    @Bindable(defaultValue = "false") boolean logRequests,
    @Bindable(defaultValue = "false") boolean logResponses,
    Duration timeout) implements AiConfiguration {

    static final String DEFAULT_MODEL_NAME = "gpt-4o-mini";
    static final int DEFAULT_MAX_TOKENS = 8000;
    static final double DEFAULT_TEMPERATURE = 0.7;

    /**
     * Micronaut's {@code @Bindable} defaults apply when a property is ABSENT from the
     * configuration. They do not apply when the record is built directly — which is what
     * {@code AiServiceManager} does, by converting the raw configuration map with Jackson.
     * Absent keys arrive here as null, so the defaults are restated in the compact
     * constructor. {@link io.kestra.webserver.services.ai.gemini.GeminiConfiguration}
     * does the same, for the same reason.
     */
    public OpenAiConfiguration {
        if (modelName == null)
            modelName = DEFAULT_MODEL_NAME;
        if (temperature == null)
            temperature = DEFAULT_TEMPERATURE;
        if (maxTokens == 0)
            maxTokens = DEFAULT_MAX_TOKENS;
    }

    @Override
    public String type() {
        return OpenAiAiService.TYPE;
    }
}
