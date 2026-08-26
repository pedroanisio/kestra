# AI Copilot against an OpenAI-compatible endpoint

A fork patch adding `openai` as a Copilot provider type. Part of a permanent
divergence — see [`../../FORK.md`](../../FORK.md).

## What upstream does, and why it is a problem here

`AiServiceManager.createAiService` accepts exactly one provider type and throws on
every other:

```java
if (!"gemini".equals(type)) {
    throw new IllegalArgumentException(
        "Unsupported AI provider type '" + type + "' for Kestra OSS. Only 'gemini' is supported. ...");
}
```

That is a product boundary, not a technical one. `AiService<T>` — which holds all the
generation logic — declares exactly one abstract method, `chatModel(listeners)`. A
provider supplies a LangChain4j `ChatModel` and nothing else.

With no provider configured, the manager falls back to `ApiAiService`, which posts to
`api.kestra.io`. On a cluster whose egress is an allowlist of named hosts, that call
cannot complete: the provider registers cleanly at startup, the UI offers a Copilot,
and the first click fails on the network. The only sanctioned alternative, `gemini`,
means a second inference vendor, a second key, and another entry in that list.

## What this patch adds

| File | Role |
| --- | --- |
| `webserver/.../ai/openai/OpenAiConfiguration.java` | Configuration record. `baseUrl` is the field that matters. |
| `webserver/.../ai/openai/OpenAiAiService.java` | Builds a LangChain4j `OpenAiChatModel` pointed at that URL. |
| `webserver/.../ai/AiServiceManager.java` | Accepts `openai` alongside `gemini`; still rejects everything else. |
| `webserver/build.gradle` | `dev.langchain4j:langchain4j-open-ai`, versioned by the BOM `:platform` already imports. |

Nothing else changes. The `/api/v1/main/ai/providers` endpoint is generic over
registered services, so the new provider appears in the UI picker with no frontend
change — asserted by `AiControllerOpenAiTest`, not assumed.

## Configuration

```yaml
kestra:
  ai:
    enabled: true
    providers:
      - id: do-inference
        display-name: DigitalOcean Serverless Inference
        type: openai
        isDefault: true
        configuration:
          base-url: https://inference.do-ai.run/v1
          model-name: anthropic-claude-5-sonnet
          api-key: "{{ your key }}"
```

| Key | Default | Notes |
| --- | --- | --- |
| `base-url` | `https://api.openai.com/v1` | Include the version path. This is the whole point of the provider. |
| `api-key` | — | Sent as the bearer token. |
| `model-name` | `gpt-4o-mini` | **Set it.** The default is an OpenAI model name and will not exist on a compatible endpoint. |
| `temperature` | `0.7` | |
| `top-p` | unset | |
| `max-tokens` | `8000` | |
| `strict-tools` | `false` | OpenAI's strict function-calling mode. Most compatible endpoints reject it. |
| `strict-json-schema` | `false` | Same reasoning. |
| `custom-headers` | unset | For gateways needing a header beyond the bearer token. |
| `client-pem` / `ca-pem` | unset | Client certificate and extra trust, for mutual TLS or a private CA. |
| `timeout` | client default | |
| `log-requests` / `log-responses` | `false` | Logs prompts and completions. Leave off where prompts may carry data. |

Multiple providers may be configured at once, including alongside a `gemini` one.
`isDefault` picks which serves a request that names no provider; the API accepts an
explicit `providerId` per request.

### The model must support tool calling

`AiService.flowYamlBuilder` binds the namespace context tool to the model. A model
without function-calling support fails at generation time, not at configuration time,
and the error surfaces as a failed Copilot request rather than a startup problem.
Check the endpoint's model list first.

### Why the strict modes default to off

OpenAI's `strict` function-calling and JSON-schema modes are extensions many
OpenAI-*compatible* servers do not implement, and they reject the request outright
when sent. Defaulting them on would break the common case for this provider. Turn
them on only against OpenAI itself.

## Enabling it on the deployment

Secrets reach Kestra as base64 environment variables read once at startup, so this is
a configuration change *and* a restart — which, with the `Recreate` strategy and a
ReadWriteOnce volume, is a short outage. Plan it accordingly.

1. Build and push an image from a branch carrying this patch (`make build-docker`).
2. Add the key to the flow-secrets Secret in `k8s-do-control`.
3. Add the `kestra.ai.providers` block above to the Kestra configuration in
   `manifests/apps/kestra/kestra.yaml`, reading the key from the environment.
4. Update the image digest, `make deploy-kestra`, and confirm with:

```bash
curl -s -u "$USER:$PASS" https://kestra.xaai.ai/api/v1/main/ai/providers | jq .
# expect: [{"id":"do-inference","displayName":"...","isDefault":true}]
```

No egress change is needed: `inference.do-ai.run` is already on the Cilium
allowlist.

## Migration and rollback

There is nothing to migrate. The patch is additive — with no `kestra.ai.providers`
block configured, behaviour is byte-for-byte upstream's, including the
`api.kestra.io` fallback. Rolling back means deploying an image without the patch;
any `type: openai` provider then fails at startup with the upstream "requires Kestra
Enterprise Edition" message, which is the correct signal rather than a silent
downgrade.

## Tests

| Test | Covers |
| --- | --- |
| `AiServiceManagerTest.shouldCreateProvider_whenTypeIsOpenAiCompatible` | The original failure: `openai` was refused outright. |
| `...shouldRegisterGeminiAndOpenAiSideBySide` | Both types coexist; `isDefault` is honoured. |
| `...shouldUseTheOpenAiDefaults_whenOnlyBaseUrlAndKeyAreGiven` | Defaults come from the record, since Jackson conversion bypasses `@Bindable`. |
| `...shouldIgnoreUnknownConfigurationKeys_forOpenAiProviders` | A stray key is dropped, not fatal. |
| `...shouldRejectAProviderTypeThisEditionCannotServe` | Widening to two types did not widen to all. |
| `...shouldRejectANullProviderType_withoutThrowingNullPointerException` | A missing type reports as configuration error, not internal error. |
| `...shouldSkipAProviderWithNoConfiguration_ratherThanFailStartup` | Pre-existing skip behaviour preserved. |
| `OpenAiAiServiceTest` (7) | Base URL resolution, model parameters, defaults, strict modes off, custom headers. |
| `AiControllerOpenAiTest` (3) | HTTP end to end: providers endpoint, flow generation through a stubbed compatible endpoint, explicit `providerId`. |
