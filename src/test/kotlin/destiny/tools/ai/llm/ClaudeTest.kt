package destiny.tools.ai.llm

import com.jayway.jsonpath.DocumentContext
import com.jayway.jsonpath.JsonPath
import destiny.tools.ai.ChatOptions
import destiny.tools.ai.ThinkingMode
import destiny.tools.ai.llm.Claude.ClaudeOptions.Companion.toClaude
import com.jayway.jsonpath.PathNotFoundException
import mu.KotlinLogging
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Nested
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Created by smallufo on 2024-10-14.
 */
class ClaudeTest {

  val json = Json {
    encodeDefaults = true
    prettyPrint = true
    // skip null fields , get rid of null user error
    explicitNulls = false
    // to ignore unknown keys
    ignoreUnknownKeys = true
  }

  private val logger = KotlinLogging.logger { }

  @Nested
  inner class DeserializeTest {

    @Test
    fun weather() {
      val raw = """
        {
          "id": "msg_01UbxrHrc72srjNVFeDpFzaz",
          "type": "message",
          "role": "assistant",
          "model": "claude-3-TEST-MODEL",
          "content": [
            {
              "type": "text",
              "text": "Okay, let's check the current weather in New York and Taipei:"
            },
            {
              "type": "tool_use",
              "id": "toolu_01WdELdRPyW1hfGLgwL3eLBc",
              "name": "get_current_weather",
              "input": {
                "location": "New York, NY",
                "format": "F"
              }
            }
          ],
          "stop_reason": "tool_use",
          "stop_sequence": null,
          "usage": {
            "input_tokens": 390,
            "output_tokens": 92,
            "cache_creation_input_tokens":0,
            "cache_read_input_tokens":0
          }
        }
      """.trimIndent()
      json.decodeFromString<Claude.Response>(raw).also { response: Claude.Response ->
        logger.info { "claudeResponse = $response" }
        response.contents!!.also { contents ->
          assertEquals(2, contents.size)
          contents.last().also { content ->
            assertEquals("tool_use", content.contentType)
            val toolUse = (content as Claude.Content.ToolUse)
            assertEquals("get_current_weather", toolUse.name)
          }
        }
      }
    }
  }

  @Test
  fun horoscopeForecastYear() {
    val raw = """
      {
        "id": "msg_01NMLtxgG1B4MG6JYEwEwJkY",
        "type": "message",
        "role": "assistant",
        "model": "claude-3-TEST-MODEL",
        "content": [
          {
            "type": "text",
            "text": "我已經為您分析了目前星盤的狀況,下面我來預測2027年的運勢:"
          },
          {
            "type": "tool_use",
            "id": "toolu_01XnTA2Sdd9kiP5RiUpGdoer",
            "name": "get_horoscope_forecast_year",
            "input": {
              "birthYear": 2010,
              "birthMonth": 1,
              "birthDay": 1,
              "birthHour": 18,
              "birthMinute": 0,
              "birthLat": 25.03903,
              "birthLng": 121.517668,
              "tzid": "Asia/Taipei",
              "gender": "M",
              "forecastYear": 2027
            }
          }
        ],
        "stop_reason": "tool_use",
        "stop_sequence": null,
        "usage": {
          "input_tokens": 4804,
          "output_tokens": 271,
          "cache_creation_input_tokens":0,
          "cache_read_input_tokens":0
        }
      }
    """.trimIndent()
    json.decodeFromString<Claude.Response>(raw).also { response ->
      logger.info { "claudeResponse = $response" }
      response.contents.also {
        assertNotNull(it)
        it[0].also { content: Claude.Content ->
          assertTrue { content is Claude.Content.Text }
        }
        it[1].also { content: Claude.Content ->
          assertTrue { content is Claude.Content.ToolUse }
          val toolUse = (content as Claude.Content.ToolUse)
          assertEquals("get_horoscope_forecast_year", toolUse.name)
        }
      }
    }

  }


