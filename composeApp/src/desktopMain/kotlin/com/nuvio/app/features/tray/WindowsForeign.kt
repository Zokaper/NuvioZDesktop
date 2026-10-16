package com.nuvio.app.features.tray

import java.lang.invoke.MethodHandle
import java.lang.reflect.Array as JArray

/**
 * The Windows calls the tray and the single-instance hand-off need, through the JDK's foreign-function API **looked up at run time**.
 *
 * ⚠ **Reflection on purpose.** The shipped Windows runtime is JBR 25, where `java.lang.foreign` is final,
 * but CI also compiles this source set on JDK 17 (the macOS release-selection tests), which has no such
 * package - naming its types directly broke Desktop debug build 98. Looked up reflectively it compiles
 * anywhere, works on 22+, and on an older runtime every call simply reports "unavailable". No JNA, and no
 * change to the native player bridge.
 */
internal object WindowsForeign {
    private class Api(
        val linkerClass: Class<*>,
        val linker: Any,
        val arenaGlobal: Any,
        val arenaClass: Class<*>,
        val memoryLayoutClass: Class<*>,
        val memorySegmentClass: Class<*>,
        val functionDescriptorClass: Class<*>,
        val optionClass: Class<*>,
        val address: Any,
        val javaInt: Any,
        val ofIntClass: Class<*>,
    )

    private val api: Api? by lazy {
        runCatching {
            val linkerClass = Class.forName("java.lang.foreign.Linker")
            val arenaClass = Class.forName("java.lang.foreign.Arena")
            val valueLayoutClass = Class.forName("java.lang.foreign.ValueLayout")
            Api(
                linkerClass = linkerClass,
                linker = linkerClass.getMethod("nativeLinker").invoke(null),
                arenaGlobal = arenaClass.getMethod("global").invoke(null),
                arenaClass = arenaClass,
                memoryLayoutClass = Class.forName("java.lang.foreign.MemoryLayout"),
                memorySegmentClass = Class.forName("java.lang.foreign.MemorySegment"),
                functionDescriptorClass = Class.forName("java.lang.foreign.FunctionDescriptor"),
                optionClass = Class.forName("java.lang.foreign.Linker\$Option"),
                address = valueLayoutClass.getField("ADDRESS").get(null),
                javaInt = valueLayoutClass.getField("JAVA_INT").get(null),
                ofIntClass = Class.forName("java.lang.foreign.ValueLayout\$OfInt"),
            )
        }.getOrNull()
    }

    /** A downcall to [function] in [library] returning an int and taking [argLayouts], or null. */
    private fun intFunction(library: String, function: String, argLayouts: (Api) -> List<Any>): MethodHandle? {
        val api = api ?: return null
        val layouts = argLayouts(api)
        return runCatching {
            val lookupClass = Class.forName("java.lang.foreign.SymbolLookup")
            val lookup = lookupClass.getMethod("libraryLookup", String::class.java, api.arenaClass)
                .invoke(null, library, api.arenaGlobal)
            val symbol = (lookupClass.getMethod("find", String::class.java).invoke(lookup, function) as java.util.Optional<*>)
                .orElse(null) ?: return null
            val args = JArray.newInstance(api.memoryLayoutClass, layouts.size)
            layouts.forEachIndexed { index, layout -> JArray.set(args, index, layout) }
            val descriptor = api.functionDescriptorClass
                .getMethod("of", api.memoryLayoutClass, args.javaClass)
                .invoke(null, api.javaInt, args)
            val noOptions = JArray.newInstance(api.optionClass, 0)
            // Through the public interface: the implementation class lives in an unexported package.
            api.linkerClass.getMethod("downcallHandle", api.memorySegmentClass, api.functionDescriptorClass, noOptions.javaClass)
                .invoke(api.linker, symbol, descriptor, noOptions) as MethodHandle
        }.getOrNull()
    }

    /** `GetCurrentProcess()` is the constant pseudo-handle -1; no call needed to make it. */
    private fun currentProcess(): Any? {
        val api = api ?: return null
        return runCatching {
            api.memorySegmentClass.getMethod("ofAddress", Long::class.javaPrimitiveType).invoke(null, -1L)
        }.getOrNull()
    }

    private val emptyWorkingSet: MethodHandle? by lazy { intFunction("kernel32", "K32EmptyWorkingSet") { listOf(it.address) } }
    private val queryNotificationState: MethodHandle? by lazy {
        intFunction("shell32", "SHQueryUserNotificationState") { listOf(it.address) }
    }
    private val allowSetForegroundWindow: MethodHandle? by lazy {
        intFunction("user32", "AllowSetForegroundWindow") { listOf(it.javaInt) }
    }

    /** `K32EmptyWorkingSet(GetCurrentProcess())`; false when unavailable or refused. */
    fun emptyWorkingSet(): Boolean {
        val handle = emptyWorkingSet ?: return false
        val process = currentProcess() ?: return false
        return runCatching { (handle.invokeWithArguments(process) as Int) != 0 }.getOrDefault(false)
    }

    /**
     * `AllowSetForegroundWindow(processId)`: lets that process take the foreground, which only a process
     * that has it (the one the user just launched) can grant. False when refused (a process without the
     * foreground cannot grant it), null when the call cannot be made at all.
     */
    fun allowSetForegroundWindow(processId: Long): Boolean? {
        val handle = allowSetForegroundWindow ?: return null
        return runCatching { (handle.invokeWithArguments(processId.toInt()) as Int) != 0 }.getOrNull()
    }

    /** `SHQueryUserNotificationState`'s answer, or null when it cannot be asked. */
    fun userNotificationState(): Int? {
        val api = api ?: return null
        val handle = queryNotificationState ?: return null
        return runCatching {
            val arena = api.arenaClass.getMethod("ofConfined").invoke(null) as AutoCloseable
            arena.use {
                val out = Class.forName("java.lang.foreign.SegmentAllocator")
                    .getMethod("allocate", api.memoryLayoutClass)
                    .invoke(arena, api.javaInt)
                val hr = handle.invokeWithArguments(out) as Int
                if (hr != 0) return@use null
                api.memorySegmentClass.getMethod("get", api.ofIntClass, Long::class.javaPrimitiveType)
                    .invoke(out, api.javaInt, 0L) as Int
            }
        }.getOrNull()
    }
}
