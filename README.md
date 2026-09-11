# destiny-ai

A provider-agnostic Kotlin layer for talking to LLMs: one set of interfaces, thirteen
providers behind them, and the failover / retry / cost-accounting machinery you would
otherwise write yourself.

It is deliberately **dependency-light** — kotlin-stdlib, coroutines, kotlinx-serialization,
kotlin-logging, jakarta.inject. No HTTP client, no web framework, no Spring. The wire
implementations live in a separate module, so this one drops into any stack.

```xml
<dependency>
  <groupId>destiny</groupId>
  <artifactId>destiny-ai</artifactId>
</dependency>
```

---

## What's in here

| Concern | Type |
|---|---|
| Talk to one model | `IChatCompletion` |
| Talk to one model, **streaming** | `IStreamingChatCompletion` → `Flow<ChatChunk>` |
| Structured output (JSON → your data class) | `FormatSpec<T>` + `typedChatComplete` |
| Retry across providers until one succeeds | `IChatOrchestrator` → `ResilientChatService` |
| Race providers, take the fastest | `IChatOrchestrator` → `HedgeChatService` |
| Result, with errors classified | `Reply<T>` (`Normal` / `Error.Retryable` / `Error.Terminal`) |
| Token & USD accounting | `TokenUsageAccumulator`, `ModelPricing`, `IModelCatalog` |
| Tool / function calling | `IFunctionDeclaration` |
| DAG of LLM calls | `destiny.tools.workflow` (`GenerationPlan`, `Segment`, `ExecutionEngine`) |

---

## Three things worth knowing

### 1. Structured output is the main event

Most LLM work is not "give me prose", it is "give me a `TarotReading` object". `FormatSpec<T>`
carries the serializer, the JSON schema, and a content-level validator:

```kotlin
@Serializable
data class Verdict(val summary: String, val score: Int)

val spec = FormatSpec.of<Verdict>("verdict", "A short judgement with a 0-100 score")

val reply: Reply<Verdict>? = impl.typedChatComplete(
  model = "claude-sonnet-5",
  message = "Judge this: ...",
  formatSpec = spec,
  json = LlmJson.lenient,
)
```

The schema is sent to the provider's native structured-output API where available, the
response is extracted and deserialized, and the result is validated. That last step matters
more than it sounds: a schema's `required` only guarantees a field *exists*. `FormatSpec.validator`
is where you catch "the map has 4 of the 12 keys I asked for" — which otherwise counts as
a successful call.

### 2. Errors are classified, not stringly-typed

`Reply.Error` splits into `Retryable` (rate limit, overloaded) and `Terminal` (bad API key,
input too long, deserialization failure). The orchestrators act on that split; so can you.
A `RateLimited` carries the provider's `retryAfter` hint when there is one.

### 3. The orchestrators are where failover lives

```kotlin
val orchestrator = factory.resilient(
  ResilientConfig(
    providerModels = setOf(
      ProviderModel(Provider.CLAUDE, "claude-sonnet-5"),
      ProviderModel(Provider.OPENAI, "gpt-4.1-mini"),
    ),
    maxTotalAttempts = 3,
  )
)
val reply = orchestrator.chatComplete(spec, prompt, postProcessors, providerImpl = ::findImpl)
```

Shuffles the models, tries them in order, disables a provider for the rest of the call on
`InvalidApiKey`, honours `retryAfter` between loops. `chatCompleteOrExplain` returns
`Orchestration.Exhausted` with every attempt's classification when nothing worked.

---

## Streaming

```kotlin
val streaming = impl as? IStreamingChatCompletion
  ?: error("this provider does not stream")

val text = StringBuilder()
streaming.streamChatComplete(model, "Explain monads in Chinese.").collect { chunk ->
  when (chunk) {
    is ChatChunk.Delta          -> { text.append(chunk.text); print(chunk.text) }
    is ChatChunk.ReasoningDelta -> { /* model's thinking, if the model emits any */ }
    is ChatChunk.ToolStarted    -> println("[calling ${chunk.name}]")
    is ChatChunk.ToolFinished   -> println("[${chunk.name} done]")
    is ChatChunk.Completed      -> println("\n-- ${chunk.reply.outputTokens} tokens")
    is ChatChunk.Failed         -> println("\n-- failed: ${chunk.error}")
  }
}
```

Four things about this API that are deliberate, and will save you a debugging session:

**It is opt-in, by interface, with no default implementation.** `as?` tells you the truth
about whether a provider streams. The tempting alternative — a default method on
`IChatCompletion` that wraps a one-shot call in a single chunk — would make all thirteen
providers *claim* to stream while twelve of them silently deliver one giant blob after forty
seconds. That is exactly the failure streaming exists to prevent, so the default does not exist.

