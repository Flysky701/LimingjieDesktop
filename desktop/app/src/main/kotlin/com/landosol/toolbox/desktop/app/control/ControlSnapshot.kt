package com.landosol.toolbox.desktop.app.control

import com.landosol.toolbox.desktop.platform.AppConfig
import com.landosol.toolbox.desktop.platform.DesktopGoal
import com.landosol.toolbox.desktop.platform.DesktopPreferences
import com.landosol.toolbox.desktop.platform.DesktopSettings
import com.landosol.toolbox.desktop.shared.DeviceIdentitySnapshot
import com.landosol.toolbox.labyrinth.LabyrinthRerollSettings
import com.landosol.toolbox.labyrinth.LabyrinthStrategySettings
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.*
import kotlin.reflect.*
import kotlin.reflect.full.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Wire schema v1: a full JSON snapshot, not JVM object serialization.
 *
 * Model sections use existing Kotlin data-class constructor field names, enums as names,
 * and sealed subclasses as {_type: simpleName, ...constructor fields}. ControlModelCodec
 * (in this file) will provide the shared data-only codec to preserve complete upstream forms.
 *
 * console:RunConsoleState (including task, entry, batch, halt, overlay, device, busy,starting,stopping)
 * settings:DesktopSettings; preferences:DesktopPreferences; accounts:AccountUiState (no passwords)
 * strategy:LabyrinthStrategySettings; catalog:[LabyrinthRoleRatingItem]
 * resolution:VisualResolutionStatus; ocr:OcrEngineStatus
 * credentials:{accountIdString:Boolean}; identity:DeviceIdentitySnapshot?; gameClient:GameClientResolution
 * database:String; foreground:String?; connectionActivity:String?; connectionMessage:String?
 * devices:[DeviceDescriptor]; history:[DesktopRunRecord]; logs:[UserLogLine] (compact tail, last 50)
 * keyLogs:[UserLogLine] (all retained KEY, WARNING and ERROR entries in chronological order)
 * historyTotal:Int; historyRevision:Long; logTotal:Int; logRevision:Long
 * dataDirectory:String; debugUrl:String?; complete lists and crash names are requested on demand
 * preview:{jpeg:String(base64),timestampMillis:Long}?; only for a selected worker with an expanded, visible preview
 * captcha:{ticketId:String,url:String,accountAlias:String,error:String?}?
 * notices:[String]; title:String optional binding display. Shell adds instance identity/connection status.
 *
 * Important: worker owns captchaHost.open/close and callback; UI does not install DesktopBindings.
 * worker excludes account editor passwords from periodic snapshots; account.save carries a transient
 * password only on its originating worker's authenticated control channel.
 */
data class ControlSnapshot(val document:JsonObject) {
    fun section(name:String):JsonObject = document[name] as? JsonObject ?: JsonObject(emptyMap())
    fun string(name:String):String? = (document[name] as? JsonPrimitive)?.contentOrNull
    val busy get() = (section("console")["busy"] as? JsonPrimitive)?.booleanOrNull ?: false
    val captcha get() = document["captcha"] as? JsonObject
    val title get() = string("title")
    companion object { const val VERSION=1 }
}

fun controlArgs(vararg values:Pair<String,JsonElement>):JsonObject=JsonObject(mapOf(*values))

/** JSON data codec shared by worker adapter and UI. No class names from the wire are loaded.
 * Decode always starts from a compile-time expected model; _type only selects its sealed children.
 * Only constructor properties are encoded (no callbacks, computed properties or Graph objects).
 */
object ControlModelCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val wireSerializers = mapOf<KClass<*>, KSerializer<*>>(
        AppConfig::class to AppConfig.serializer(),
        DesktopSettings::class to DesktopSettings.serializer(),
        DesktopPreferences::class to DesktopPreferences.serializer(),
        DesktopGoal::class to DesktopGoal.serializer(),
        LabyrinthStrategySettings::class to LabyrinthStrategySettings.serializer(),
        LabyrinthRerollSettings::class to LabyrinthRerollSettings.serializer(),
        DeviceIdentitySnapshot::class to DeviceIdentitySnapshot.serializer(),
    )

    // V1 tags every model object, including models inside lists and map values.
    // Map keys are user data and may themselves be named "_type".
    private fun SerialDescriptor.tagModels(value: JsonElement): JsonElement = when {
        value is JsonPrimitive -> value
        kind == StructureKind.CLASS -> buildJsonObject {
            put("_type", serialName.substringAfterLast('.'))
            for ((name, field) in value.jsonObject) {
                put(name, getElementDescriptor(getElementIndex(name)).tagModels(field))
            }
        }
        kind == StructureKind.LIST -> JsonArray(value.jsonArray.map { getElementDescriptor(0).tagModels(it) })
        kind == StructureKind.MAP -> JsonObject(value.jsonObject.mapValues { getElementDescriptor(1).tagModels(it.value) })
        else -> value
    }

    private val properties=ConcurrentHashMap<KClass<*>,Map<String,KProperty1<Any,*>>>()
    @Suppress("UNCHECKED_CAST") private fun properties(type:KClass<*>)=properties.getOrPut(type){type.memberProperties.associateBy{it.name} as Map<String,KProperty1<Any,*>>}

    private fun scalarMapKey(key: Any?): String? = when (key) {
        is String -> key
        is Char -> key.toString()
        is Boolean -> key.toString()
        is Number -> key.toString()
        is Enum<*> -> key.name
        else -> null
    }

    fun encode(value:Any?):JsonElement=when(value) {
        null->JsonNull
        is JsonElement->value
        is String->JsonPrimitive(value)
        is Char->JsonPrimitive(value.toString())
        is Boolean->JsonPrimitive(value)
        is Double->if(value.isFinite())JsonPrimitive(value) else JsonPrimitive(value.toString())
        is Float->if(value.isFinite())JsonPrimitive(value) else JsonPrimitive(value.toString())
        is Number->JsonPrimitive(value)
        is Enum<*>->JsonPrimitive(value.name)
        is Map<*, *> -> {
            if (value.isEmpty() || value.keys.all { scalarMapKey(it) != null }) {
                JsonObject(value.entries.associate { (key, item) -> scalarMapKey(key)!! to encode(item) })
            } else {
                JsonArray(value.entries.map { (key, item) ->
                    buildJsonObject { put("_k", encode(key)); put("_v", encode(item)) }
                })
            }
        }
        is Iterable<*>->JsonArray(value.map(::encode))
        is IntArray->JsonArray(value.map(::JsonPrimitive))
        is LongArray->JsonArray(value.map(::JsonPrimitive))
        is FloatArray->JsonArray(value.map(::encode))
        is DoubleArray->JsonArray(value.map(::encode))
        is ByteArray->JsonArray(value.map{JsonPrimitive(it.toInt())})
        is Array<*>->JsonArray(value.map(::encode))
        else -> encodeModel(value)
    }

    private fun encodeModel(value: Any): JsonElement {
        val type = value::class
        // Each entry pairs a concrete model with its own generated serializer.
        @Suppress("UNCHECKED_CAST")
        val serializer = wireSerializers[type] as KSerializer<Any>?
        if (serializer != null) {
            return serializer.descriptor.tagModels(json.encodeToJsonElement(serializer, value))
        }
        require(type.qualifiedName.orEmpty().startsWith("com.landosol.toolbox.")) { "Unsupported control model ${type.qualifiedName}" }
        require(type.isData || type.isValue || type.objectInstance != null) { "Control model must contain data only: ${type.simpleName}" }
        val props = properties(type)
        return buildJsonObject {
            put("_type", type.simpleName)
            type.primaryConstructor?.parameters?.forEach { parameter ->
                val name = checkNotNull(parameter.name)
                put(name, encode(checkNotNull(props[name]) { "Missing model property $name" }.get(value)))
            }
        }
    }

    inline fun <reified T> decode(value:JsonElement):T=decode(value,typeOf<T>()) as T
    fun decode(value:JsonElement,type:KType):Any? {
        if(value is JsonNull){require(type.isMarkedNullable){"Unexpected null for $type"};return null}
        val klass=type.classifier as? KClass<*> ?: error("Unsupported control type $type")
        wireSerializers[klass]?.let { serializer ->
            try {
                return json.decodeFromJsonElement(serializer, value)
            } catch (_: SerializationException) {
                // Older inputs can use [{_k, _v}] even for scalar-keyed maps.
                // The v1 decoder below also preserves its existing validation errors.
            }
        }
        val primitive=value as? JsonPrimitive
        when(klass) {
            JsonObject::class->return value.jsonObject
            JsonElement::class->return value
            String::class->return checkNotNull(primitive).content
            Char::class->return checkNotNull(primitive).content.single()
            Boolean::class->return checkNotNull(primitive).boolean
            Int::class->return checkNotNull(primitive).int
            Long::class->return checkNotNull(primitive).long
            Float::class->return checkNotNull(primitive).float
            Double::class->return checkNotNull(primitive).double
            Short::class->return checkNotNull(primitive).int.toShort()
            Byte::class->return checkNotNull(primitive).int.toByte()
            IntArray::class->return value.jsonArray.map{it.jsonPrimitive.int}.toIntArray()
            LongArray::class->return value.jsonArray.map{it.jsonPrimitive.long}.toLongArray()
            FloatArray::class->return value.jsonArray.map{it.jsonPrimitive.float}.toFloatArray()
            DoubleArray::class->return value.jsonArray.map{it.jsonPrimitive.double}.toDoubleArray()
            ByteArray::class->return value.jsonArray.map{it.jsonPrimitive.int.toByte()}.toByteArray()
            List::class,Collection::class,Iterable::class->return value.jsonArray.map{decode(it,checkNotNull(type.arguments[0].type))}
            Set::class->return value.jsonArray.map{decode(it,checkNotNull(type.arguments[0].type))}.toSet()
            Map::class -> {
                val keyType = checkNotNull(type.arguments[0].type)
                val valueType = checkNotNull(type.arguments[1].type)
                return when (value) {
                    is JsonArray -> value.associate { entry ->
                        val pair = entry.jsonObject
                        decode(pair.getValue("_k"), keyType) to decode(pair.getValue("_v"), valueType)
                    }
                    else -> value.jsonObject.entries.associate { (key, item) ->
                        decode(JsonPrimitive(key), keyType) to decode(item, valueType)
                    }
                }
            }
        }
        if(klass.java.isEnum)return klass.java.enumConstants.firstOrNull{(it as Enum<*>).name==primitive?.content} ?: error("Unknown ${klass.simpleName}")
        val obj=value.jsonObject
        val actual=if(klass.isSealed) sealedType(klass,obj["_type"]?.jsonPrimitive?.content) else klass
        actual.objectInstance?.let{return it}
        require(actual.qualifiedName.orEmpty().startsWith("com.landosol.toolbox.")&&(actual.isData||actual.isValue)){"Unsupported control model ${actual.qualifiedName}"}
        val constructor=checkNotNull(actual.primaryConstructor)
        val args=mutableMapOf<KParameter,Any?>()
        constructor.parameters.forEach{parameter->
            val name=checkNotNull(parameter.name)
            val field=obj[name]
            when {field!=null->args[parameter]=decode(field,parameter.type)
                parameter.isOptional->Unit
                parameter.type.isMarkedNullable->args[parameter]=null
                else->error("Missing $name in ${actual.simpleName}")}
        }
        return constructor.callBy(args)
    }
    private fun sealedType(base:KClass<*>,name:String?):KClass<*> {
        fun children(type:KClass<*>):List<KClass<*>> = type.sealedSubclasses.flatMap{listOf(it)+if(it.isSealed)children(it) else emptyList()}
        return children(base).firstOrNull{it.simpleName==name} ?: error("Unknown ${base.simpleName} variant $name")
    }
}
