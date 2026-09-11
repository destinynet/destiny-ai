/**
 * Created by smallufo on 2026-07-25.
 */
package destiny.tools.workflow

import destiny.tools.ai.AspectRatio
import destiny.tools.ai.ImageOptions
import destiny.tools.ai.ImageResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SegmentTest {

  data class StringOutput(val value: String) : SegmentOutput

  private val SCENE = SegmentId("scene")
  private val PAINT = SegmentId("paint")

  @Test
  fun `ImageSegment 的 promptBuilder 讀得到上游輸出`() {
    val segment = Segment.ImageSegment(
      id = PAINT,
      dependsOn = setOf(SCENE),
      promptBuilder = { ctx ->
        val scene: StringOutput = ctx.get(SCENE)
        "ink wash painting of ${scene.value}"
      },
      options = ImageOptions(n = 2, resolution = ImageResolution.R2K, aspectRatio = AspectRatio.PORTRAIT_3_4)
    )

    assertEquals(PAINT, segment.id)
    assertEquals(setOf(SCENE), segment.dependsOn)
    assertEquals(2, segment.options.n)
    assertEquals(AspectRatio.PORTRAIT_3_4, segment.options.aspectRatio)

    val ctx = MutableSegmentContext().apply { put(SCENE, StringOutput("a lone crane over misty river")) }
    assertEquals("ink wash painting of a lone crane over misty river", segment.promptBuilder(ctx))
  }

  @Test
  fun `ImageSegment 預設無依賴、用預設 ImageOptions`() {
    val segment = Segment.ImageSegment(id = PAINT, promptBuilder = { "a cat" })

    assertTrue(segment.dependsOn.isEmpty())
    assertEquals(ImageOptions(), segment.options)
  }

  /** ImageSegment 必須是 Segment 的一員，才能進 GenerationPlan 的 DAG。 */
  @Test
  fun `ImageSegment 可放入 GenerationPlan 並參與拓樸排序`() {
    val plan = GenerationPlan(
      planId = "test-image-dag",
      name = "Image DAG",
      segments = listOf(
        Segment.StaticSegment(id = SCENE, content = StringOutput("moonlit bamboo")),
        Segment.ImageSegment(
          id = PAINT,
          dependsOn = setOf(SCENE),
          promptBuilder = { ctx -> ctx.get<StringOutput>(SCENE).value }
        )
      ),
      assembler = { "done" }
    )

    assertEquals(listOf(SCENE, PAINT), plan.topologicalSort().map { it.id })
    assertEquals(2, plan.executionLayers().size)
  }
}
