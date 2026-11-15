package com.safelog.app.vehicle

import android.util.Log
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * Calls the Pleos SDKs by name. Why reflection:
 *  - the app builds even where the Pleos maven repository is unreachable, and runs on a plain AAOS emulator;
 *  - in Vehicle SDK 2.3.2 some domain getters are invisible to Kotlin (metadata says private, see other teams'
 *    VehicleAccess workarounds), Java reflection ignores that;
 *  - a renamed method only disables that one channel (the engine then ABSTAINS), it never crashes the app.
 */
object Reflect {
    private const val TAG = "SafeLog/Pleos"

    fun cls(name: String): Class<*>? = try { Class.forName(name) } catch (_: Throwable) { null }

    fun methods(target: Any, name: String): List<Method> =
        target.javaClass.methods.filter { it.name == name && !it.name.contains("$") && !Modifier.isStatic(it.modifiers) }

    /** Calls [name] with [args] (matching the parameter count). Returns null if missing or failing. */
    fun call(target: Any?, name: String, vararg args: Any?): Any? {
        target ?: return null
        val m = methods(target, name).firstOrNull { it.parameterCount == args.size } ?: return null.also { Log.d(TAG, "no $name/${args.size} on ${target.javaClass.simpleName}") }
        return try { m.invoke(target, *args) } catch (t: Throwable) {
            Log.w(TAG, "$name failed: ${(t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t}"); null
        }
    }

    /** A Kotlin `(T) -> Unit` callback as the SDK expects it (kotlin.jvm.functions.Function1). */
    fun fn1(block: (Any?) -> Unit): Function1<Any?, Unit> = { x: Any? -> block(x) }

    /** A Kotlin `(A, B) -> Unit` callback (some set/get APIs pass two values). */
    fun fn2(block: (Any?, Any?) -> Unit): Function2<Any?, Any?, Unit> = { a: Any?, b: Any? -> block(a, b) }

