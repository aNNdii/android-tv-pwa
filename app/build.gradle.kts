import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.net.URI
import javax.imageio.ImageIO

plugins { id("com.android.application") }

// ---------------------------------------------------------------------------
// Config: webapps.json (private, gitignored). Other file: -Pwebapps=<path>
// ---------------------------------------------------------------------------

data class WebApp(
    val id: String, val name: String, val url: String, val banner: File?, val color: Int?, // null: none given
    val cursor: Boolean, val desktopMode: Boolean, val zoom: Int, val allowInsecureContent: Boolean,
    val blocklist: Boolean,            // false only if the app entry says "blocklist": false
    val allowDomains: List<String>,    // validated, lowercase
)

/** [blocklist]: the top-level http(s) address of the domain list, or null. */
data class Config(val blocklist: String?, val apps: List<WebApp>)

val configPath = providers.gradleProperty("webapps").getOrElse("webapps.json")
val configFile = rootProject.layout.projectDirectory.file(configPath)
val configText = providers.fileContents(configFile).asText.orNull
    ?: throw GradleException("$configPath fehlt: kopiere webapps.example.json nach webapps.json und trage deine Seiten ein.")

val javaKeywords = setOf(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
    "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
    "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
    "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
    "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null",
)
val topLevelKeys = setOf("adb", "apps", "blocklist")
val allowedKeys = setOf(
    "id", "name", "url", "banner", "color", "cursor", "desktopMode", "zoom", "allowInsecureContent",
    "blocklist", "allowDomains",
)
/** Banner of an app without its own colour: Catppuccin Mocha Base (the launcher is dark). */
val defaultColor = 0x1e1e2e

fun isHttpUrl(s: String): Boolean {
    val uri = try { URI(s) } catch (e: Exception) { null }
    return uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty()
}