  /**
   * 2026-04-18：驗證 prompt caching 的 request payload 序列化：
   * - 頂層 `system` 陣列 + `cache_control: ephemeral`
   * - `Content.Text` 也支援 `cache_control`（給 cacheable user message 用）
   */
  @Nested
  inner class PromptCachingSerialization {

    @Test
    fun `system with cache_control ephemeral 序列化正確`() {
      val chatModel = Claude.ChatModel(
        messages = listOf(
          Claude.ClaudeMessage.TextContent("user", "今天運勢如何？"),
        ),
        model = "claude-sonnet-4-5",
        maxTokens = 1024,
        system = listOf(
          Claude.SystemTextBlock(
            text = "[USER_PROFILE]\n出生資料：男性，1970-01-01\n\n[NATAL_DATA]\n甲子年 壬申月 乙亥日 戊寅時",
            cacheControl = Claude.CacheControl(),
          )
        ),
      )

      val serialized = json.encodeToString(Claude.ChatModel.serializer(), chatModel)
      logger.info { "serialized: $serialized" }

      val doc = JsonPath.parse(serialized)
      assertEquals("text", doc.read("$.system[0].type"))
      assertEquals("ephemeral", doc.read("$.system[0].cache_control.type"))
      val systemText: String = doc.read("$.system[0].text")
      assertTrue(systemText.contains("[USER_PROFILE]"))
      assertTrue(systemText.contains("[NATAL_DATA]"))
      assertTrue(systemText.contains("1970-01-01"))
    }

    @Test
    fun `system 為 null 時 不出現在 payload`() {
      val chatModel = Claude.ChatModel(
        messages = listOf(Claude.ClaudeMessage.TextContent("user", "hi")),
        model = "claude-sonnet-4-5",
        system = null,
      )

      val serialized = json.encodeToString(Claude.ChatModel.serializer(), chatModel)
      val doc = JsonPath.parse(serialized)
      assertFailsWith<PathNotFoundException>("system=null 時不該出現") {
        doc.read<Any>("$.system")
      }
    }

    @Test
    fun `Text content 的 cache_control 可選序列化`() {
      val textWithCache = Claude.Content.Text(text = "cached content", cacheControl = Claude.CacheControl())
      val textWithoutCache = Claude.Content.Text(text = "normal content")

      val s1 = json.encodeToString(Claude.Content.serializer(), textWithCache)
      val s2 = json.encodeToString(Claude.Content.serializer(), textWithoutCache)

      val d1 = JsonPath.parse(s1)
      assertEquals("ephemeral", d1.read<String>("$.cache_control.type"))
      assertEquals("cached content", d1.read<String>("$.text"))

      val d2 = JsonPath.parse(s2)
      assertEquals("normal content", d2.read<String>("$.text"))
      assertFailsWith<PathNotFoundException>("無 cacheControl 時 explicitNulls=false 應省略") {
        d2.read<Any>("$.cache_control")
      }
    }

    @Test
    fun `response usage 含 cache 欄位反序列化`() {
      val raw = """
        {
          "id": "msg_test",
          "type": "message",
          "role": "assistant",
          "model": "claude-sonnet-4-5",
          "content": [{"type": "text", "text": "ok"}],
          "stop_reason": "end_turn",
          "stop_sequence": null,
          "usage": {
            "input_tokens": 100,
            "output_tokens": 50,
            "cache_creation_input_tokens": 1500,
            "cache_read_input_tokens": 3200
          }
        }
      """.trimIndent()

      val response = json.decodeFromString<Claude.Response>(raw)
      val usage = assertNotNull(response.usage)
      assertEquals(100, usage.inputTokens)
      assertEquals(50, usage.outputTokens)
      assertEquals(1500, usage.cacheCreationInputTokens)
      assertEquals(3200, usage.cacheReadInputTokens)
    }

  }