**`Completed.reply.content` is always empty.** The full text already went out through `Delta`.
Accumulate it yourself. Shipping it twice would mean two equally plausible ways to read the
result, and therefore a bug somebody writes once and finds much later. The `Completed` chunk
is for metadata: tokens, model, cache hits, duration, invoked tools.

**There is no failover.** Streaming does not go through `IChatOrchestrator`, and cannot:
`ResilientChatService` only learns a call failed after deserializing and validating the whole
response — by which time the losing provider's tokens are already on the user's screen and
cannot be recalled. `HedgeChatService` races several providers and keeps the first winner,
which under streaming would mean buffering until a winner exists, which is not streaming.
So `streamChatComplete` binds to exactly one `(provider, model)`. Pick it yourself; failure
is failure. That is the nature of streaming, not a gap in the implementation.

**Retry rules change once bytes are out.** `Reply.Error.Retryable` means "safe to retry" only
*before* the first `Delta`. After that the user has half an answer on screen and a retry would
replay it from the top. The implementations honour this and so should your calling code.

There is also no `postProcessors` parameter, unlike `typedChatComplete`. Post-processors take
the whole string — Simplified/Traditional Chinese conversion, for instance, is phrase-level and
would produce different output if applied per-chunk at arbitrary boundaries. Rather than accept
a parameter that gets silently ignored, the signature omits it. Run post-processing on the
assembled text afterwards.

### What "streaming" means here — and what it does not

This library hands you a `Flow<ChatChunk>`. It does **not** ship Server-Sent Events, WebSockets,
or any transport.

```
Anthropic / OpenAI stream over SSE   →   inside the provider impl, invisible to you
              ↓
        Flow<ChatChunk>              →   what destiny-ai gives you
              ↓
  your SSE endpoint / WebSocket /    →   your choice; the library has no opinion
  TUI / file / plain collect
```

Wiring it to Spring MVC is a handler returning `Flow<ServerSentEvent<String>>` with
`produces = TEXT_EVENT_STREAM_VALUE`; Ktor has `respondSSE`; on Android you just collect.
Keeping transport out is what lets this module stay free of HTTP and web dependencies.

### Provider support

| Provider | Streaming |
|---|---|
| Anthropic (Claude) | ✅ |
| OpenAI, Gemini, Mistral, Cohere, DeepSeek, Groq, xAI, Cerebras, Together, Reka, XiaoMi | ❌ not yet |

Claude came first on purpose: its SSE carries the widest variety of events — text deltas,
thinking deltas, tool-use argument deltas — so it exercises every `ChatChunk` case. Designing
the contract against the simplest provider would have produced one that cannot express
reasoning or tool deltas, and adding those later is a breaking change.

Eight of the remaining providers share a common `OpenAiCompatibleChatCompletion` base, so one
SSE parser there lights up eight at once. Contributions welcome — `AbstractChatCompletionTest`
already contains the acceptance test (see below); a new implementation just un-`@Ignore`s it.

### The streaming acceptance test

`AbstractChatCompletionTest.streamCountToTwenty` asks the model to count from 1 to 20 and
checks four things. The important one:

```kotlin
assertTrue(deltaCount > 1, "only $deltaCount delta(s) — this is not streaming")
```

A fake implementation that buffers and emits once fails right there. A subtler fake — collect
everything, then emit it in small pieces — passes that check but fails the fourth assertion,
which requires the deltas to arrive *progressively* rather than all at the same instant.

Measured against `claude-sonnet-5` on 2026-09-11: an 800-word generation produced 623 deltas
spread evenly from 894 ms to 33.5 s, roughly 3.3 s per decile. No buffering.

---

## Module layout

`destiny-ai` holds contracts and provider-agnostic logic only. The HTTP implementations
(`ClaudeImpl`, `OpenAiImpl`, …) live in `destiny-core-impl` along with their Ktor client
wiring. If you are embedding this library you will typically depend on both, or write your
own `IChatCompletion` against your HTTP stack of choice.

## License

See the repository.

---
---

# destiny-ai（中文）

與 LLM 對話的 provider-agnostic Kotlin 層：一組介面、後面接十三家 provider，外加
failover / retry / 成本記帳這些你本來得自己寫的東西。

它刻意維持**極輕的依賴** —— kotlin-stdlib、coroutines、kotlinx-serialization、
kotlin-logging、jakarta.inject。沒有 HTTP client、沒有 web framework、沒有 Spring。
實際發 HTTP 的 impl 在另一個模組，所以這個模組塞得進任何 stack。

