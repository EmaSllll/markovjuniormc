package net.hanasashimi.client

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW
import java.nio.file.Files

/**
 * 岩石建造工具：把 markovjuniormc (CLI) 生成的 JSON 产物物化到游戏世界。
 * - 手持石镐，右键点击设定锚点；
 * - 在锚点位置渲染一个青色线框（尺寸来自 JSON 的 size，与结构方块一致）；
 * - 按 Z 键在青色区域内放置方块。
 * 所有逻辑均为客户端单机场景。
 */
object MarkovBuildController {
    // 青色线框 (AARRGGBB)
    private val LINE_COLOR: Int = 0xFF00E5FF.toInt()
    private const val LINE_WIDTH = 2.0f

    val buildKey: KeyMapping = KeyMappingHelper.registerKeyMapping(
        KeyMapping(
            "key.markovjuniormc.build",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Z,
            KeyMapping.Category.GAMEPLAY,
        )
    )

    // B 键打开 markovGeneration 管理界面
    val guiKey: KeyMapping = KeyMappingHelper.registerKeyMapping(
        KeyMapping(
            "key.markovjuniormc.gui",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            KeyMapping.Category.GAMEPLAY,
        )
    )

    // 当前加载好的模型（null = 尚无产物）。在后台线程生成，完成后再回主线程赋值，故加 @Volatile。
    @Volatile
    var model: LoadedModel? = null