  /**
   * 回應裡的 thinking block。
   *
   * 這一整組來自 2026-08-26 dev 環境的實際炸點（commercial partner=andy 送占星報告）：
   * `Serializer for subclass 'thinking' is not found in the polymorphic scope of 'Content'`。
   */
  @Nested
  inner class ThinkingBlockTest {

    /**
     * Claude 4.6 世代（sonnet-5 / opus-5 …）**省略 `thinking` 參數就等於 adaptive thinking 開啟**，
     * 與舊模型相反。所以一份沒改過的 request 只要把 model 從 `claude-haiku-4-5` 換成
     * `claude-sonnet-5`，`content[0]` 就會多出一個 thinking block。
     *
     * 這個 payload 是 2026-08-26 dev 環境實際炸掉的形狀（commercial partner=andy 送占星報告）：
     * `Serializer for subclass 'thinking' is not found in the polymorphic scope of 'Content'`。
     * `ignoreUnknownKeys` 救不了 —— 它管的是未知**欄位**，不是未知的多型**子類**。
     */
    @Test
    fun `thinking block 反序列化 —— 4_6 世代預設會回這個`() {
      val raw = """
        {
          "id": "msg_test",
          "type": "message",
          "role": "assistant",
          "model": "claude-sonnet-5",
          "content": [
            {"type": "thinking", "thinking": "", "signature": "ErUBCkYIBRgCKkD..."},
            {"type": "text", "text": "{\"summary\": \"...\"}"}
          ],
          "stop_reason": "end_turn",
          "stop_sequence": null,
          "usage": {
            "input_tokens": 100,
            "output_tokens": 50,
            "cache_creation_input_tokens": 0,
            "cache_read_input_tokens": 0
          }
        }
      """.trimIndent()

      val contents = assertNotNull(json.decodeFromString<Claude.Response>(raw).contents)
      assertEquals(2, contents.size)

      val thinking = assertNotNull(contents.filterIsInstance<Claude.Content.Thinking>().firstOrNull())
      assertEquals("", thinking.thinking, "display=omitted 時 thinking 是空字串，但 block 仍在")
      assertEquals("ErUBCkYIBRgCKkD...", thinking.signature, "簽章要留著：tool-use 往返時必須原樣回送")

      // 消費端取的是第一個 Text —— thinking 排在前面也不該干擾
      val text = assertNotNull(contents.filterIsInstance<Claude.Content.Text>().firstOrNull())
      assertTrue(text.text.startsWith("{"), "取到的應該是 JSON 回應而不是思考歷程")
    }

    /** `display = "summarized"` 時 thinking 有內容；另有 redacted 變體 */
    @Test
    fun `summarized thinking 與 redacted_thinking 都解得開`() {
      val raw = """
        {
          "id": "msg_test", "type": "message", "role": "assistant", "model": "claude-opus-5",
          "content": [
            {"type": "thinking", "thinking": "先看四元素分佈…", "signature": "sig1"},
            {"type": "redacted_thinking", "data": "EncryptedBlob=="},
            {"type": "text", "text": "done"}
          ],
          "stop_reason": "end_turn", "stop_sequence": null,
          "usage": {"input_tokens": 1, "output_tokens": 1, "cache_creation_input_tokens": 0, "cache_read_input_tokens": 0}
        }
      """.trimIndent()

      val contents = assertNotNull(json.decodeFromString<Claude.Response>(raw).contents)
      assertEquals("先看四元素分佈…", contents.filterIsInstance<Claude.Content.Thinking>().first().thinking)
      assertEquals("EncryptedBlob==", contents.filterIsInstance<Claude.Content.RedactedThinking>().first().data)
    }

    /**
     * tool-use 往返會把 assistant 的 content **原樣回送**（`ClaudeImpl` 的
     * `ClaudeMessage.ArrayContent("assistant", claudeResponse.contents!!)`）。
     * Anthropic 要求 thinking block 連簽章一起送回，所以序列化必須 round-trip 得回去。
     */
    @Test
    fun `thinking block 序列化回送時保留 type 與 signature`() {
      val block: Claude.Content = Claude.Content.Thinking(thinking = "推理…", signature = "sig-abc")
      val out = json.encodeToString(Claude.Content.serializer(), block)
      val doc = JsonPath.parse(out)

      assertEquals("thinking", doc.read<String>("$.type"))
      assertEquals("推理…", doc.read<String>("$.thinking"))
      assertEquals("sig-abc", doc.read<String>("$.signature"))

      // contentType 是 Kotlin 端的便利欄位，不該上線 —— 回送時 Anthropic 會用 signature 驗 block
      assertFailsWith<PathNotFoundException> { doc.read<Any>("$.contentType") }
    }

    /**
     * ⚠️ 回送路徑用的是 **`Json` companion 的預設實例**，不是 `ClaudeImpl` 那個
     * `encodeDefaults = true` 的設定 —— 見 `ClaudeMessageSerializer` 的
     * `Json.encodeToJsonElement(value.content)`。
     *
     * 所以「等於預設值」的欄位會被省略。`display = omitted`（4.6 世代預設）時 thinking
     * 剛好就是空字串，少了 `@EncodeDefault` 的話回送的 block 就沒有這個欄位，Anthropic 回
     * `messages.N.content.0.thinking.thinking: Field required`，整個 tool-use 往返失敗。
     *
     * 這條測試刻意用 bare `Json` 而非本檔的 `json`，因為要複製的是**壞掉的那條路徑**。
     * 用設定過的實例測，它永遠是綠的，也就永遠抓不到這個問題。
     */
    @Test
    fun `空字串的 thinking 在 bare Json 下仍須送出`() {
      val block: Claude.Content = Claude.Content.Thinking(thinking = "", signature = "sig")
      val out = Json.encodeToJsonElement(Claude.Content.serializer(), block).toString()
      val doc = JsonPath.parse(out)

      assertEquals("", doc.read<String>("$.thinking"), "空字串被省略了 —— Anthropic 會拒收")
      assertEquals("sig", doc.read<String>("$.signature"))
    }
  }

