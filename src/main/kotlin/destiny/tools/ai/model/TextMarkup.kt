/**
 * Created by smallufo on 2026-10-31.
 */
package destiny.tools.ai.model

/**
 * 文字輸出（非 JSON）時，**讀者那一端**能顯示哪些 Markdown 結構。
 *
 * 同一份 prompt 的輸出可能送到能力不同的地方：網頁的 Markdown renderer 什麼都畫得出來，
 * 聊天軟體的訊息卡片可能只認得清單與粗體、完全沒有表格。模型不知道輸出會被送去哪裡，
 * 所以由呼叫端宣告一個 `Set<TextMarkup>`，提示詞據此說明「可以用什麼、不可以用什麼」。
 *
 * 集合**由 renderer 宣告**（放在 renderer 旁邊），不由 prompt 端猜，提示詞的承諾才會跟實際畫得出來的東西一致。
 *
 * 沒列在集合裡的就是不能用的；純文字本身不是一個 flag（它是「不要 JSON」那條分支）。
 *
 * @property allowed 允許時告訴模型的說法
 * @property forbidden 不允許時的說法 —— **一定要附替代寫法**，只說「不要」的話模型常連內容一起省掉
 */
enum class TextMarkup(val allowed: String, val forbidden: String) {
  HEADING(
    allowed = "Markdown headings (#, ##, ###)",
    forbidden = "Do NOT use Markdown headings (#); write a short line of plain text as a section title instead."
  ),
  LIST(
    allowed = "bullet / numbered lists",
    forbidden = "Do NOT use Markdown lists; write each point as its own short paragraph instead."
  ),
  BOLD(
    allowed = "**bold** for emphasis",
    forbidden = "Do NOT use **bold** or *italic* markers; the reader would see the asterisks literally."
  ),
  TABLE(
    allowed = "Markdown tables (a line break inside a cell may be written as <br>)",
    forbidden = "Do NOT use Markdown tables — the reader cannot render them. " +
      "Present the same content as a list instead: one item per row, each column written as \"column name: value\"."
  );

  companion object {
    /** 什麼都畫得出來（網頁的 Markdown renderer）。也是未指定時的預設 —— 不加任何限制句 */
    val ALL: Set<TextMarkup> = entries.toSet()

    /**
     * 提示詞裡的一段說明；[markup] 為 [ALL] 時回 `null`（一個字都不加，保持既有提示詞不變）。
     */
    fun instruction(markup: Set<TextMarkup>): String? {
      if (markup.containsAll(entries)) return null
      return buildString {
        val (yes, no) = entries.partition { it in markup }
        if (yes.isNotEmpty()) {
          append("Formatting: the reader's app can only display these Markdown features: ")
          append(yes.joinToString(", ") { it.allowed })
          appendLine(".")
        } else {
          appendLine("Formatting: the reader's app displays plain text only — no Markdown at all.")
        }
        no.forEach { appendLine(it.forbidden) }
      }.trimEnd()
    }
  }
}
