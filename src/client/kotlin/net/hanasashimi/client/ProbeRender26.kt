package net.hanasashimi.client

/**
 * 26.2 渲染 API 探测结果（保留作参考）：
 * - com.mojang.blaze3d.vertex.Tesselator        X 已移除
 * - com.mojang.blaze3d.vertex.BufferUploader    X 已移除
 * - net.minecraft.client.renderer.RenderType    X 已移除
 * - com.mojang.blaze3d.systems.RenderSystem     存在
 * - net.minecraft.client.renderer.SubmitNodeCollector 存在
 * - net.minecraft.client.renderer.GameRenderer   存在
 * 结论：26.2 用 gizmo 管线 DrawableGizmoPrimitives / RenderState / SubmitNodeCollector，
 *       不再使用旧版的 RenderType/Tesselator/BufferUploader 立即模式。
 */
object ProbeRender26 {
    val note = "see TApi26 for the final working rendering approach"
}