  /**
   * request 的 `thinking` 參數。
   *
   * 重點不在「能不能送」，而在**預設值不再是隱形的**：省略等於沿用該 model 的世代預設，
   * 而那個預設在 4.6 世代翻了面（省略 = adaptive 開啟）。
   */
  @Nested
  inner class ThinkingConfigTest {

    private fun payload(mode: ThinkingMode?): DocumentContext {
      val options = ChatOptions(thinking = mode).toClaude()
      val chatModel = Claude.ChatModel(
        messages = listOf(Claude.ClaudeMessage.TextContent("user", "hi")),
        model = "claude-sonnet-5",
        options = options,
      )
      return JsonPath.parse(json.encodeToString(chatModel))
    }

    @Test
    fun `null 時 payload 完全不出現 thinking 欄位`() {
      assertFailsWith<PathNotFoundException> { payload(null).read<Any>("$.thinking") }
    }

    @Test
    fun `DISABLED 送出 type=disabled`() {
      val doc = payload(ThinkingMode.DISABLED)
      assertEquals("disabled", doc.read<String>("$.thinking.type"))
    }

    @Test
    fun `ADAPTIVE 送出 type=adaptive 且不帶 display`() {
      val doc = payload(ThinkingMode.ADAPTIVE)
      assertEquals("adaptive", doc.read<String>("$.thinking.type"))
      assertFailsWith<PathNotFoundException> { doc.read<Any>("$.thinking.display") }
    }

    @Test
    fun `ADAPTIVE_SUMMARIZED 帶 display=summarized`() {
      val doc = payload(ThinkingMode.ADAPTIVE_SUMMARIZED)
      assertEquals("adaptive", doc.read<String>("$.thinking.type"))
      assertEquals("summarized", doc.read<String>("$.thinking.display"))
    }
  }
}
