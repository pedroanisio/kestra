package io.kestra.webserver.services.ai.openai;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.kestra.core.docs.JsonSchemaGenerator;
import io.kestra.core.plugins.PluginRegistry;
import io.kestra.core.services.InstanceService;
import io.kestra.core.utils.VersionProvider;
import io.kestra.webserver.services.ai.AiService;
import io.kestra.webserver.services.ai.NamespaceContextTool;
import io.kestra.webserver.services.posthog.PosthogService;
import io.kestra.webserver.utils.HttpClientUtils;

import dev.langchain4j.http.client.HttpClientBuilderLoader;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;

/**
 * Copilot provider for OpenAI and any OpenAI-compatible Chat Completions endpoint.
 *
 * <p>Kestra's open-source edition ships only the Gemini provider; every other type is
 * refused as Enterprise. That is a product boundary rather than a technical one — the
 * generation logic in {@link AiService} is provider-agnostic and the only thing a provider
 * supplies is a {@link ChatModel}. This class supplies one built by LangChain4j's OpenAI
 * module, whose {@code baseUrl} accepts any compatible endpoint.
 *
 * <p>REQUIRES TOOL CALLING. {@link AiService#flowYamlBuilder} binds the namespace context
 * tool to the model, so a model without function-calling support will fail at generation
 * rather than at configuration. Check the endpoint's model list before pointing this at it.
 */
@Slf4j
public class OpenAiAiService extends AiService<OpenAiConfiguration> {
    public static final String TYPE = "openai";

    /** OpenAI itself, used when no {@code baseUrl} is configured. */
    static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    public OpenAiAiService(PluginRegistry pluginRegistry, JsonSchemaGenerator jsonSchemaGenerator, VersionProvider versionProvider, InstanceService instanceService,
        PosthogService posthogService, NamespaceContextTool namespaceContextTool, String displayName, List<ChatModelListener> listeners, OpenAiConfiguration openAiConfiguration) {
        super(pluginRegistry, jsonSchemaGenerator, versionProvider, instanceService, posthogService, namespaceContextTool, TYPE, displayName, listeners, openAiConfiguration);
    }

    @Override
    protected String baseUrl() {
        return getAiConfiguration().baseUrl() != null ? getAiConfiguration().baseUrl() : DEFAULT_BASE_URL;
    }

    @Override
    public ChatModel chatModel(List<ChatModelListener> listeners) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
            // baseUrl(), not the raw configuration value: an absent baseUrl must mean
            // OpenAI rather than whatever the client library would default to, and the
            // same resolved value is what the metadata listener reports.
            .baseUrl(this.baseUrl())
            .listeners(listeners)
            .modelName(getAiConfiguration().modelName())
            .apiKey(getAiConfiguration().apiKey())
            .temperature(getAiConfiguration().temperature())
            .topP(getAiConfiguration().topP())
            .maxTokens(getAiConfiguration().maxTokens())
            .strictTools(getAiConfiguration().strictTools())
            .strictJsonSchema(getAiConfiguration().strictJsonSchema())
            .logRequests(getAiConfiguration().logRequests())
            .logResponses(getAiConfiguration().logResponses())
            .timeout(getAiConfiguration().timeout());

        if (getAiConfiguration().customHeaders() != null) {
            builder = builder.customHeaders(getAiConfiguration().customHeaders());
        }

        if (getAiConfiguration().clientPem() != null) {
            try (
                ByteArrayInputStream is = new ByteArrayInputStream(getAiConfiguration().clientPem().getBytes(StandardCharsets.UTF_8));
                ByteArrayInputStream caPem = getAiConfiguration().caPem() == null ? null : new ByteArrayInputStream(getAiConfiguration().caPem().getBytes(StandardCharsets.UTF_8))
            ) {
                JdkHttpClientBuilder jdkHttpClientBuilder = ((JdkHttpClientBuilder) HttpClientBuilderLoader.loadHttpClientBuilder()).httpClientBuilder(
                    HttpClientUtils.withPemCertificate(is, caPem)
                );

                builder = builder.httpClientBuilder(jdkHttpClientBuilder);
            } catch (Exception e) {
                throw new IllegalArgumentException("Exception while trying to setup AI Service certificates", e);
            }
        }

        return builder.build();
    }
}
