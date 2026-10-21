# BeautyCare Service OS

An AI-powered customer service assistant for beauty e-commerce, integrating chat, order, and support ticket data to support customer profiling, personalized recommendations, sentiment analysis, and follow-up tracking through real-time and long-term service loops.

[中文说明](README.zh-CN.md)

This repository contains a **local demonstration prototype**, not a production customer service platform. Order and ticket examples are simulated. The project is not affiliated with or endorsed by L'Oréal Paris.

## Features

- Separate agent sessions, customer chat, human takeover, and conversation routing.
- A service assistant with reception priorities, customer profiles, and service history.
- Context summaries, service timelines, sentiment analysis, and reply drafts.
- An 11-question skincare survey and explainable catalog recommendations.
- Commitment tracking, deadline reminders, and unresolved service items.
- Image/file attachments and product recommendation cards.
- Model routing for DeepSeek, Qwen, and an optional locally hosted compatible model.
- History revision checks that prevent stale browser tabs from restoring deleted demo messages.

## Two service loops

The **real-time response loop** updates the understanding of a conversation when a message or survey answer arrives, then supports a bot response or a human-reviewed draft.

The **long-term service loop** retains profiles, service records, and unresolved commitments so a later conversation can continue from the existing context. A closed reminder is not evidence that a refund or replacement has actually completed.

## Stack and layout

| Component | Technology | Directory |
| --- | --- | --- |
| Frontend | Vue 3, Vite, Pinia, Element Plus | `web/` |
| Backend | Java 21, Spring Boot 3.4.1 | `server-v2/server/` |
| Default database | In-memory H2 | Backend configuration |
| AI | Server-side compatible HTTP APIs | Backend model router |

The backend is in **`server-v2/server`**, not a top-level `server` directory. The older standalone Python prototype is not required and is not included in this distribution. Model weights are not included.

## Quick start

### Requirements

- Node.js 22.12 or later in the Node 22 release line, with npm.
- JDK 21 and Maven 3.6.3 or later, available on your terminal path.
- Your own model API credentials for AI features. No credentials are included.

### 1. Start the backend

From the repository root in PowerShell:

```powershell
Set-Location server-v2/server
Copy-Item .env.example .env.local
```

Edit `.env.local` locally, add your own `DEEPSEEK_API_KEY`, then run:

```powershell
mvn spring-boot:run
```

The backend listens on `http://127.0.0.1:8080`. This distribution binds it to loopback by default. Never upload `.env.local`.

The provided example chooses DeepSeek for text tasks and disables local-node overrides. For Qwen text tasks, set `AI_PROVIDER=qwen` and supply `DASHSCOPE_API_KEY`. Visual analysis additionally requires a suitable Qwen visual model and its credential. Local LoRA inference is optional and requires your own model server and weights.

Without credentials, basic pages and local chat can still be explored, but AI operations may fail or use their implemented fallback paths. An HTTP 402 generally requires checking the selected provider's account balance or quota.

### 2. Start the frontend

In another terminal, from the repository root:

```powershell
Set-Location web
npm ci
npm run dev
```

Open `http://localhost:5175/login`. The development server proxies `/api` requests to the backend. If Vite selects another port, use the address printed in the terminal.

### 3. Try a conversation

| Demo account | Display name | Demo password |
| --- | --- | --- |
| `agent1` | 客服01 | `123456` |
| `agent2` | 客服02 | `123456` |
| `agent3` | 客服03 | `123456` |

1. Log in to an agent desk and open a demo customer consultation from the login page.
2. Send a message as the customer and observe the bot response.
3. Request a human agent, or send a reply from the desk to take over.
4. Open the profile tab, try the survey, and inspect recommendation reasons.
5. Test an explicit follow-up commitment and its reminder separately from conversation closure.

These are demonstration credentials embedded in the prototype, **not secure user accounts**. Backend restarts invalidate login tokens. Log in again after a restart.

## Data and privacy

Runtime state is saved under the operating-system user's home directory in `.beautycare-service-os/`. The frontend also stores state in browser storage. Neither your current runtime data nor your private browser history is included in this distribution.

Source code contains example customer names, messages, orders, and evaluation cases for demonstration. They should not be interpreted as real transactions or independently verified business evidence.

Cloud AI features send the relevant conversation context, and where applicable images, to the configured provider. Do not enter real personal data into a public demo. Training-call recording is disabled by default in this distribution. See [security notes](SECURITY.md).

## Build and tests

```powershell
# Frontend production build
Set-Location web
npm ci
npm run build

# Representative frontend regression tests
node tests/history-revision.mjs
node tests/reception-display.mjs
node tests/service-identity.mjs
node tests/product-presentation.mjs
node tests/quiz-selection.mjs
```

From the repository root, build the backend with:

```powershell
Set-Location server-v2/server
mvn -DskipTests package
```

Backend tests under `src/test/java/` are mostly standalone `main` smoke tests, not JUnit tests. `mvn test` alone does not demonstrate that those scenarios ran. Run the relevant test main classes in an IDE using an isolated test home directory, since some tests write local runtime files.

GitHub Actions is configured to build the frontend and backend and run the listed frontend regression tests. It does not call paid model APIs or provide deployment hosting.

## Scope and limitations

- Several analysis endpoints exist individually. The frontend does not yet use the unified orchestration endpoint for every workflow.
- Orders, refunds, stock, and support ticket operations are not connected to a real merchant system.
- Reminders require the desk to remain open. They are not a durable production job scheduler.
- Some back-office, training, and experience-review functions have backend APIs without a complete frontend management flow.
- Product descriptions, ingredient claims, prices, images, and availability must be independently checked. Survey outputs are user-reported, not medical diagnoses.
- No measured accuracy, cost-saving percentage, or benchmark improvement is claimed by this release.
- This backend cannot run on GitHub Pages. GitHub hosts the repository; a runnable system needs a Java backend and configured frontend environment.

## Rights and attribution

No open-source license has been selected in this distribution. Public repository visibility alone does not grant permission to reuse the code. The authors should agree on an appropriate license before representing the project as open source.

See [third-party notices](THIRD_PARTY_NOTICES.md) for brand, catalog, and dependency considerations. Adding a code license does not relicense third-party assets.

For upload instructions, see [GitHub upload guide](docs/GITHUB_UPLOAD.zh-CN.md).
