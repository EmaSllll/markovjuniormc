package net.hanasashimi.client

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * 尺寸编辑子页面：从列表页点击某模型进入。
 * 显示所选模型，提供 长(X)/宽(Y)/高(Z) 输入。
 * 点击列表中某文件时已算选中（写回石镐稿件），这里只负责尺寸与生成。
 */
class MarkovEditScreen(private val name: String) : Screen(Component.literal("编辑: $name")) {

    private lateinit var xBox: EditBox
    private lateinit var yBox: EditBox
    private lateinit var zBox: EditBox

    private var status = Component.literal("")

    private val WHITE = 0xFFFFFFFF.toInt()
    private val GRAY = 0xFFAAAAAA.toInt()
    private val CYAN = 0xFF00E5FF.toInt()

    override fun init() {
        super.init()
        val font = minecraft.font
        val s = MarkovLibrary.getSize(name)

        xBox = EditBox(font, 150, 60, 90, 18, Component.literal("长 X")).apply { setValue(s.x.toString()) }
        yBox = EditBox(font, 150, 86, 90, 18, Component.literal("宽 Y")).apply { setValue(s.y.toString()) }
        zBox = EditBox(font, 150, 112, 90, 18, Component.literal("高 Z")).apply { setValue(s.z.toString()) }
        addRenderableWidget(xBox)
        addRenderableWidget(yBox)
        addRenderableWidget(zBox)

        // 生成并应用：存尺寸 -> 生成产物 -> 刷新预览 -> 设为石镐稿件
        addRenderableWidget(
            Button.builder(Component.literal("生成并应用")) {
                applyAndGenerate()
            }.pos(150, 146).size(90, 20).build()
        )

        // 删除该模型
        addRenderableWidget(
            Button.builder(Component.literal("删除此模型")) {
                MarkovLibrary.delete(name)
                if (MarkovBuildController.selectedModel == name) {
                    MarkovBuildController.selectedModel = null
                }
                minecraft.setScreenAndShow(MarkovLibScreen())
            }.pos(150, 180).size(90, 20).build()
        )

        // 返回列表
        addRenderableWidget(
            Button.builder(Component.literal("返回列表")) {
                minecraft.setScreenAndShow(MarkovLibScreen())
            }.pos(150, 210).size(90, 20).build()
        )
    }

    private fun applyAndGenerate() {
        val x = parseDim(xBox.value, 8)
        val y = parseDim(yBox.value, 8)
        val z = parseDim(zBox.value, 8)
        MarkovLibrary.setSize(name, x, y, z)
        runCatching { MarkovLibrary.generate(name) }
            .onSuccess { runCatching { MarkovPreviews.ensure(name) } }
            .onFailure { status = Component.literal("生成失败: ${it.message}") }
        MarkovBuildController.select(name)
        status = Component.literal("已生成 $name：${x}x${y}x$z")
    }

    private fun parseDim(v: String, def: Int): Int {
        val n = v.trim().toIntOrNull() ?: return def
        return if (n < 1) 1 else n
    }

    override fun extractRenderState(extractor: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(extractor, mouseX, mouseY, delta)
        val font = minecraft.font

        extractor.text(font, "编辑模型: $name", 12, 8, WHITE)
        extractor.text(font, "\u5df2\u9009\u4e2d\uff1a\u77f3\u9550\u53f3\u952e\u8bbe\u951a\u70b9\uff0cZ \u751f\u6210", 12, 28, CYAN)

        // 输入框标签
        extractor.text(font, "长 X", 122, 64, GRAY)
        extractor.text(font, "宽 Y", 122, 90, GRAY)
        extractor.text(font, "高 Z", 122, 116, GRAY)

        // 状态
        extractor.text(font, status.string, 12, this.height - 18, GRAY)
    }
}