/**
 * Created by smallufo on 2026-07-24.
 */
package destiny.tools.ai

import kotlin.time.Duration

/**
 * Hedged orchestration 的配置，chat（[HedgeChatService]）與 image（[HedgeImageService]）共用。
 *
 * @param preferred     優先的 (provider, model)。
 * @param preferredWait preferred model 若能在此段時間內有結果，則優先回傳。
 * @param fallbacks     preferred 出局（超時 / 失敗）後的備援池，取第一個成功者。
 * @param modelTimeout  單一 model 一次請求的 timeout；必須大於 [preferredWait]。
 */
data class HedgeConfig(
  val preferred: ProviderModel,
  val preferredWait: Duration,
  val fallbacks: Set<ProviderModel>,
  override val modelTimeout: Duration,
  override val user: String?
) : IChatConfig {
  init {
    require(!fallbacks.contains(preferred))
    require(modelTimeout > preferredWait)
  }
}
