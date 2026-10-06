package net.hanasashimi.client

/**
 * 26.2 已确认 API（用于功能实现，保留作参考）：
 * - 相机位置：net.minecraft.client.Camera.position(): Vec3
 * - 方块默认态：Block.defaultBlockState()
 * - 注册表查询：Registry.getValue(net.minecraft.resources.Identifier.parse("minecraft:stone"))
 * - 资源标识符：net.minecraft.resources.Identifier（旧的 ResourceLocation 已移除）
 * - 窗口句柄：com.mojang.blaze3d.platform.Window.handle()
 * - 键位注册：net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(...)
 * - gizmo 线框：net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives.addLine(Vec3,Vec3,int,float)
 *              + submit(SubmitNodeCollector, CameraRenderState, boolean)，坐标用世界坐标（内部做相机偏移）
 * - 相机渲染态：LevelRenderContext.levelState().cameraRenderState
 */
object TApi26 {
    const val note = "reference only"
}