package io.kestra.webserver.controllers.api;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.utils.IdUtils;
import io.kestra.webserver.utils.PosthogUtil;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The AI Copilot driven end to end through an OpenAI-compatible endpoint, over the real
 * HTTP API rather than the service classes.
 *
 * <p>This is the path that was unreachable before: Kestra OSS refuses any provider type
 * but {@code gemini}, so an instance whose network reaches only an OpenAI-compatible
 * inference host had no provider it could both configure and call.
 */
@KestraTest(environments = { "openai-ai" })
@WireMockTest(httpPort = 28186)
class AiControllerOpenAiTest {
    @Inject
    @Client("/")
    HttpClient client;

    @BeforeEach
    void baseMocks(WireMockRuntimeInfo wmRuntimeInfo) {
        PosthogUtil.mockPosthog(wmRuntimeInfo);
    }

    /** An OpenAI Chat Completions response carrying {@code content} as the assistant reply. */
    private static String chatCompletion(String content) {
        return """
            {
              "id": "chatcmpl-test",
              "object": "chat.completion",
              "created": 1700000000,
              "model": "test-model",
              "choices": [
                {
                  "index": 0,
                  "message": { "role": "assistant", "content": "%s" },
                  "finish_reason": "stop"
                }
              ],
              "usage": { "prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15 }
            }""".formatted(content);
    }

    @Test
    void shouldExposeTheOpenAiCompatibleProviderOnTheProvidersEndpoint() {
        // The API surface the UI reads to populate its provider picker. It is generic over
        // registered services, so the new type has to appear here with no UI change --
        // this asserts that rather than assuming it.
        HttpResponse<List<AiController.AiProviderResponse>> response = client.toBlocking().exchange(
            HttpRequest.GET("/api/v1/main/ai/providers"),
            Argument.listOf(AiController.AiProviderResponse.class)
        );

        assertThat(response.getStatus().getCode()).isEqualTo(200);
        assertThat(response.body())
            .as("the configured openai-compatible provider must be listed")
            .anySatisfy(provider -> {
                assertThat(provider.id()).isEqualTo("openai-compatible");
                assertThat(provider.displayName()).isEqualTo("OpenAI-compatible endpoint");
                assertThat(provider.isDefault()).isTrue();
            });

        // The base test configuration also carries the legacy `kestra.ai.type: gemini`,
        // so this environment has both. That is the point of asserting it here: adding a
        // type must COEXIST with the legacy single-provider path, and the controller
        // sorts the default first, so the new provider is what the UI preselects.
        assertThat(response.body()).extracting(AiController.AiProviderResponse::id)
            .contains("openai-compatible", "gemini-legacy");
        assertThat(response.body().getFirst().id())
            .as("the default provider is returned first, and it is the configured one")
            .isEqualTo("openai-compatible");
    }

    @Test
    void shouldGenerateAFlowThroughAnOpenAiCompatibleEndpoint() {
        // Generation is two model calls -- the plugin finder, then the YAML builder --
        // so the stub is staged the way AiControllerTest stages the Gemini equivalent.
        stubFor(
            post(urlPathEqualTo("/v1/chat/completions"))
                .inScenario("OpenAI flow generation")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody(chatCompletion("io.kestra.plugin.core.log.Log")))
                .willSetStateTo("Tasks fetched")
        );

        String expectedFlow = "id: my-flow\\nnamespace: io.kestra.tests\\ntasks:\\n  - id: log\\n    type: io.kestra.plugin.core.log.Log";
        stubFor(
            post(urlPathEqualTo("/v1/chat/completions"))
                .inScenario("OpenAI flow generation")
                .whenScenarioStateIs("Tasks fetched")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody(chatCompletion(expectedFlow)))
        );

        HttpResponse<String> response = client.toBlocking().exchange(
            HttpRequest.POST(
                "/api/v1/main/ai/generate/flow",
                new AiController.FlowGenerationPrompt(IdUtils.create(), "Say 'hi'", "yaml", "io.kestra.tests", null)
            ),
            String.class
        );

        assertThat(response.getStatus().getCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(expectedFlow.replace("\\n", "\n"));

        // Proves the call actually left through the configured base-url rather than
        // reaching OpenAI or api.kestra.io.
        verify(moreThanOrExactly(1), postRequestedFor(urlPathEqualTo("/v1/chat/completions")));
    }

    @Test
    void shouldAddressTheProviderExplicitlyByItsConfiguredId() {
        stubFor(
            post(urlPathEqualTo("/v1/chat/completions"))
                .inScenario("Explicit provider id")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody(chatCompletion("io.kestra.plugin.core.log.Log")))
                .willSetStateTo("Tasks fetched")
        );
        stubFor(
            post(urlPathEqualTo("/v1/chat/completions"))
                .inScenario("Explicit provider id")
                .whenScenarioStateIs("Tasks fetched")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody(chatCompletion("id: explicit")))
        );

        HttpResponse<String> response = client.toBlocking().exchange(
            HttpRequest.POST(
                "/api/v1/main/ai/generate/flow",
                new AiController.FlowGenerationPrompt(IdUtils.create(), "Say 'hi'", "yaml", "io.kestra.tests", "openai-compatible")
            ),
            String.class
        );

        assertThat(response.getStatus().getCode()).isEqualTo(200);
    }
}
