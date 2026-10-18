/**
 * Created by smallufo on 2024-08-19.
 */
package destiny.tools.ai


enum class Provider {
  OPENAI,
  GEMINI,
  CLAUDE,
  COHERE,
  REKA,
  MISTRAL,
  GROQ,
  XAI,
  DEEPSEEK,
  TOGETHER,
  CEREBRAS,
  XIAOMI,
  /** build.nvidia.com 託管的 Nemotron 系列（試用免費、無 SLA）。 */
  NVIDIA,
  /** openrouter.ai 聚合商：一把 key 打多家 model，`:free` 後綴為免費版（有每日配額）。 */
  OPENROUTER,
  /** image-only 代管商（托管 FLUX schnell / aesthetic-anime 等），無 chat API。 */
  REPLICATE,
}