    // 单一线程后台执行模型生成 / 预览生成 / 产物解析，避免在主线程阻塞导致卡顿。
    private val genExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "markov-gen").apply { isDaemon = true }
    }

    // 分帧放置队列：按 Z 后不一次性 setBlock，而是每 tick 放一批，玩家移动/生存不冻结。
    private var pendingQueue: java.util.ArrayDeque<Pair<BlockPos, String>>? = null
    private var pendingTotal = 0
    private var pendingPlaced = 0

    // 当前选中的模型名（来自 markovGeneration 文件库；GUI 里可切换）
    var selectedModel: String? = null

    // 锚点：石镐点击的方块坐标；青色线框从这里开始
    var anchor: BlockPos? = null

    // 翻译规则（字符 -> 方块ID）
    var translation: Map<Char, String> = emptyMap()

    fun initialize() {
        reload()

        // 右键设定锚点 / Z 键生成建筑
        // 必须用 START_CLIENT_TICK：STAGE 结束时会先于我们消费 keyUse，
        // 到 END_CLIENT_TICK 时 consumeClick() 已返回 false，导致无法设锚点。
        ClientTickEvents.START_CLIENT_TICK.register { minecraft -> onTick(minecraft) }

        // 提取阶段补充一次数据（保留备用）
        LevelExtractionEvents.END_EXTRACTION.register { }

        // DRAW 阶段绘制青色线框（使用现代 gizmo 管线，世界坐标）
        LevelRenderEvents.BEFORE_GIZMOS.register { ctx ->
            val anc = anchor ?: return@register
            val m = model ?: return@register
            val cameraState = ctx.levelState().cameraRenderState
            val prim = DrawableGizmoPrimitives()

            val minX = anc.x.toDouble()
            val minY = anc.y.toDouble()
            val minZ = anc.z.toDouble()
            val maxX = minX + m.sizeX
            val maxY = minY + m.sizeZ // MJ Z(竖直) -> 世界 Y
            val maxZ = minZ + m.sizeY // MJ Y -> 世界 Z

            addBoxEdges(prim, minX, minY, minZ, maxX, maxY, maxZ)
            prim.submit(ctx.submitNodeCollector(), cameraState, true)
        }
    }

    private fun addBoxEdges(
        prim: DrawableGizmoPrimitives,
        minX: Double, minY: Double, minZ: Double,
        maxX: Double, maxY: Double, maxZ: Double,
    ) {
        fun line(a: DoubleArray, b: DoubleArray) {
            prim.addLine(
                Vec3(a[0], a[1], a[2]),
                Vec3(b[0], b[1], b[2]),
                LINE_COLOR, LINE_WIDTH
            )
        }

        // 底部四边 (Y=min)
        line(doubleArrayOf(minX, minY, minZ), doubleArrayOf(maxX, minY, minZ))
        line(doubleArrayOf(maxX, minY, minZ), doubleArrayOf(maxX, minY, maxZ))
        line(doubleArrayOf(maxX, minY, maxZ), doubleArrayOf(minX, minY, maxZ))
        line(doubleArrayOf(minX, minY, maxZ), doubleArrayOf(minX, minY, minZ))
        // 顶部四边 (Y=max)
        line(doubleArrayOf(minX, maxY, minZ), doubleArrayOf(maxX, maxY, minZ))
        line(doubleArrayOf(maxX, maxY, minZ), doubleArrayOf(maxX, maxY, maxZ))
        line(doubleArrayOf(maxX, maxY, maxZ), doubleArrayOf(minX, maxY, maxZ))
        line(doubleArrayOf(minX, maxY, maxZ), doubleArrayOf(minX, maxY, minZ))
        // 四条竖边
        line(doubleArrayOf(minX, minY, minZ), doubleArrayOf(minX, maxY, minZ))
        line(doubleArrayOf(maxX, minY, minZ), doubleArrayOf(maxX, maxY, minZ))
        line(doubleArrayOf(maxX, minY, maxZ), doubleArrayOf(maxX, maxY, maxZ))
        line(doubleArrayOf(minX, minY, maxZ), doubleArrayOf(minX, maxY, maxZ))
    }

    private fun hasToolInHand(): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        return player.mainHandItem.item == Items.STONE_PICKAXE
    }

    private fun onTick(minecraft: Minecraft) {
        val player = minecraft.player ?: return

        // 每帧推进分帧放置队列（避免一次性 setBlock 卡死主线程）
        tickPlacements()

        // 管理界面必须"随时可开"：不要求创造/不要求石镐，放在所有条件之前
        while (guiKey.consumeClick()) {
            minecraft.setScreenAndShow(MarkovLibScreen())
        }

        if (!player.isCreative()) return
        if (!hasToolInHand()) return

        // 右键设定锚点
        while (minecraft.options.keyUse.consumeClick()) {
            val hit = minecraft.hitResult
            if (hit is BlockHitResult) {
                anchor = hit.blockPos
                reload()
                player.sendSystemMessage(
                    Component.literal(
                        "锚点已设为 " + hit.blockPos + "，尺寸=" +
                            (model?.let { "${it.sizeX}x${it.sizeZ}x${it.sizeY}" } ?: "未加载")
                    )
                )
            }
        }

        // Z 键生成建筑
        while (buildKey.consumeClick()) {
            generate()
        }
    }

    /** 切换石镐粘贴的稿件为指定模型，并重新加载其产物与预览。 */
    fun select(name: String) {
        selectedModel = name
        reload()
    }

    /** 供 GUI 重建后重新加载。 */
    fun reloadNow() {
        reload()
    }

    private fun find(relative: String): java.nio.file.Path {
        val prod = runDir().resolve(relative)
        if (java.nio.file.Files.exists(prod)) return prod
        return java.nio.file.Paths.get("run").resolve(relative)
    }

    /** (重新)加载翻译规则与当前选中模型的产物。 */
    private fun reload() {
        val trans = MarkovTranslation.load(MarkovPaths.translation())
        if (trans.isNotEmpty()) {
            translation = trans
        }
        // 未指定选中时取文件库第一个 XML
        val entries = MarkovLibrary.list()
        entries.firstOrNull()?.let { first ->
            selectedModel = selectedModel ?: first.name
        }
        val name = selectedModel ?: return
        selectedModel = name
        // 每次都重新生成（推进规则到终止）再加载，避免复用旧的 / 全 0 产物。
        // 大模型生成耗时，放到后台线程，完成后再回主线程给 model 赋值，避免卡住主线程。
        val mc = Minecraft.getInstance()
        genExecutor.submit {
            val json = try {
                MarkovLibrary.generate(name)
            } catch (e: Exception) {
                mc.execute {
                    mc.player?.sendSystemMessage(Component.literal("生成 $name 失败: ${e.message}"))
                }
                return@submit
            }
            // 首次打开自动生成预览缓存
            try {
                MarkovPreviews.ensure(name)
            } catch (_: Exception) {
            }
            val loaded = try { ModelLoader.tryLoad(json) } catch (_: Exception) { null }
            if (loaded != null) {
                mc.execute { model = loaded }
            }
        }
    }

    private fun runDir(): java.nio.file.Path {
        val appdata = System.getenv("APPDATA")
        return if (!appdata.isNullOrEmpty()) {
            java.nio.file.Paths.get(appdata, ".minecraft")
        } else {
            java.nio.file.Paths.get(System.getProperty("user.home"), ".minecraft")
        }
    }

    /** 每帧从放置队列取一批 setBlock，避免一次性放置上万方块冻住游戏。 */
    private fun tickPlacements() {
        val queue = pendingQueue ?: return
        val player = Minecraft.getInstance().player ?: return
        val level = player.level()
        var n = 0
        while (n < 512 && queue.isNotEmpty()) {
            val (pos, id) = queue.removeFirst()
            if (placeBlock(level, pos, id)) pendingPlaced++
            n++
        }
        if (queue.isEmpty()) {
            val total = pendingTotal
            val placed = pendingPlaced
            pendingQueue = null
            pendingTotal = 0
            pendingPlaced = 0
            player.sendSystemMessage(
                if (total == 0)
                    Component.literal("已放置 0 个方块（没有可放置的符号，或对应映射缺失）")
                else
                    Component.literal("已放置 $placed / $total 个方块")
            )
        }
    }

    /** 按 JSON 的 size 在锚点处放置方块。解析在后台线程完成，放置分帧执行。 */
    private fun generate() {
        val player = Minecraft.getInstance().player ?: return
        val base = anchor ?: run {
            player.sendSystemMessage(Component.literal("请先用石镐右键设定锚点"))
            return
        }
        val loaded = model ?: run {
            player.sendSystemMessage(Component.literal("未找到产物模型，请先在 markovGeneration 放入 XML 或展开 GUI 生成"))
            return
        }
        if (loaded.cells.isEmpty()) {
            player.sendSystemMessage(Component.literal("产物为空，无法放置"))
            return
        }

        val mc = Minecraft.getInstance()
        player.sendSystemMessage(Component.literal("正在解析产物…"))
        // 后台线程把"可放置的格子"解析成 (坐标, 方块ID) 列表，避免主线程做大量循环。
        genExecutor.submit {
            val jobs = ArrayList<Pair<BlockPos, String>>()
            // MJ 轴：X/Y 水平，Z 竖直。映射为 世界X=X, 世界Y=Z, 世界Z=Y
            for (z in 0 until loaded.sizeZ) {
                for (y in 0 until loaded.sizeY) {
                    for (x in 0 until loaded.sizeX) {
                        val index = x + y * loaded.sizeX + z * loaded.sizeX * loaded.sizeY
                        val cell = loaded.cells.getOrNull(index) ?: continue
                        // 注意：cell 是符号索引，0 不一定是"空"（如 villa 的 F=0 是石头）。
                        // 是否放置看 symbols[cell] 在 translation 里有没有对应方块；没有就跳过。
                        val symbol = loaded.symbols.getOrNull(cell) ?: continue
                        val blockId = translation[symbol] ?: continue
                        jobs.add(base.offset(x, z, y) to blockId)
                    }
                }
            }
            mc.execute {
                pendingTotal = jobs.size
                pendingPlaced = 0
                pendingQueue = java.util.ArrayDeque(jobs)
                if (jobs.isEmpty()) {
                    player.sendSystemMessage(Component.literal("已放置 0 个方块（没有可放置的符号，或对应映射缺失）"))
                }
            }
        }
    }

    private fun placeBlock(level: Level, pos: BlockPos, id: String): Boolean {
        val block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id))
        if (block == Blocks.AIR) return false
        val prev = level.getBlockState(pos)
        val state = block.defaultBlockState()
        level.setBlock(pos, state, 3)
        // 强制客户端重绘 + 光照重算，避免出现半透明"幽灵方块"
        level.sendBlockUpdated(pos, prev, state, 3)
        level.getLightEngine().checkBlock(pos)
        return true
    }
}

