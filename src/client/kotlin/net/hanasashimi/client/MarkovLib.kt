package net.hanasashimi.client

import com.caimeo.markovpaper.xml.MarkovXmlCompiler
import com.caimeo.markovpaper.xml.ModelResources
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * .minecraft 根目录定位：
 * - dev 环境（Loom runClient）游戏目录是项目下的 run/；
 * - 正式安装后是 %APPDATA%\.minecraft。
 * 统一用 run/（存在即优先），否则回退到 APPDATA/.minecraft。
 */
object MarkovPaths {
    fun root(): Path {
        val runDir = Path.of("run").toAbsolutePath().normalize()
        val prod = prodMinecraft().toAbsolutePath().normalize()
        // dev：runClient 工作目录在项目根，游戏目录是 run/（其下存在 markovGeneration）。
        // 用绝对路径判定，绝不以"当前工作目录"为准，避免启动器改 CWD 后静默回退到 APPDATA。
        return if (Files.isDirectory(runDir.resolve("markovGeneration"))) runDir else prod
    }

    fun generation(): Path = root().resolve("markovGeneration")

    /**
     * 运行时可添加的模型资源目录（如 apartment 等模型引用的 tileset 图集）。
     * user XML 里 `file=…`/`fin/fout=…` 会拼成 rules/<folder>/<file>.png(.vox)，
     * 优先从本目录读，读不到再回退内置 /markovjunior 资源。
     * 对应放置位置：<root>/modelResources/rules/<folder>/<file>.png
     */
    fun modelResources(): Path = root().resolve("modelResources")

    fun preview(): Path = root().resolve("assets").resolve("markovModelPreview")

    fun translation(): Path = root().resolve("translation_marlov.txt")

    private fun prodMinecraft(): Path {
        val appdata = System.getenv("APPDATA")
        return if (!appdata.isNullOrEmpty()) {
            Path.of(appdata, ".minecraft")
        } else {
            Path.of(System.getProperty("user.home"), ".minecraft")
        }
    }
}

/**
 * markovGeneration 文件库：
 * 列出 XML、编辑尺寸、用固定种子就地生成 JSON、删除模型。
 * 每个命名模型对应 <name>.xml（用户放入）与生成的 <name>.json（石镐粘贴用）。
 */
object MarkovLibrary {
    // 引擎默认的格子上限；GUI 生成时跟随时设尺寸放宽（大于此值才用尺寸本身作上限）。
    private const val MAX_CELLS_DEFAULT: Long = 2_000_000L

    data class ModelEntry(val name: String, val xml: Path, val json: Path)
    data class Size(val x: Int, val y: Int, val z: Int)

    private val gson = Gson()

    val dir: Path get() = MarkovPaths.generation()
    private fun sizesFile() = dir.resolve(".sizes.json")

    fun ensureDir() = Files.createDirectories(dir)

    /**
     * 在系统文件管理器里打开模型目录（跨平台）：
     * 优先用 java.awt.Desktop，失败则回退到各平台的命令行打开器。
     */
    fun openFolder() {
        openPath(ensureDir())
    }

    /** 打开"模型资源目录"：user 模型引用的外部资源（如 tilesets/Paths.xml）放这里。 */
    fun openModelFolder() {
        try {
            // 按常见引用预建 tilesets/ 子目录（对应报错里的 tilesets/Paths.xml）
            Files.createDirectories(MarkovPaths.modelResources().resolve("tilesets"))
        } catch (_: Throwable) {
        }
        openPath(MarkovPaths.modelResources())
    }

    private fun openPath(directory: Path) {
        try {
            val desktop = java.awt.Desktop.getDesktop()
            if (desktop.isSupported(java.awt.Desktop.Action.OPEN)) {
                desktop.open(directory.toFile())
                return
            }
        } catch (_: Throwable) {
            // headless 或 AWT 不可用时回退到平台命令
        }
        val os = System.getProperty("os.name", "").lowercase()
        val launcher = when {
            os.contains("mac") -> listOf("open")
            os.contains("win") -> listOf("explorer.exe")
            else -> listOf("xdg-open")
        }
        try {
            ProcessBuilder(launcher + directory.toString()).start()
        } catch (_: Throwable) {
            // 打不开就不打扰用户；GUI 顶部仍显示路径
        }
    }