fun loadConfig(text: String, baseDir: File, source: String): Config {
    val root = try {
        groovy.json.JsonSlurper().parseText(text)
    } catch (e: Exception) {
        throw GradleException("$source: kein gültiges JSON: ${e.message}")
    }
    val errors = mutableListOf<String>()
    if (root !is Map<*, *>) throw GradleException("$source: oberste Ebene muss ein Objekt sein")
    (root.keys - topLevelKeys).forEach { errors += "$it: unbekannter Key (erlaubt: ${topLevelKeys.joinToString()})" }
    if (root.containsKey("adb") && root["adb"] !is String) errors += "adb: muss ein String sein, z. B. \"192.0.2.10:5555\""
    val blocklist = root["blocklist"]
    if (root.containsKey("blocklist") && (blocklist !is String || !isHttpUrl(blocklist))) {
        errors += "blocklist: muss eine http(s)-Adresse sein"
    }
    val list = root["apps"]
    if (list !is List<*> || list.isEmpty()) {
        throw GradleException("$source: apps fehlt oder ist leer, siehe webapps.example.json")
    }
    val seen = mutableSetOf<String>()
    val apps = list.mapIndexedNotNull { i, raw ->
        if (raw !is Map<*, *>) { errors += "apps[$i]: muss ein Objekt sein"; return@mapIndexedNotNull null }
        val idHint = (raw["id"] as? String)?.let { " ($it)" } ?: ""
        val before = errors.size
        fun err(field: String, msg: String) { errors += "apps[$i]$idHint.$field: $msg" }
        (raw.keys.map { it.toString() } - allowedKeys).forEach { err(it, "unbekannter Key (erlaubt: ${allowedKeys.joinToString()})") }

        val id = raw["id"]
        when {
            id == null -> err("id", "fehlt")
            id !is String || !Regex("[a-z][a-z0-9]*").matches(id) -> err("id", "\"$id\" muss [a-z][a-z0-9]* sein")
            id in javaKeywords -> err("id", "\"$id\" ist ein reserviertes Java-Wort")
            !seen.add(id) -> err("id", "\"$id\" ist doppelt")
        }
        val name = raw["name"]
        when {
            name == null -> err("name", "fehlt")
            name !is String || name.isBlank() -> err("name", "muss ein nicht leerer String sein")
        }
        val url = raw["url"]
        when {
            url == null -> err("url", "fehlt")
            url !is String -> err("url", "muss ein String sein")
            !isHttpUrl(url) -> err("url", "\"$url\" ist keine gültige http(s)-URL")
        }
        var bannerFile: File? = null
        raw["banner"]?.let { b ->
            if (b !is String) { err("banner", "muss ein Pfad (String) sein"); return@let }
            val f = baseDir.resolve(b)
            if (!f.isFile) { err("banner", "Datei $b fehlt"); return@let }
            val magic = f.inputStream().use { s -> ByteArray(8).also { s.read(it) } }
            if (!magic.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))) {
                err("banner", "$b ist keine PNG-Datei"); return@let
            }
            val img = ImageIO.read(f)
            if (img != null && img.width * 9 != img.height * 16) {
                logger.quiet("Warnung: apps[$i]$idHint.banner: $b ist ${img.width}x${img.height}, nicht 16:9 (empfohlen 320x180)")
            }
            bannerFile = f
        }
        val color = raw["color"]
        if (color != null && (color !is String || !Regex("#[0-9a-fA-F]{6}").matches(color))) err("color", "\"$color\" muss #RRGGBB sein")
        val cursor = raw["cursor"]
        if (cursor != null && cursor !is Boolean) err("cursor", "muss true oder false sein (ohne Anführungszeichen)")
        val desktop = raw["desktopMode"]
        if (desktop != null && desktop !is Boolean) err("desktopMode", "muss true oder false sein (ohne Anführungszeichen)")
        val insecure = raw["allowInsecureContent"]
        if (insecure != null && insecure !is Boolean) err("allowInsecureContent", "muss true oder false sein (ohne Anführungszeichen)")
        val zoom = raw["zoom"]
        when {
            zoom == null -> {}
            zoom !is Int -> err("zoom", "${if (zoom is String) "\"$zoom\"" else zoom} muss eine ganze Zahl sein")
            zoom !in 50..300 -> err("zoom", "$zoom nicht in 50–300")
        }
        val appBlocklist = raw["blocklist"]
        if (raw.containsKey("blocklist") && appBlocklist != false) err("blocklist", "nur false erlaubt")
        val allow = raw["allowDomains"]
        val allowDomains = mutableListOf<String>()
        when {
            allow == null -> {}
            allow !is List<*> || allow.any { it !is String } -> err("allowDomains", "muss eine Liste von Domains sein")
            else -> allow.forEach { d ->
                val domain = (d as String).lowercase()
                if (BlocklistTask.DOMAIN.matches(domain)) allowDomains += domain
                else err("allowDomains", "\"$d\" ist keine Domain (ohne Schema, Pfad, Leerzeichen)")
            }
        }
        if (errors.size > before) return@mapIndexedNotNull null
        WebApp(
            id = id as String, name = name as String, url = url as String, banner = bannerFile,
            color = (color as String?)?.substring(1)?.toInt(16),
            cursor = cursor as Boolean? ?: true, desktopMode = desktop as Boolean? ?: false, zoom = zoom as Int? ?: 100,
            allowInsecureContent = insecure as Boolean? ?: false,
            blocklist = appBlocklist != false, allowDomains = allowDomains,
        )
    }
    if (errors.isNotEmpty()) throw GradleException("$source ist fehlerhaft:\n  " + errors.joinToString("\n  "))
    return Config(blocklist as String?, apps)
}

/** Escapes a value for an Android string resource. AGP already XML-escapes `&` and `<`. */
fun escapeAndroidString(s: String): String {
    val e = s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"").replace("\n", "\\n")
    return if (e.startsWith("@") || e.startsWith("?")) "\\$e" else e
}

fun javaString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val config = loadConfig(configText, configFile.asFile.parentFile, configPath)
val webApps = config.apps

// ---------------------------------------------------------------------------
// Banner: copy the given PNG, or render 320x180 with color + name
// ---------------------------------------------------------------------------