    /** An implementation of the SDK listener interface [ifaceName]; every "onXxx" call is forwarded. */
    fun listener(ifaceName: String, onCall: (String, Array<out Any?>) -> Unit): Any? {
        val iface = cls(ifaceName) ?: return null
        return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxy, method, a ->
            when (method.name) {
                "equals" -> proxy === a?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "SafeLogListener(${iface.simpleName})"
                else -> {
                    try { onCall(method.name, a ?: emptyArray()) } catch (t: Throwable) { Log.w(TAG, "listener ${method.name}: $t") }
                    if (method.returnType == java.lang.Boolean.TYPE) false else null
                }
            }
        }
    }

    private val warned = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Logs [msg] the first time only (the poll loops run every second). */
    fun warnOnce(msg: String) { if (warned.add(msg)) Log.w(TAG, msg) }

    /**
     * Calls a get-style SDK function: area parameters are filled with [area] (an area value name, e.g. ROW_1_LEFT),
     * function parameters become onComplete / onFailed callbacks. Returns false if the function does not exist or
     * the area cannot be built (then nothing is called: a null area would only throw inside the SDK).
     */
    fun get(target: Any?, name: String, area: String? = null, onError: ((Any?) -> Unit)? = null, onValue: (Any?) -> Unit): Boolean {
        target ?: return false
        val m = methods(target, name).maxByOrNull { it.parameterCount } ?: return false
        val args = m.parameterTypes.mapIndexed { k, type ->
            when {
                Function1::class.java.isAssignableFrom(type) ->
                    if (k == m.parameterCount - 1 && m.parameterCount >= 2 && m.parameterTypes[k - 1].let { Function1::class.java.isAssignableFrom(it) })
                        fn1 { e -> Log.d(TAG, "$name failed: $e"); onError?.invoke(e) }     // onFailed
                    else fn1(onValue)                                       // onComplete
                Function2::class.java.isAssignableFrom(type) -> fn2 { _, b -> onValue(b) }
                type.simpleName.endsWith("Area") -> area(type, area)
                    ?: return false.also { warnOnce("$name: no ${type.simpleName} for $area (values: ${areaValues(type)})") }
                else -> null
            }
        }
        return try {
            val r = m.invoke(target, *args.toTypedArray())
            // a plain getter (no callback parameters) answers with its return value
            if (args.none { it is Function1<*, *> || it is Function2<*, *, *> }) onValue(r)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "$name: ${(t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t}"); false
        }
    }

    /**
     * The area constructor and its value enum. Vehicle SDK 2.3.2 areas take `vararg values` (official API reference:
     * `SeatArea(vararg values: SeatValue)`, `WindowArea(vararg values: WindowValue)`, same for Door, Mirror,
     * TurnSignal and Wheel), which is one `XValue[]` parameter to reflection, so the chosen value goes into a
     * one-element array (passing the bare enum gave null and every area getter failed with an NPE). A single `XValue`
     * parameter works too.
     */
    private fun areaCtor(type: Class<*>): Pair<java.lang.reflect.Constructor<*>, Class<*>>? {
        for (c in type.constructors) {
            if (c.parameterCount != 1) continue
            val p = c.parameterTypes[0]
            val e = if (p.isArray) p.componentType else p
            if (e != null && e.isEnum) return c to e
        }
        return null
    }

    /**
     * Builds an area object for one value, e.g. WindowArea(WindowValue.FRONT_WINDSHIELD). [wanted] must be one of
     * the value names; an unknown name gives null rather than another area, so a typo can never read the wrong
     * window or seat. null = the driver's side (ROW_1_LEFT, else DRIVER_LEFT, else FRONT_WINDSHIELD, else the first).
     */
    fun area(type: Class<*>, wanted: String?): Any? {
        val (ctor, enumType) = areaCtor(type) ?: return null
        val consts = enumType.enumConstants ?: return null
        val pick = if (wanted != null) consts.firstOrNull { (it as Enum<*>).name == wanted } ?: return null
            else listOf("ROW_1_LEFT", "DRIVER_LEFT", "FRONT_WINDSHIELD").firstNotNullOfOrNull { n -> consts.firstOrNull { (it as Enum<*>).name == n } }
                ?: consts.firstOrNull() ?: return null
        val arg: Any = if (ctor.parameterTypes[0].isArray) java.lang.reflect.Array.newInstance(enumType, 1).also { java.lang.reflect.Array.set(it, 0, pick) } else pick
        return try { ctor.newInstance(arg) } catch (t: Throwable) { Log.w(TAG, "area ${type.simpleName}($wanted): $t"); null }
    }

    /** Every value name an area type accepts (SeatArea -> ROW_1_LEFT, ROW_1_CENTER ...). */
    fun areaValues(type: Class<*>): List<String> = enumNames(areaCtor(type)?.second)

    /** The value names inside an area object (its `values`), e.g. a SeatArea from a capability list. */
    fun areaNames(area: Any?): List<String> = when (val v = prop(area, "values")) {
        is Array<*> -> v.mapNotNull { (it as? Enum<*>)?.name }
        is Iterable<*> -> v.mapNotNull { (it as? Enum<*>)?.name }
        else -> emptyList()
    }

    /** Constant names of an enum type, or of an array-of-enum type (a vararg area constructor's parameter). */
    fun enumNames(type: Class<*>?): List<String> {
        val t = if (type?.isArray == true) type.componentType else type
        return t?.enumConstants?.map { (it as Enum<*>).name } ?: emptyList()
    }

    /** Reads a property of an SDK data object by getter name ("distance" -> getDistance()). */
    fun prop(obj: Any?, vararg names: String): Any? {
        obj ?: return null
        for (n in names) {
            val m = obj.javaClass.methods.firstOrNull { it.parameterCount == 0 && (it.name.equals("get$n", true) || it.name.equals(n, true) || it.name.equals("is$n", true)) }
            if (m != null) return try { m.invoke(obj) } catch (_: Throwable) { null }
        }
        return null
    }

    fun num(x: Any?): Double? = (x as? Number)?.toDouble()
    fun name(x: Any?): String? = (x as? Enum<*>)?.name ?: x?.toString()
}
