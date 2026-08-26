package io.kestra.webserver.services.ai.openai;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.kestra.core.docs.JsonSchemaGenerator;
import io.kestra.core.plugins.PluginRegistry;
import io.kestra.core.services.InstanceService;
import io.kestra.core.utils.VersionProvider;
import io.kestra.webserver.services.ai.NamespaceContextTool;
import io.kestra.webserver.services.posthog.PosthogService;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class OpenAiAiServiceTest {
    @Mock
    PluginRegistry pluginRegistry;
    @Mock
    JsonSchemaGenerator jsonSchemaGenerator;
    @Mock
    VersionProvider versionProvider;
    @Mock
    InstanceService instanceService;
    @Mock
    PosthogService posthogService;
    @Mock
    NamespaceContextTool namespaceContextTool;

    private OpenAiAiService service(OpenAiConfiguration configuration) {
        return new OpenAiAiService(
            pluginRegistry, jsonSchemaGenerator, versionProvider, instanceService, posthogService,
            namespaceContextTool, "Test provider", List.of(), configuration
        );
    }

    private OpenAiConfiguration configuration(String baseUrl, String modelName) {
        return new OpenAiConfiguration(
            baseUrl, "fake-key", modelName, null, null, 0, null, false, false, null, null, false, false, null
        );
    }

    @Test
    void shouldPointAtTheConfiguredEndpoint() {
        // The entire reason this provider exists: an endpoint that is not OpenAI.
        OpenAiAiService service = service(configuration("https://inference.do-ai.run/v1", "anthropic-claude-5-sonnet"));

        assertThat(service.baseUrl()).isEqualTo("https://inference.do-ai.run/v1");
    }

    @Test
    void shouldFallBackToOpenAi_whenNoBaseUrlIsConfigured() {
        OpenAiAiService service = service(configuration(null, "gpt-4o-mini"));

        assertThat(service.baseUrl()).isEqualTo(OpenAiAiService.DEFAULT_BASE_URL);
    }

    @Test
    void shouldBuildAnOpenAiChatModelCarryingTheConfiguredParameters() {
        OpenAiConfiguration configuration = new OpenAiConfiguration(
            "https://inference.do-ai.run/v1", "fake-key", "anthropic-claude-5-sonnet",
            0.2, 0.9, 4096, null, false, false, null, null, false, false, Duration.ofSeconds(30)
        );

        ChatModel model = service(configuration).chatModel(List.of());

        assertThat(model).isInstanceOf(OpenAiChatModel.class);
        assertThat(model.defaultRequestParameters().modelName()).isEqualTo("anthropic-claude-5-sonnet");
        assertThat(model.defaultRequestParameters().temperature()).isEqualTo(0.2);
        assertThat(model.defaultRequestParameters().topP()).isEqualTo(0.9);
        assertThat(model.defaultRequestParameters().maxOutputTokens()).isEqualTo(4096);
    }

    @Test
    void shouldApplyDefaults_whenOptionalValuesAreAbsent() {
        // Absent keys reach the record as nulls (Jackson conversion, not Micronaut
        // binding), so the compact constructor is the only thing supplying defaults.
        OpenAiConfiguration configuration = configuration("https://inference.do-ai.run/v1", null);

        assertThat(configuration.modelName()).isEqualTo(OpenAiConfiguration.DEFAULT_MODEL_NAME);
        assertThat(configuration.temperature()).isEqualTo(OpenAiConfiguration.DEFAULT_TEMPERATURE);
        assertThat(configuration.maxTokens()).isEqualTo(OpenAiConfiguration.DEFAULT_MAX_TOKENS);
        assertThat(configuration.type()).isEqualTo(OpenAiAiService.TYPE);
    }

    @Test
    void shouldDefaultStrictModesOff_becauseCompatibleEndpointsRejectThem() {
        // OpenAI's `strict` function-calling and JSON-schema modes are not implemented by
        // most OpenAI-COMPATIBLE endpoints, which reject the request outright. Defaulting
        // them on would make the common case for this provider fail.
        OpenAiConfiguration configuration = configuration("https://inference.do-ai.run/v1", "some-model");

        assertThat(configuration.strictTools()).isFalse();
        assertThat(configuration.strictJsonSchema()).isFalse();
    }

    @Test
    void shouldAcceptCustomHeaders_forGatewaysThatRequireThem() {
        OpenAiConfiguration configuration = new OpenAiConfiguration(
            "https://gateway.internal/v1", "fake-key", "some-model", null, null, 0,
            Map.of("X-Tenant", "inova"), false, false, null, null, false, false, null
        );

        ChatModel model = service(configuration).chatModel(List.of());

        assertThat(configuration.customHeaders()).containsEntry("X-Tenant", "inova");
        assertThat(model).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void shouldReportTheProviderTypeAsOpenAi() {
        assertThat(OpenAiAiService.TYPE).isEqualTo("openai");
        assertThat(service(configuration(null, null)).displayName()).isEqualTo("Test provider");
    }
}