abstract class BannerTask : DefaultTask() {
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: RegularFileProperty
    @get:Input abstract val label: Property<String>
    @get:Input abstract val color: Property<Int>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().dir("drawable-xhdpi").asFile
        out.deleteRecursively(); out.mkdirs()
        val target = File(out, "banner.png")
        if (source.isPresent) { source.get().asFile.copyTo(target, overwrite = true); return }
        ImageIO.write(render(label.get(), color.get()), "png", target)
    }

    private fun render(text: String, rgb: Int): BufferedImage {
        val w = 320; val h = 180; val pad = 20
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(rgb); g.fillRect(0, 0, w, h)
        val bg = Color(rgb)
        val luma = 0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue
        g.color = if (luma > 150) Color(0x4c4f69) else Color(0xcdd6f4) // Catppuccin Latte / Mocha Text
        val maxW = w - 2 * pad
        var lines = listOf(text)
        for (size in 44 downTo 20 step 2) {
            g.font = Font(Font.SANS_SERIF, Font.BOLD, size)
            lines = wrap(text, g.fontMetrics, maxW)
            if (lines.size <= 2) break
        }
        if (lines.size > 2) lines = listOf(lines[0], ellipsize(lines.drop(1).joinToString(" "), g.fontMetrics, maxW))
        val fm = g.fontMetrics
        val lineH = fm.height
        var y = (h - lineH * lines.size) / 2 + fm.ascent
        for (line in lines) {
            g.drawString(line, (w - fm.stringWidth(line)) / 2, y)
            y += lineH
        }
        g.dispose()
        return img
    }

    /** Greedy word wrap; words wider than the line are broken by characters. */
    private fun wrap(text: String, fm: FontMetrics, maxW: Int): List<String> {
        val lines = mutableListOf<String>()
        var cur = ""
        for (word in text.trim().split(Regex("\\s+"))) {
            val cand = if (cur.isEmpty()) word else "$cur $word"
            if (fm.stringWidth(cand) <= maxW) { cur = cand; continue }
            if (cur.isNotEmpty()) lines += cur
            cur = ""
            for (ch in word) {
                if (fm.stringWidth(cur + ch) > maxW && cur.isNotEmpty()) { lines += cur; cur = "" }
                cur += ch
            }
        }
        if (cur.isNotEmpty()) lines += cur
        return lines
    }

    private fun ellipsize(s: String, fm: FontMetrics, maxW: Int): String {
        var t = s
        while (t.isNotEmpty() && fm.stringWidth("$t…") > maxW) t = t.dropLast(1)
        return t.trimEnd() + "…"
    }
}

// ---------------------------------------------------------------------------
// Blocklist: repo-root blocklist.txt (gitignored, written by mise run deploy from the top-level
// "blocklist" address, first line "# source: <address>") -> cleaned asset blocklist.txt per app
// ---------------------------------------------------------------------------

abstract class BlocklistTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    /**
     * Writes outputDir/blocklist.txt: [domains] of the source. Format contract with Blocklist.load (app side),
     * which hashes the raw bytes: asset name blocklist.txt, lowercase ASCII, one domain per line, each ended
     * by '\n' (no '\r', no comments). A break here builds fine but blocks nothing. Change both together.
     */
    @TaskAction
    fun run() {
        val out = outputDir.get().asFile
        out.deleteRecursively(); out.mkdirs()
        File(out, "blocklist.txt").writeText(domains(source.get().asFile.readText()).joinToString("\n", postfix = "\n"))
    }

    companion object {
        /** A host name without scheme, path, port or spaces; lowercase. Also used for allowDomains.
         *  Counterpart: the grep that counts domains in mise-tasks/deploy. Change both together. */
        val DOMAIN = Regex("[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?(\\.[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?)+")

        /** The single list-syntax rule: trim, lowercase, drop '#' comments, blank and non-domain lines, dedupe. */
        fun domains(text: String): Set<String> =
            text.lineSequence().map { it.trim().lowercase() }.filter { !it.startsWith("#") && DOMAIN.matches(it) }.toSet()

        /** [host] or one of its parent domains is in [domains] (suffix at a '.' boundary, never a substring). */
        fun listed(host: String, domains: Set<String>): Boolean {
            var h = host
            while (true) {
                if (h in domains) return true
                val dot = h.indexOf('.')
                if (dot < 0) return false
                h = h.substring(dot + 1)
            }
        }
    }
}

