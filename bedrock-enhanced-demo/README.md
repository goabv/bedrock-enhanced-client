# Bedrock Enhanced Client — Cost Optimization Demo

A web application demonstrating the `BedrockEnhancedClient`'s prompt caching feature and its cost savings compared to default (no caching) settings.

## What It Shows

The demo runs **two parallel sessions** for each conversation:

1. **Default Session** — Standard Converse API calls with no prompt caching. Every turn re-sends the full conversation history as fresh input tokens.

2. **Caching Session** — Uses `PromptCachingConfig` to automatically insert `CachePointBlock` markers. Bedrock caches the conversation prefix, so subsequent turns only pay the reduced cache-read rate for previously-seen tokens.

The UI displays real-time cost comparison with:
- Large savings percentage banner
- Side-by-side cost cards (total cost per session)
- Token breakdown showing cache read/write tokens
- Per-turn cost bar chart showing savings growth over conversation length

## Supported Models

| Model | Input/1K | Output/1K | Cache Read/1K | Supports Caching |
|-------|----------|-----------|---------------|------------------|
| Claude Sonnet 4 | $0.003 | $0.015 | $0.0003 | ✅ |
| Claude 3.5 Sonnet v2 | $0.003 | $0.015 | $0.0003 | ✅ |
| Claude 3.5 Haiku | $0.0008 | $0.004 | $0.00008 | ✅ |
| Claude 3 Haiku | $0.00025 | $0.00125 | $0.00003 | ✅ |

## Prerequisites

- Java 8+
- Maven
- AWS credentials configured (profile, env vars, or IAM role)
- Bedrock model access enabled in your AWS account for the models above

## Running

```bash
# From the repo root, build the enhanced client first
mvn clean install -pl :bedrock-enhanced -P quick --am

# Then run the demo
cd services-custom/bedrock-enhanced-demo
mvn spring-boot:run
```

Open http://localhost:8080 in your browser.

## Configuration

Edit `src/main/resources/application.properties`:

```properties
# AWS region (must have Bedrock access)
aws.region=us-east-1

# Server port
server.port=8080
```

## Demo Script (3.5 min recording)

1. **[0:00-0:30]** Open the app, explain the split view: cost panel left, chat right
2. **[0:30-1:00]** Select Claude Sonnet 4, send first message — note both sessions start at similar cost
3. **[1:00-1:45]** Send 2-3 follow-up messages — watch the savings percentage grow as cache kicks in
4. **[1:45-2:15]** Point out the token breakdown: cache read tokens increasing, savings accumulating
5. **[2:15-2:45]** Switch to Claude 3.5 Haiku, repeat 2-3 messages — show caching works across models
6. **[2:45-3:15]** Show the per-turn bar chart — visual proof that later turns cost less with caching
7. **[3:15-3:30]** Wrap up: summarize that caching is automatic, no code changes needed

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                   Spring Boot Web App                     │
│                                                          │
│  ┌──────────────┐    ┌──────────────────────────────┐   │
│  │ ChatController│───▶│ SessionManager                │   │
│  │ (REST API)   │    │  ├─ defaultClient (no cache)  │   │
│  └──────────────┘    │  └─ cachingClient (with cache)│   │
│                      └──────────────────────────────┘   │
│                              │                           │
│                              ▼                           │
│                 ┌─────────────────────────┐             │
│                 │ BedrockEnhancedClient    │             │
│                 │  ├─ ChatSession (default)│             │
│                 │  └─ ChatSession (caching)│             │
│                 └─────────────────────────┘             │
│                              │                           │
│                              ▼                           │
│                 ┌─────────────────────────┐             │
│                 │ BedrockRuntimeClient     │             │
│                 │ (Converse API)           │             │
│                 └─────────────────────────┘             │
└─────────────────────────────────────────────────────────┘
```
