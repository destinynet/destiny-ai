/**
 * Created by smallufo on 2026-09-09 (logic moved from destiny-core-impl's PostProcessorJsonExtract).
 */
package destiny.tools.ai

import kotlinx.serialization.json.Json
import java.util.regex.Pattern


/**
 * 從 LLM 可能夾雜散文與 markdown 圍籬的回覆裡，把 JSON 本體切出來。
 *
 * ## 為什麼是 typed 路徑的內建步驟，而不是 post-processor
 *
 * 它先前是 core-impl 的 `PostProcessorJsonExtract`，靠 `PostProcessorsMap` 對四個 domain 選配掛上：
 * `ELECTIONAL_DAY_HOUR` 是 typed 輸出卻沒掛、Captcha 得自己手掛、`ToolsConfig` 那份 bean 的順序
 * 又跟 `PostProcessorsMap` 相反。而六家不送 schema 的 provider 一出圍籬就 `DeserializationFailure`。
 * 「輸出型別不是 String 就要切 JSON」是 `typedChatComplete` 的事實，不是 domain 的選項。
 *
 * ## 兩條 pattern，可解析者優先
 *
 * 物件與陣列各取第一個候選（第一個 `{` 到最後一個 `}`、第一個 `[` 到最後一個 `]`），
 * 以「可解析者優先，其次起始位置較早者」挑選；散文裡的 `[see below]` 起始位置比真正的 JSON 早，
 * 單靠位置會選錯。都不可解析時退回最早的那個 —— LLM 常吐出字串內含真實換行的近似 JSON，
 * 那還輪得到 lenient decoder 救；驗證只用來**排序**，不用來**否決**。
 */
object JsonExtract {

  /** `\{.*}` + DOTALL：第一個 `{` 到最後一個 `}`（含換行） */
  private val ObjectPattern: Pattern = Pattern.compile("""\{.*}""", Pattern.DOTALL)

  /** `\[.*]`：同上，但取陣列。⚠️ 不是 `\\[.*]` —— 那是「反斜線 + 字元類」，陣列分支曾因此從未生效 */
  private val ArrayPattern: Pattern = Pattern.compile("""\[.*]""", Pattern.DOTALL)

  private val parser = Json

  /** 切出 JSON 本體；找不到任何候選時原樣回傳。 */
  fun extract(raw: String): String {
    val candidates: List<Pair<Int, String>> = listOfNotNull(
      ObjectPattern.firstMatch(raw),
      ArrayPattern.firstMatch(raw),
    ).sortedBy { (start, _) -> start }
    val picked = candidates.firstOrNull { (_, text) -> text.isParseableJson() }
      ?: candidates.firstOrNull()
      ?: return raw
    return picked.second
  }

  private fun Pattern.firstMatch(input: String): Pair<Int, String>? =
    matcher(input).let { if (it.find()) it.start() to it.group() else null }

  private fun String.isParseableJson(): Boolean = try {
    parser.parseToJsonElement(this)
    true
  } catch (e: Exception) {
    false
  }
}