/** 从 CLI JSON 加载后的模型数据。 */
data class LoadedModel(
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val symbols: CharArray,
    val cells: IntArray,
)

/** 解析 /run 或 .minecraft 下的 translation_marlov.txt：字符 -> 方块ID。 */
object MarkovTranslation {
    fun load(path: java.nio.file.Path): Map<Char, String> {
        val map = linkedMapOf<Char, String>()
        if (!java.nio.file.Files.exists(path)) return map
        java.nio.file.Files.readAllLines(path).forEach { raw ->
            val line = raw.replace("\uFEFF", "").substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val sep = line.indexOf('=')
            if (sep <= 0) return@forEach
            val key = line.substring(0, sep).trim()
            val value = line.substring(sep + 1).trim()
            if (key.length == 1 && value.isNotEmpty()) {
                map[key[0]] = value
            }
        }
        return map
    }
}

/** 加载 CLI 生成的 markov-output.json。 */
object ModelLoader {
    fun tryLoad(path: java.nio.file.Path): LoadedModel? {
        return try {
            if (!java.nio.file.Files.exists(path)) return null
            val text = java.nio.file.Files.readString(path)
            val root = com.google.gson.JsonParser.parseString(text).asJsonObject
            // size 是形如 [x,y,z] 的数组
            val sizeArr = root.getAsJsonArray("size")
            val sx = sizeArr[0].asInt
            val sy = sizeArr[1].asInt
            val sz = sizeArr[2].asInt
            // symbols 是符号表字符串；cells 是每格的符号索引数组（x-fastest，0=空）
            val symbols = root.get("symbols").asString.toCharArray()
            val cellsArr = root.getAsJsonArray("cells")
            val cellCount = sx * sy * sz
            val cells = IntArray(cellsArr.size()) { cellsArr.get(it).asInt }
            if (cells.size != cellCount) return null
            LoadedModel(sx, sy, sz, symbols, cells)
        } catch (e: Exception) {
            null
        }
    }
}