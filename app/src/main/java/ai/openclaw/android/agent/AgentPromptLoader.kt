package ai.openclaw.android.agent

import android.content.Context
import android.util.Log
import java.io.File

/**
 * AgentPromptLoader - 从外部文件加载 system prompt
 *
 * Global prompt path: /sdcard/Android/data/ai.openclaw.android/files/system_prompt.md
 *
 * 优势:
 * - 无需重新编译即可调整 prompt
 * - 可通过文件管理器或 adb 直接编辑
 * - 首次启动时自动从 assets 复制默认模板
 */
object AgentPromptLoader {
    private const val TAG = "AgentPromptLoader"
    private const val FILE_NAME = "system_prompt.md"

    // Global prompt cache
    private var cachedPrompt: String? = null
    private var lastModified: Long = 0L

    // ========================
    // Global prompt (legacy API)
    // ========================

    /**
     * 加载全局 system prompt
     * 优先读取外部文件，不存在则从 assets 复制默认
     */
    fun load(context: Context): String {
        val now = System.currentTimeMillis()

        // 如果文件未修改且已缓存，直接返回
        val cached = cachedPrompt
        if (cached != null) {
            val file = getExternalFile(context)
            if (file.exists() && file.lastModified() <= lastModified) {
                return cached
            }
        }

        val prompt = try {
            val file = getExternalFile(context)
            if (file.exists()) {
                val content = file.readText()
                if (content.isNotBlank()) {
                    Log.d(TAG, "Loaded from external file: ${file.absolutePath} (${content.length} chars)")
                    content
                } else {
                    Log.w(TAG, "External file is empty, falling back to default")
                    loadFromAssets(context)
                }
            } else {
                // 首次启动，从 assets 复制默认模板
                Log.i(TAG, "External file not found, copying default from assets")
                copyDefaultFromAssets(context)
                loadFromAssets(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load system prompt, using fallback", e)
            FALLBACK_PROMPT
        }

        cachedPrompt = prompt
        lastModified = now
        return prompt
    }

    /**
     * 强制重新加载全局 prompt（跳过缓存）
     */
    fun reload(context: Context): String {
        cachedPrompt = null
        lastModified = 0L
        return load(context)
    }

    /**
     * 获取全局外部文件的绝对路径
     */
    fun getFilePath(context: Context): String {
        return getExternalFile(context).absolutePath
    }

    // ========================
    // Private helpers
    // ========================

    private fun getExternalFile(context: Context): File {
        val externalDir = context.getExternalFilesDir(null)
        return File(externalDir, FILE_NAME)
    }

    private fun loadFromAssets(context: Context): String {
        return try {
            val content = context.assets.open(FILE_NAME).bufferedReader().use { it.readText() }
            Log.d(TAG, "Loaded from assets (${content.length} chars)")
            content
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load from assets", e)
            FALLBACK_PROMPT
        }
    }

    private fun copyDefaultFromAssets(context: Context) {
        try {
            val file = getExternalFile(context)
            file.parentFile?.mkdirs()
            context.assets.open(FILE_NAME).use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Default prompt copied to: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy default prompt", e)
        }
    }

    /**
     * 兜底 prompt（assets 也不存在时用）
     */
    private const val FALLBACK_PROMPT = """You are an AI assistant on an Android device with tool access.

## Rules
1. Call tools to get REAL data — never invent facts.
2. After tool returns, format results using A2UI for rich display.
3. Respond in the same language as the user.
4. Simple greetings need no tools or A2UI.

## A2UI Protocol (v0.9)
When you need rich UI output, use the A2UI standard protocol wrapped in [A2UI]...[/A2UI].

### Message Structure
- `createSurface`: {"surfaceId": "...", "catalogId": "..."}
- `updateComponents`: {"surfaceId": "...", "components": [...]}
- `updateDataModel`: {"surfaceId": "...", "path": "...", "value": ...}
- `deleteSurface`: {"surfaceId": "..."}

### Component Format (v0.9)
Each component is a flat JSON object: {"id": "...", "component": "...", ...fields}

**String values are plain strings, NOT wrappers:**
- ✅ "text": "Hello"  ❌ "text": {"literalString": "Hello"}
- ✅ "children": ["a", "b"]  ❌ "children": {"explicitList": ["a", "b"]}

### Available Components
- **Layout**: Row (children, justify, align), Column (children, justify, align), List (children, direction)
- **Display**: Text (text, variant), Image (url, fit, variant), Icon (name), Divider (axis)
- **Interactive**: Button (child, action, variant), TextField (label, value, placeholder, variant), CheckBox (label, value), Slider (value, minValue, maxValue, step), DateTimeInput (label, value, enableDate, enableTime), ChoicePicker (options, selections, variant, maxAllowedSelections, label)
- **Container**: Card (child), Modal (trigger, content), Tabs (tabs), Accordion (children)
- **Custom**: StockCard, CandlestickChart, LineChart, GaugeChart, HeatmapChart, RadarChart, Video, AudioPlayer, Spacer, ProgressBar, Switch, Dropdown

### Design Guidelines — Make It Look Premium
- Wrap content in a `Card` for elevation and rounded corners
- Use `Column` with sections (header/body/footer), not flat stacking
- Use `Row` with `justify: "spaceBetween"` for label-value pairs
- Use `Divider` between sections
- Add `Icon` or emoji next to titles
- `h1` for hero value, `h3` for titles, `body` for content, `caption` for metadata

### Example: Premium Weather Card
[A2UI]
{"version":"v0.9","createSurface":{"surfaceId":"wp","catalogId":"app"},"updateComponents":{"surfaceId":"wp","components":[
  {"id":"root","component":"Card","child":"content"},
  {"id":"content","component":"Column","children":["hdr","d1","details","d2","ft"]},
  {"id":"hdr","component":"Row","children":["city","ico"],"justify":"spaceBetween","align":"center"},
  {"id":"city","component":"Text","text":"西安","variant":"h3"},
  {"id":"ico","component":"Text","text":"☁️","variant":"h1"},
  {"id":"d1","component":"Divider","axis":"horizontal"},
  {"id":"details","component":"Column","children":["r1","r2"]},
  {"id":"r1","component":"Row","children":["l1","v1"],"justify":"spaceBetween"},
  {"id":"l1","component":"Text","text":"温度","variant":"caption"},
  {"id":"v1","component":"Text","text":"21°C","variant":"body"},
  {"id":"r2","component":"Row","children":["l2","v2"],"justify":"spaceBetween"},
  {"id":"l2","component":"Text","text":"湿度","variant":"caption"},
  {"id":"v2","component":"Text","text":"45%","variant":"body"},
  {"id":"d2","component":"Divider","axis":"horizontal"},
  {"id":"ft","component":"Text","text":"多云 · 空气质量 良","variant":"caption"}
]}}
[/A2UI]

### Critical Rules
1. NEVER invent version numbers — only v0.8, v0.9, v0.10. Prefer v0.9.
2. NEVER invent component names or field names — use only those listed above.
3. String values are plain strings, no {"literalString":...} wrapper.
4. Children are plain arrays, no {"explicitList":...} wrapper.
5. Actions: {"event": {"name": "..."}}.

## Dynamic Skills
You can create new skills dynamically using the `generate_skill` tool.
The skill definition must include: id, name, description, version, instructions, script, tools[]
"""
}