```xml
<dependency>
  <groupId>destiny</groupId>
  <artifactId>destiny-ai</artifactId>
</dependency>
```

---

## 裡面有什麼

| 要做的事 | 型別 |
|---|---|
| 對單一 model 發話 | `IChatCompletion` |
| 對單一 model 發話，**串流** | `IStreamingChatCompletion` → `Flow<ChatChunk>` |
| 結構化輸出（JSON → 你的 data class） | `FormatSpec<T>` + `typedChatComplete` |
| 跨 provider 重試到有人成功 | `IChatOrchestrator` → `ResilientChatService` |
| 同時發、取最快的 | `IChatOrchestrator` → `HedgeChatService` |
| 帶錯誤分類的結果 | `Reply<T>`（`Normal` / `Error.Retryable` / `Error.Terminal`） |
| token 與 USD 記帳 | `TokenUsageAccumulator`、`ModelPricing`、`IModelCatalog` |
| 工具 / function calling | `IFunctionDeclaration` |
| LLM 呼叫的 DAG | `destiny.tools.workflow`（`GenerationPlan`、`Segment`、`ExecutionEngine`） |

---

## 三件值得先知道的事

### 1. 結構化輸出才是主戲

多數 LLM 工作不是「給我一段散文」，而是「給我一個 `TarotReading` 物件」。
`FormatSpec<T>` 一次帶齊 serializer、JSON schema、以及**內容層的驗證器**：

```kotlin
@Serializable
data class Verdict(val summary: String, val score: Int)

val spec = FormatSpec.of<Verdict>("verdict", "A short judgement with a 0-100 score")

val reply: Reply<Verdict>? = impl.typedChatComplete(
  model = "claude-sonnet-5",
  message = "Judge this: ...",
  formatSpec = spec,
  json = LlmJson.lenient,
)
```

schema 會送進 provider 原生的 structured-output API（有的話），回應自動抽取、反序列化，
最後驗證。最後那步比聽起來重要：schema 的 `required` 只保證欄位**存在**。
`FormatSpec.validator` 守的是 schema 說不出來的事 —— 「我要 12 個 key，它只給了 4 個」
這種情況，少了這一層會被算成呼叫成功。

### 2. 錯誤是分類過的，不是字串

`Reply.Error` 分成 `Retryable`（rate limit、overloaded）與 `Terminal`（API key 錯、
input 太長、反序列化失敗）。orchestrator 依此決策，你也可以。`RateLimited` 在 provider
有給 hint 時會帶著 `retryAfter`。

### 3. failover 住在 orchestrator

```kotlin
val orchestrator = factory.resilient(
  ResilientConfig(
    providerModels = setOf(
      ProviderModel(Provider.CLAUDE, "claude-sonnet-5"),
      ProviderModel(Provider.OPENAI, "gpt-4.1-mini"),
    ),
    maxTotalAttempts = 3,
  )
)
val reply = orchestrator.chatComplete(spec, prompt, postProcessors, providerImpl = ::findImpl)
```

把 model 洗牌後依序試；遇到 `InvalidApiKey` 就把那家 provider 停用到本次呼叫結束；
輪與輪之間尊重 `retryAfter`。全部失敗時 `chatCompleteOrExplain` 會回
`Orchestration.Exhausted`，裡面有每一次嘗試的分類。

---

## 串流

```kotlin
val streaming = impl as? IStreamingChatCompletion
  ?: error("這家 provider 不支援串流")

val text = StringBuilder()
streaming.streamChatComplete(model, "用中文解釋 monad。").collect { chunk ->
  when (chunk) {
    is ChatChunk.Delta          -> { text.append(chunk.text); print(chunk.text) }
    is ChatChunk.ReasoningDelta -> { /* 模型的思考歷程（有的話） */ }
    is ChatChunk.ToolStarted    -> println("[正在呼叫 ${chunk.name}]")
    is ChatChunk.ToolFinished   -> println("[${chunk.name} 完成]")
    is ChatChunk.Completed      -> println("\n-- ${chunk.reply.outputTokens} tokens")
    is ChatChunk.Failed         -> println("\n-- 失敗：${chunk.error}")
  }
}
```

這個 API 有四個刻意的設計，先知道可以省下一次 debug：

**它是 opt-in 的獨立介面，沒有預設實作。** 用 `as?` 問得到真話。另一種誘人的做法 ——
在 `IChatCompletion` 上放一個「打一次性請求再包成單顆 chunk」的預設方法 —— 會讓
十三家 provider 全都**聲稱**會串流，而其中十二家實際上是等四十秒吐出一大塊。
那正是串流要解決的問題本身，所以那個預設不存在。