    fun list(): List<ModelEntry> {
        ensureDir()
        return Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".xml") && Files.isRegularFile(it) }
                .map { p ->
                    val base = p.fileName.toString().removeSuffix(".xml")
                    ModelEntry(base, p, dir.resolve("$base.json"))
                }
                .toList()
                .sortedBy { it.name }
        }
    }

    /** 当前选中模型对应的产物 JSON 路径（若该模型存在产物体）。 */
    fun jsonPath(name: String): Path = dir.resolve("$name.json")

    fun getSize(name: String): Size {
        val v = readSizes()[name] ?: return Size(8, 8, 8)
        val parts = v.split(',')
        return if (parts.size == 3) {
            Size(parts[0].trim().toIntOrNull() ?: 8, parts[1].trim().toIntOrNull() ?: 8, parts[2].trim().toIntOrNull() ?: 8)
        } else Size(8, 8, 8)
    }

    fun setSize(name: String, x: Int, y: Int, z: Int) {
        val map = readSizes()
        map[name] = "$x,$y,$z"
        Files.writeString(sizesFile(), gson.toJson(map))
    }

    fun deleteSize(name: String) {
        val map = readSizes()
        map.remove(name)
        if (map.isEmpty()) Files.deleteIfExists(sizesFile())
        else Files.writeString(sizesFile(), gson.toJson(map))
    }

    fun delete(name: String) {
        list().filter { it.name == name }.forEach {
            Files.deleteIfExists(it.xml)
            Files.deleteIfExists(it.json)
        }
        deleteSize(name)
    }

    /**
     * 用固定种子（seed=0）生成该模型，产物 JSON 写到 markovGeneration/<name>.json。
     * 尺寸来自对应用户在 GUI 里设定的长宽高；未设定则默认 8x8x8。
     */
    fun generate(name: String, seed: Long = 0L): Path {
        val entry = list().firstOrNull { it.name == name }
            ?: error("Model '$name' not found")
        val size = getSize(name)
        val xml = Files.readString(entry.xml)
        // 允许模型引用外部 tileset 等资源：优先读运行时 modelResources 目录，回退内置资源。
        val resources = ModelResources(MarkovPaths.modelResources())
        // 不设固定 200 万上限：maxCells 跟随时设的尺寸，让更大的模型也能生成。
        // cellCount 超过 Int.MAX 时引擎内层的 require 仍会兜底报错。
        val cellCount = size.x.toLong() * size.y.toLong() * size.z.toLong()
        val maxCells = if (cellCount > MAX_CELLS_DEFAULT) cellCount else MAX_CELLS_DEFAULT
        val model = MarkovXmlCompiler.compile(xml, size.x, size.y, size.z, seed, resources, maxCells)
        // 推进规则到终止（fill 等一步即稳定；对非终止模型给出上限避免卡死）。
        // 与 CLI run 一致：编译出的只是初始网格，必须 advance 才产生最终布局。
        val maxSteps = 1_000_000
        var steps = 0
        while (model.node.advance() != null) {
            if (++steps > maxSteps) break
        }
        val finalGrid = model.state.grid
        val cells = IntArray(size.x * size.y * size.z)
        var i = 0
        for (zz in 0 until size.z) for (yy in 0 until size.y) for (xx in 0 until size.x) {
            cells[i++] = finalGrid[xx, yy, zz].toInt()
        }
        val symbols = model.symbols.joinToString("")
        val sb = StringBuilder()
        sb.append("{\"size\":[").append(size.x).append(',').append(size.y).append(',').append(size.z)
            .append("],\"symbols\":\"").append(symbols)
            .append("\",\"seed\":").append(seed).append(",\"order\":\"x-fastest\",\"cells\":[")
        sb.append(cells.joinToString(","))
        sb.append(']').append('}')
        Files.deleteIfExists(entry.json)
        Files.writeString(entry.json, sb.toString())
        return entry.json
    }

    private val sizesType = object : TypeToken<MutableMap<String, String>>() {}.type

    private fun readSizes(): MutableMap<String, String> {
        val f = sizesFile()
        if (!Files.exists(f)) return mutableMapOf()
        return try {
            val map: MutableMap<String, String> = gson.fromJson(Files.readString(f), sizesType)
            map ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }
}

/**
 * 预览缓存：首次（该 XML 在 GUI 里被打开时）生成一次，之后直接读取。
 * 目录 .minecraft/assets/markovModelPreview/<name>.json。
 * 说明：现阶段以固定种子 JSON 作为预览缓存（结构同 markovGeneration 产物，可直接由线框渲染）。
 * 若确认改用原版 NBT（StructureTemplate *.nbt），替换这里即可。
 */
object MarkovPreviews {
    fun path(name: String): Path = MarkovPaths.preview().resolve("$name.json")

    /** 缓存已存在则直接返回，否则生成一次并缓存。 */
    fun ensure(name: String): Path {
        val target = path(name)
        if (Files.exists(target)) return target
        Files.createDirectories(target.parent)
        val generated = MarkovLibrary.generate(name)
        Files.copy(generated, target, StandardCopyOption.REPLACE_EXISTING)
        return target
    }
}