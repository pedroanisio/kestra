package io.kestra.webserver.services.ai;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.kestra.core.docs.JsonSchemaGenerator;
import io.kestra.core.plugins.PluginRegistry;
import io.kestra.core.services.InstanceService;
import io.kestra.core.utils.VersionProvider;
import io.kestra.webserver.services.posthog.PosthogService;

import io.micronaut.core.value.PropertyResolver;
import io.micronaut.http.client.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiServiceManagerTest {

    @Mock
    HttpClient apiHttpClient;
    @Mock
    io.micronaut.http.client.BlockingHttpClient blockingHttpClient;
    @Mock
    AiProvidersConfiguration providersConfiguration;
    @Mock
    PropertyResolver propertyResolver;
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

    private AiServiceManager buildManager(List<AiProviderConfiguration> providers) {
        when(providersConfiguration.providers()).thenReturn(providers);

        return new AiServiceManager(
            apiHttpClient,
            providersConfiguration,
            propertyResolver,
            pluginRegistry,
            jsonSchemaGenerator,
            versionProvider,
            instanceService,
            posthogService,
            List.of(),
            namespaceContextTool
        );
    }

    @Test
    void hasConfiguredProviderShouldBeFalseWhenNoProvidersConfigured() {
        when(apiHttpClient.toBlocking()).thenReturn(blockingHttpClient);

        AiServiceManager manager = buildManager(null);

        assertThat(manager.hasConfiguredProvider()).isFalse();
    }

    @Test
    void hasConfiguredProviderShouldBeFalseWhenProviderListEmpty() {
        when(apiHttpClient.toBlocking()).thenReturn(blockingHttpClient);

        AiServiceManager manager = buildManager(List.of());

        assertThat(manager.hasConfiguredProvider()).isFalse();
    }

    @Test
    void shouldCreateProvider_whenTypeIsOpenAiCompatible() {
        // The point of this fork's AI change. Kestra OSS hard-codes the provider type
        // to "gemini" and refuses everything else, so this instance -- whose egress
        // allowlist reaches exactly one host, an OpenAI-compatible inference endpoint --
        // has no provider it is both allowed to configure and able to reach.
        AiProviderConfiguration openAiProvider = new AiProviderConfiguration(
            "do-inference",
            "DigitalOcean Serverless Inference",
            "openai",
            true,
            java.util.Map.of(
                "baseUrl", "https://inference.do-ai.run/v1",
                "modelName", "anthropic-claude-5-sonnet",
                "apiKey", "fake-key"
            )
        );

        AiServiceManager manager = buildManager(List.of(openAiProvider));

        assertThat(manager.hasConfiguredProvider())
            .as("an openai-compatible provider must be accepted")
            .isTrue();
        assertThat(manager.getAiService("do-inference"))
            .as("the provider must be registered under its configured id")
            .isNotNull();
        assertThat(manager.getAiService("do-inference").displayName())
            .isEqualTo("DigitalOcean Serverless Inference");
        assertThat(manager.getDefaultProviderId())
            .as("isDefault must be honoured for openai providers as it is for gemini")
            .isEqualTo("do-inference");
    }

    @Test
    void hasConfiguredProviderShouldBeTrueWhenGeminiProviderConfigured() {
        AiProviderConfiguration geminiProvider = new AiProviderConfiguration(
            "gemini-test",
            "Gemini",
            "gemini",
            true,
            java.util.Map.of("modelName", "gemini-2.5-flash", "apiKey", "fake-key")
        );

        AiServiceManager manager = buildManager(List.of(geminiProvider));

        assertThat(manager.hasConfiguredProvider()).isTrue();
    }

    @Test
    void shouldRegisterGeminiAndOpenAiSideBySide() {
        // Adding a type must not make the loop exclusive. Both are built, both are
        // addressable by id, and only the one flagged isDefault is the default.
        AiProviderConfiguration gemini = new AiProviderConfiguration(
            "gemini-test", "Gemini", "gemini", false,
            java.util.Map.of("modelName", "gemini-2.5-flash", "apiKey", "fake-key")
        );
        AiProviderConfiguration openAi = new AiProviderConfiguration(
            "do-inference", "DigitalOcean", "openai", true,
            java.util.Map.of("baseUrl", "https://inference.do-ai.run/v1", "modelName", "openai-gpt-5", "apiKey", "fake-key")
        );

        AiServiceManager manager = buildManager(List.of(gemini, openAi));

        assertThat(manager.getAllAiServices()).containsOnlyKeys("gemini-test", "do-inference");
        assertThat(manager.getDefaultProviderId()).isEqualTo("do-inference");
        assertThat(manager.getDefaultAiService()).isSameAs(manager.getAiService("do-inference"));
    }

    @Test
    void shouldUseTheOpenAiDefaults_whenOnlyBaseUrlAndKeyAreGiven() {
        // Everything except the endpoint and the key is optional. The Jackson conversion
        // path leaves absent keys null, so the record's compact constructor is what has to
        // supply the defaults -- Micronaut's @Bindable does not run here.
        AiProviderConfiguration openAi = new AiProviderConfiguration(
            "minimal", "Minimal", "openai", true,
            java.util.Map.of("baseUrl", "https://inference.do-ai.run/v1", "apiKey", "fake-key")
        );

        AiServiceManager manager = buildManager(List.of(openAi));

        assertThat(manager.getAiService("minimal")).isNotNull();
    }

    @Test
    void shouldIgnoreUnknownConfigurationKeys_forOpenAiProviders() {
        // FAIL_ON_UNKNOWN_PROPERTIES is disabled for this conversion, so a key meant for
        // another provider is dropped rather than failing the whole instance at startup.
        AiProviderConfiguration openAi = new AiProviderConfiguration(
            "lenient", "Lenient", "openai", true,
            java.util.Map.of("baseUrl", "https://inference.do-ai.run/v1", "apiKey", "fake-key", "topK", 40, "notAKey", "ignored")
        );

        AiServiceManager manager = buildManager(List.of(openAi));

        assertThat(manager.getAiService("lenient")).isNotNull();
    }

    @Test
    void shouldRejectAProviderTypeThisEditionCannotServe() {
        // Regression guard on the OTHER half of the change: widening the accepted set to
        // two types must not widen it to all types. A type this build cannot serve is
        // thrown rather than logged, so the instance fails at startup instead of showing
        // a Copilot that fails on the first click.
        AiProviderConfiguration anthropic = new AiProviderConfiguration(
            "anthropic-test", "Anthropic", "anthropic", true,
            java.util.Map.of("modelName", "claude-opus-4-5", "apiKey", "fake-key")
        );

        assertThatThrownBy(() -> buildManager(List.of(anthropic)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("anthropic")
            .hasMessageContaining("gemini")
            .hasMessageContaining("openai")
            .hasMessageContaining("Enterprise Edition");
    }

    @Test
    void shouldRejectANullProviderType_withoutThrowingNullPointerException() {
        // `type` is unvalidated user configuration. The upstream form was
        // `!"gemini".equals(type)`, which is null-safe; a switch or a
        // `type.equals(...)` would report a missing type as an internal error instead of
        // a configuration one. This pins the null-safety in place.
        AiProviderConfiguration noType = new AiProviderConfiguration(
            "no-type", "No type", null, true,
            java.util.Map.of("apiKey", "fake-key")
        );

        assertThatThrownBy(() -> buildManager(List.of(noType)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("null");
    }

    @Test
    void shouldSkipAProviderWithNoConfiguration_ratherThanFailStartup() {
        // Pre-existing behaviour, asserted because the openai branch sits after this
        // guard and must not move it: a provider with no configuration block is logged
        // and skipped, and the instance still starts.
        AiProviderConfiguration noConfig = new AiProviderConfiguration(
            "no-config", "No config", "openai", true, null
        );

        AiServiceManager manager = buildManager(List.of(noConfig));

        assertThat(manager.getAllAiServices()).isEmpty();
        assertThat(manager.hasConfiguredProvider()).isFalse();
    }
}