**`Completed.reply.content` 永遠是空字串。** 全文已經由 `Delta` 送過了，請自己累加。
送兩份等於給了兩種都合理的讀法，於是一定有人挑錯，而且錯的那個在測試裡不會炸。
`Completed` 是給 metadata 用的：tokens、model、cache 命中、耗時、呼叫過的工具。

**沒有 failover。** 串流不經過 `IChatOrchestrator`，而且不可能經過：
`ResilientChatService` 要等整份回應反序列化並驗證完才知道失敗，那時輸的那家的 token
已經在使用者螢幕上，收不回來；`HedgeChatService` 同時發給多家取最快的，串流下等於要
緩衝到勝負揭曉，而緩衝就不是串流。所以 `streamChatComplete` 綁定單一
`(provider, model)`，你自己挑，失敗就是失敗。這是串流的本質，不是實作還沒做完。

**吐出第一個 byte 之後，重試規則就變了。** `Reply.Error.Retryable` 的「可以重試」
只在第一顆 `Delta` **之前**成立。之後使用者已經看到半個答案，重試會讓畫面從頭再來一次。
impl 端遵守這條規則，你的呼叫端也該遵守。

另外，與 `typedChatComplete` 不同，這裡**沒有 `postProcessors` 參數**。post-processor
吃的是全文 —— 以簡繁轉換為例，那是詞組級的，逐塊套用會在任意的 chunk 邊界切斷詞組而
產生不同的結果。與其收一個會被靜默忽略的參數，不如不收；需要後處理請在拼完全文之後跑。

### 這裡說的「串流」是什麼，不是什麼

這個 library 給你 `Flow<ChatChunk>`。它**不**提供 Server-Sent Events、WebSocket
或任何傳輸層。

```
Anthropic / OpenAI 用 SSE 吐 token   →   藏在 provider impl 裡，你看不到
              ↓
        Flow<ChatChunk>              →   destiny-ai 給你的東西
              ↓
   你自己的 SSE endpoint / WebSocket  →   你的選擇，library 不表意見
   / TUI / 寫檔 / 直接 collect
```

接到 Spring MVC 就是一個回 `Flow<ServerSentEvent<String>>`、標
`produces = TEXT_EVENT_STREAM_VALUE` 的 handler；Ktor 有 `respondSSE`；
Android 直接 collect。把傳輸層排除在外，正是這個模組能維持零 HTTP／zero web 依賴的原因。

### Provider 支援狀況

| Provider | 串流 |
|---|---|
| Anthropic (Claude) | ✅ |
| OpenAI、Gemini、Mistral、Cohere、DeepSeek、Groq、xAI、Cerebras、Together、Reka、XiaoMi | ❌ 尚未 |

先做 Claude 是刻意的：它的 SSE 事件種類最多 —— 文字 delta、thinking delta、
tool-use 參數 delta —— 能把 `ChatChunk` 的每一個 case 都走過一遍。拿最簡單的
provider 去設計契約，結果會是一個表達不出 reasoning 與 tool delta 的契約，
而那兩件事之後要補就是 breaking change。

其餘 provider 裡有八家共用 `OpenAiCompatibleChatCompletion` base，在那裡寫一次 SSE
解析就能一次點亮八家。歡迎 PR —— `AbstractChatCompletionTest` 裡已經有驗收測試（見下），
新的實作只要把它的 `@Ignore` 拿掉就行。

### 串流的驗收測試

`AbstractChatCompletionTest.streamCountToTwenty` 叫模型從 1 數到 20，檢查四件事。
最重要的是這條：

```kotlin
assertTrue(deltaCount > 1, "只收到 $deltaCount 顆 Delta，這不是串流")
```

「緩衝完再一次 emit」的假實作在這裡就會現形。比較狡猾的另一種 —— 全部收完再切成小塊
emit —— 過得了這條，但過不了第四條：它要求 delta **漸進**到達，而不是全部同時抵達。

2026-09-11 對 `claude-sonnet-5` 實測：一篇 800 字的生成產出 623 顆 delta，
從 894 ms 均勻分布到 33.5 秒，每個十分位約 3.3 秒。沒有任何緩衝。

---

## 模組配置

`destiny-ai` 只放契約與 provider-agnostic 的邏輯。實際發 HTTP 的 impl
（`ClaudeImpl`、`OpenAiImpl`…）與它們的 Ktor client 接線在 `destiny-core-impl`。
若你要嵌用這個 library，通常會同時依賴兩者，或是照自己的 HTTP stack 實作
`IChatCompletion`。

## 授權

見 repository。