val blocklistFile: RegularFile = rootProject.layout.projectDirectory.file("blocklist.txt")

/** Parsed at configuration time for the checks below; null without a top-level "blocklist". */
val blocklistDomains: Set<String>? = config.blocklist?.let { url ->
    val text = providers.fileContents(blocklistFile).asText.orNull
        ?: throw GradleException("blocklist.txt fehlt: erst mise run deploy ausführen (lädt $url)")
    val first = text.lineSequence().first().trim()
    if (first != "# source: $url") {
        val old = if (first.startsWith("# source: ")) first.removePrefix("# source: ") else "unbekannter Quelle (keine # source:-Zeile)"
        logger.quiet("Warnung: blocklist.txt stammt von $old, nicht von $url")
    }
    BlocklistTask.domains(text)
}

/** The only place of the condition: the list is configured and this app does not opt out. */
val WebApp.blocklistOn: Boolean get() = blocklistDomains != null && blocklist
val WebApp.host: String get() = URI(url).host.lowercase()

webApps.forEachIndexed { i, app ->
    if (app.blocklistOn && BlocklistTask.listed(app.host, blocklistDomains!!)) {
        logger.quiet("Warnung: apps[$i] (${app.id}).url: ${app.host} steht in der Blockliste, wird für diese App erlaubt")
    }
}

// ---------------------------------------------------------------------------
// Android: one product flavor "w<id>" per web app
// ---------------------------------------------------------------------------

android {
    namespace = "local.tvwebapp"
    compileSdk = 36
    defaultConfig {
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures { buildConfig = true; resValues = true }
    // Deploy installs debug builds. Debuggable apps run their code almost unoptimised: loading the blocklist
    // took ~4 s on the TV instead of 0.2 s. Page inspection stays on via setWebContentsDebuggingEnabled.
    buildTypes { getByName("debug") { isDebuggable = false } }
    // Naming scheme "w<id>" / "local.tvwebapp.<id>" (and so the APK path) is also used by
    // mise-tasks/deploy. Change both together.
    flavorDimensions += "site"
    productFlavors {
        webApps.forEach { app ->
            create("w${app.id}") {
                dimension = "site"
                applicationId = "local.tvwebapp.${app.id}"
                resValue("string", "app_name", escapeAndroidString(app.name))
                buildConfigField("String", "START_URL", javaString(app.url))
                buildConfigField("boolean", "CURSOR", app.cursor.toString())
                buildConfigField("boolean", "DESKTOP_MODE", app.desktopMode.toString())
                buildConfigField("boolean", "ALLOW_INSECURE_CONTENT", app.allowInsecureContent.toString())
                buildConfigField("int", "ZOOM", app.zoom.toString())
                // Loading screen background, like the banner; -1: none given, the app takes its theme's base.
                buildConfigField("int", "COLOR", app.color?.let { "0x%06x".format(it) } ?: "-1")
                buildConfigField("boolean", "BLOCKLIST", app.blocklistOn.toString())
                // Never blocked through the list: allowDomains and the host of the app's own url, with subdomains.
                buildConfigField("String[]", "ALLOW_DOMAINS", "{" + (app.allowDomains + app.host).distinct().joinToString { javaString(it) } + "}")
            }
        }
    }
}

androidComponents {
    onVariants { variant ->
        val app = webApps.first { "w${it.id}" == variant.flavorName }
        val task = tasks.register<BannerTask>("${variant.name}Banner") {
            if (app.banner != null) source.set(app.banner)
            label.set(app.name)
            color.set(app.color ?: defaultColor)
        }
        variant.sources.res?.addGeneratedSourceDirectory(task, BannerTask::outputDir)
        if (app.blocklistOn) {
            val bl = tasks.register<BlocklistTask>("${variant.name}Blocklist") { source.set(blocklistFile) }
            variant.sources.assets?.addGeneratedSourceDirectory(bl, BlocklistTask::outputDir)
        }
    }
}
