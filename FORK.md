# This is a permanent fork

`pedroanisio/kestra`, forked from [`kestra-io/kestra`](https://github.com/kestra-io/kestra)
and deployed at [kestra.xaai.ai](https://kestra.xaai.ai).

## The policy, in two sentences

**Nothing here is going upstream.** No pull request to `kestra-io/kestra` is planned
for any commit on this branch, and the divergence is expected to be permanent.

**Everything upstream is coming here.** This fork tracks upstream releases — bug
fixes and new features alike — and the goal is to stay current with them, not to
freeze. Falling behind is the failure mode this fork is explicitly trying to avoid.

Those two commitments pull against each other, and that tension is the whole cost
of this fork. Since the patches are never retired by being accepted upstream, every
one of them is re-applied on every sync, forever. That makes the sync procedure the
most important thing in this file — not the patches.

## Why permanent

The patches fall into two kinds, and neither ends with an upstream merge.

The AI Copilot provider removes a product boundary:
Kestra's open-source edition accepts only `gemini` as a Copilot provider and refuses
every other type as Enterprise. Modifying that is squarely permitted — the code is
Apache-2.0 — but it is upstream's commercial differentiator, so no PR would be
accepted. It is a deliberate, permanent divergence.

The authentication fixes are ordinary bug fixes and *would* be accepted upstream.
They are not being submitted, by decision. The practical consequence is the same as
above: they are carried indefinitely and re-applied on every sync.

## What this fork carries

Run this to see the current answer rather than trusting the table:

```bash
git log --oneline "$(git describe --tags --abbrev=0 HEAD)"..HEAD    # this fork's own commits
git diff --stat "$(git describe --tags --abbrev=0 HEAD)"..HEAD      # and what they touch
```

| Commit | Area | What, and why it cannot be dropped |
| --- | --- | --- |
| `98b0eec` | `webserver` auth | Tries every credential a request carries. `Optional#or` short-circuits, so a stale `BASIC_AUTH` cookie shadowed the `Authorization` header outright — behind the SSO proxy that 401s every request with correct credentials on the wire. |
| `8440ab5` | `webserver` auth | Resolves those candidates lazily, and stops an `Authorization: Basic` with nothing after it from throwing `StringIndexOutOfBoundsException` out of an unauthenticated path. |
| `cb518be` | `webserver` AI | Adds an `openai` Copilot provider type accepting any OpenAI-compatible endpoint. Upstream's OSS edition allows only `gemini`, and this cluster's egress reaches neither Gemini nor `api.kestra.io` — so without it there is no provider this instance can both configure and reach. See [`docs/fork/ai-copilot-openai.md`](docs/fork/ai-copilot-openai.md). |

Conflict exposure, measured on `upstream/develop` over the last 12 months:
`BasicAuthService.java` took 13 commits, `AiServiceManager.java` 17, `AiService.java`
20. Expect to re-resolve all three on most syncs.

## Syncing to a new upstream release

Branches are named for the release they sit on: `inova/v<version>`, cut from the
upstream release **tag**, never from `develop` (which is an unreleased
`2.0.0-SNAPSHOT`). Syncing therefore means starting a new branch, not merging into
the old one.

```bash
git fetch upstream --tags

# 1. What are we carrying? Record this before you start.
BASE=$(git describe --tags --abbrev=0 HEAD)
git log --oneline "$BASE"..HEAD

# 2. Cut the new branch from the new release tag.
git switch -c inova/v1.3.36 v1.3.36

# 3. Replay the fork's commits onto it.
git cherry-pick "$BASE"..inova/v1.3.35

# 4. Resolve conflicts, then PROVE the patches still do what they claim.
#    Each has tests that fail without it; a clean cherry-pick is not proof.
./gradlew :webserver:test --tests '*BasicAuthServiceTest*' \
                          --tests '*AiServiceManagerTest*' \
                          --tests '*OpenAiAiServiceTest*' \
                          --tests '*AiControllerOpenAiTest*'

# 5. Full module suite before the image is built.
./gradlew :webserver:test

git push -u origin inova/v1.3.36
```

Then rebuild and redeploy: `make build-docker`, and update the image digest in
`manifests/apps/kestra/kestra.yaml` in `k8s-do-control`.

**Push every branch.** For a period this fork existed only on one disk while the
production manifest named its commit as the reason the deployment authenticates.
`origin` is the durable copy; the working tree is not.

## Where the rest of the documentation lives

The monorepo holds the operational context this file should not duplicate:

```
inovapartners.com.br
    apps/README.md               how the fork is built and deployed
    apps/kestra-plugins.md       why the image bundles all 204 open-source plugins
    apps/kestra-ai-copilot.md    configuring the Copilot against DigitalOcean
```

In-tree, per-patch documentation lives under [`docs/fork/`](docs/fork/).
