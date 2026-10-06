package net.hanasashimi.client

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * markovGeneration 生成文件列表主页。
 * 每行 = 一个 XML 模型，行尾一个原生"删除"按钮（点击直接删除，不做二次确认）。
 *  - 左键单击行正文：仅选中（设为石镐粘贴稿件），不进入子页
 *  - 右键单击行正文：选中并打开尺寸编辑子页面
 */
class MarkovLibScreen : Screen(Component.literal("生成文件列表")) {

    private data class Row(val name: String, val display: String)

    private val rows = mutableListOf<Row>()

    // 目录指纹：记录每个 XML 的修改时间 + 尺寸表的修改时间。
    // 指纹变化才重建列表，实现 GUI 内"热更新"（放进/删除 XML、改尺寸都即时生效）。
    private var lastSig: String? = null

    private val listX = 12
    private val listW = 220
    private val rowH = 16
    private val topY = 26
    private val binW = 40 // 行尾"删除"按钮占宽

    private val WHITE = 0xFFFFFFFF.toInt()
    private val GRAY = 0xFFAAAAAA.toInt()
    private val CYAN = 0xFF00E5FF.toInt()
    private val DARK = 0x66333333.toInt()

    override fun init() {
        super.init()
        buildRows()
    }

    override fun tick() {
        super.tick()
        val sig = signature()
        if (sig != lastSig) {
            lastSig = sig
            buildRows()
        }
    }

    /** 目录指纹：文件名 + 各 XML 修改时间 + 尺寸表修改时间。 */
    private fun signature(): String {
        val entries = MarkovLibrary.list()
        val sizes = MarkovLibrary.dir.resolve(".sizes.json")
        val sizesM = try {
            java.nio.file.Files.getLastModifiedTime(sizes).toMillis()
        } catch (_: Exception) { 0L }
        val parts = entries.map { e ->
            val m = try {
                java.nio.file.Files.getLastModifiedTime(e.xml).toMillis()
            } catch (_: Exception) { 0L }
            "${e.name}:$m"
        }.sorted()
        return parts.joinToString("|") + "|sizes:$sizesM"
    }

    private fun buildRows() {
        clearWidgets()
        rows.clear()

        // 顶部"打开文件夹"按钮：在系统文件管理器里弹出 markovGeneration 供直接放入 XML
        addRenderableWidget(
            Button.builder(Component.literal("打开文件夹")) {
                MarkovLibrary.openFolder()
            }.pos(120, 5).size(78, 14).build()
        )
        // "资源目录"：放模型引用的 tileset 图集（rules/<folder>/<file>.png）
        addRenderableWidget(
            Button.builder(Component.literal("资源目录")) {
                MarkovLibrary.openModelFolder()
            }.pos(200, 5).size(70, 14).build()
        )

        var idx = 0
        for (e in MarkovLibrary.list()) {
            val s = MarkovLibrary.getSize(e.name)
            rows.add(Row(e.name, "${e.name}   ${s.x}x${s.y}x${s.z}"))
            val y = topY + idx * rowH
            val bx = listX + listW - binW
            // 行尾原生"删除"按钮：无二次确认
            addRenderableWidget(
                Button.builder(Component.literal("删除")) {
                    MarkovLibrary.delete(e.name)
                    if (MarkovBuildController.selectedModel == e.name) {
                        MarkovBuildController.selectedModel = null
                    }
                    minecraft.setScreenAndShow(MarkovLibScreen())
                }.pos(bx, y).size(binW, 14).build()
            )
            idx++
        }
    }

    override fun extractRenderState(extractor: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        super.extractRenderState(extractor, mouseX, mouseY, delta)
        val font = minecraft.font

        extractor.text(font, "生成文件列表", 12, 8, WHITE)
        extractor.text(font, "\u5de6\u952e\u9009\u4e2d\uff1b\u53f3\u952e\u6253\u5f00\u5217\u8868\u9879\u6d4f\u89c8", listX, this.height - 32, GRAY)
        // 显示真实读取目录，方便确认 XML 该放哪
        extractor.text(font, MarkovLibrary.dir.toString(), listX, this.height - 44, GRAY)
        extractor.text(font, "资源目录: " + MarkovPaths.modelResources().toString(), listX, this.height - 56, GRAY)

        if (rows.isEmpty()) {
            extractor.text(font, "\u5c1a\u65e0\u6a21\u578b\u3002\u8bf7\u5c06 markovjunior XML \u653e\u5165", listX, topY + 10, GRAY)
            extractor.text(font, MarkovLibrary.dir.toString(), listX, topY + 24, CYAN)
            extractor.text(font, "\u524d\uff0c\u7136\u540e\u91cd\u65b0\u6253\u5f00\u6b64\u754c\u9762\u3002Esc \u8fd4\u56de\u6e38\u620f", listX, topY + 38, GRAY)
            return
        }

        var idx = 0
        for (row in rows) {
            val y = topY + idx * rowH
            val sel = row.name == MarkovBuildController.selectedModel
            if (sel) extractor.fill(listX, y, listX + listW, y + rowH, DARK)
            // 文字只显示到删除按钮左侧
            extractor.text(font, row.display, listX + 2, y + 4, if (sel) CYAN else WHITE)
            idx++
        }

        extractor.text(font, "左键选中 / 右键打开，行尾删除，Esc 返回", listX, this.height - 16, GRAY)
    }

    override fun mouseClicked(event: MouseButtonEvent, flag: Boolean): Boolean {
        // 先交给子控件（行尾"删除"按钮）
        if (super.mouseClicked(event, flag)) return true

        val x = event.x()
        val y = event.y()
        // 正文区域（避开行尾删除按钮）
        if (x < listX || x >= listX + listW - binW) return false

        val rowIdx = ((y - topY) / rowH).toInt()
        if (rowIdx !in rows.indices) return false

        val name = rows[rowIdx].name
        // 左键 / 右键：都先选中（写回石镐粘贴稿件）
        MarkovBuildController.select(name)
        // 右键才打开尺寸编辑子页面
        if (event.button() == 1) {
            minecraft.setScreenAndShow(MarkovEditScreen(name))
        }
        return true
    }
}