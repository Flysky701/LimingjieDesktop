package com.landosol.toolbox.desktop.device

import kotlinx.serialization.json.*
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

object DeviceParsers {
    fun devices(text: String): List<DeviceDescriptor> = text.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 2 || parts[0] == "List" || parts[0].startsWith('*')) null
        else if (parts[1] in setOf("device", "offline", "unauthorized", "recovery", "sideload")) {
            val model = parts.firstOrNull { it.startsWith("model:") }?.substringAfter(':')?.replace('_', ' ')
            DeviceDescriptor(parts[0], model ?: parts[0], parts[1])
        } else null
    }.toList()

    fun foreground(text: String): String? {
        fun packageFrom(line: String): String? = Regex("(?:^|[\\s{])([A-Za-z][A-Za-z0-9_.]*)/[A-Za-z0-9_.$]+").find(line)?.groupValues?.get(1)
        val current = text.lineSequence().firstOrNull { "mCurrentFocus=" in it }
        if (current != null && !Regex("mCurrentFocus=\\s*null").containsMatchIn(current)) return packageFrom(current)
        return text.lineSequence().firstOrNull { "mFocusedApp=" in it }?.let(::packageFrom)
    }
    fun screenSize(text: String): Pair<Int, Int> {
        val override = Regex("Override size:\\s*(\\d+)x(\\d+)").find(text)
        val match = override ?: Regex("Physical size:\\s*(\\d+)x(\\d+)").find(text) ?: throw DeviceOperationException("无法读取模拟器分辨率")
        val width = match.groupValues[1].toInt(); val height = match.groupValues[2].toInt()
        if (width <= 0 || height <= 0) throw DeviceOperationException("模拟器分辨率无效")
        return width to height
    }
    fun awake(text: String): Boolean = Regex("mWakefulness=Awake\\b").containsMatchIn(text)
    fun launchComponent(text: String): String? = text.lineSequence().map(String::trim)
        .lastOrNull { Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+").matches(it) }
    fun launchSucceeded(text: String): Boolean = !Regex("Error:|Exception", RegexOption.IGNORE_CASE).containsMatchIn(text)
    fun baseApk(text: String): String? {
        val paths = text.lineSequence().map(String::trim).filter { it.startsWith("package:") }.map { it.removePrefix("package:") }.toList()
        return paths.firstOrNull { it.endsWith("/base.apk") } ?: paths.singleOrNull()
    }
    fun packageVersion(text: String): Triple<String, Long, String> {
        val name = Regex("\\bversionName=([^\\r\\n]+)").find(text)?.groupValues?.get(1)?.trim() ?: throw DeviceOperationException("无法读取游戏版本")
        val code = Regex("\\bversionCode=(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: throw DeviceOperationException("无法读取游戏版本号")
        val updated = Regex("\\blastUpdateTime=([^\\r\\n]+)").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        return Triple(name, code, updated)
    }
    fun mumuPorts(text: String): List<Int> {
        val document = try { Json.parseToJsonElement(text) } catch (_: kotlinx.serialization.SerializationException) { return emptyList() }
        val ports = mutableSetOf<Int>()
        fun visit(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    if (key in setOf("adb_port", "adbPort")) (value as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..65535 }?.let(ports::add)
                    if (value is JsonObject || value is JsonArray) visit(value)
                }
                is JsonArray -> element.forEach(::visit)
                else -> Unit
            }
        }
        visit(document); return ports.toList()
    }
    fun ldplayerIndices(text: String): List<Int> = text.lineSequence().mapNotNull { line ->
        val fields = line.split(',')
        if (fields.size >= 7 && fields[4].trim() == "1") fields[0].trim().toIntOrNull() else null
    }.toList()

    /** Bounds mirror upstream: at most 600 nodes in each root/window and depth 40. */
    fun uiTree(xml: String): List<UiNode> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false; isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder()
        builder.setErrorHandler(object : org.xml.sax.helpers.DefaultHandler() {
            override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
        })
        val root = builder.parse(InputSource(StringReader(xml))).documentElement
        fun node(element: Element, depth: Int, remaining: IntArray): UiNode {
            remaining[0]--
            val bounds = Regex("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").matchEntire(element.getAttribute("bounds"))
                ?: throw DeviceOperationException("渠道界面节点坐标无效")
            val children = mutableListOf<UiNode>()
            if (depth < 40) for (index in 0 until element.childNodes.length) {
                if (remaining[0] <= 0) break
                val child = element.childNodes.item(index)
                if (child is Element && child.tagName == "node") children += node(child, depth + 1, remaining)
            }
            val values = bounds.groupValues.drop(1).map(String::toInt)
            return UiNode(element.getAttribute("package"), element.getAttribute("text").takeIf(String::isNotEmpty), element.getAttribute("content-desc").takeIf(String::isNotEmpty),
                element.getAttribute("clickable") == "true", UiBounds(values[0], values[1], values[2], values[3]), children)
        }
        val roots = mutableListOf<UiNode>()
        for (index in 0 until root.childNodes.length) {
            val child = root.childNodes.item(index)
            if (child is Element && child.tagName == "node") roots += node(child, 0, intArrayOf(600))
        }
        return roots
    }
}
