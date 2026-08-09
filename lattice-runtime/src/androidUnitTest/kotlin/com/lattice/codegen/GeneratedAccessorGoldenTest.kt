package com.lattice.codegen

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Golden test over the `@Model` transform's OUTPUT.
 *
 * ## Why this exists
 *
 * Every dangerous failure mode of the Kotlin 2.2 parameter-argument refactor
 * compiles green:
 *
 *  - `setter.valueParameters.first()` became `setter.parameters.first()`, which
 *    now returns the DISPATCH RECEIVER — so a generated setter stores `this`
 *    instead of `value`.
 *  - An off-by-one in `arguments[i]` silently reorders a runtime call.
 *  - `isInstant()` matches an FQN as a literal string, with a silent
 *    `getString`/`setString` fallback — a moved FQN flips REAL to TEXT.
 *
 * None of those are type errors and none are observable from
 * `nativeTest`, which has no round-trip coverage for collections or
 * `VirtualList` and never invokes the `LatticeList` setter at all.
 *
 * ## What it asserts
 *
 * Not raw bytecode — that drifts across compiler versions for cosmetic reasons
 * and would produce false reds. Instead each accessor is reduced to its
 * *codegen shape*: the ordered sequence of constants loaded, locals read, and
 * methods invoked. That is stable across Kotlin releases but captures exactly
 * the things above:
 *
 *  - `LOAD 0` where `LOAD 1` belongs  -> the dispatch-receiver bug
 *  - a reordered `LDC`/`LOAD` run     -> a bad `arguments[]` index
 *  - `setString` where `setLong` was  -> the datetime fallback
 *
 * ## Regenerating
 *
 * `./gradlew :lattice-runtime:testDebugUnitTest -Dlattice.golden.update=true`
 *
 * Regenerate ONLY from a build you have independently validated. The goldens
 * committed alongside this file were generated at Kotlin 2.0.21 from the
 * released 1.0.0 transform, and are the reference the 2.4 migration must match.
 */
class GeneratedAccessorGoldenTest {

    @Test
    fun `generated accessors match golden codegen shape`() {
        val mismatches = mutableListOf<String>()

        for (fixture in FIXTURES) {
            val actual = shapeOf(fixture)
            val golden = goldenFile(fixture)

            if (updating) {
                golden.parentFile.mkdirs()
                golden.writeText(actual)
                continue
            }

            if (!golden.exists()) {
                mismatches += "${fixture.simpleName}: no golden — run with -Dlattice.golden.update=true"
                continue
            }
            val expected = golden.readText()
            if (expected != actual) {
                mismatches += "${fixture.simpleName}: codegen shape changed\n${diff(expected, actual)}"
            }
        }

        if (updating) fail("Goldens rewritten. Re-run without -Dlattice.golden.update to verify.")
        if (mismatches.isNotEmpty()) fail(mismatches.joinToString("\n\n"))
    }

    /**
     * Guards the guard. If the transform is disabled — `-Xplugin` pointing at a
     * missing jar does exactly that, silently — every accessor degrades to a
     * plain backing-field access and every golden would still "match" a
     * regenerated empty baseline. Assert the delegating path is present.
     */
    @Test
    fun `fixtures are actually transformed`() {
        val shape = shapeOf(GoldenScalars::class.java)
        val calls = shape.lines().count { it.contains("INVOKE") && it.contains("lattice", ignoreCase = true) }
        if (calls == 0) {
            fail(
                "No Lattice runtime calls in GoldenScalars' accessors — the @Model transform did not run. " +
                    "Check that -Xplugin resolves to an existing compiler-plugin jar.",
            )
        }
    }

    private fun goldenFile(fixture: Class<*>) = File(goldenDir, "${fixture.simpleName}.txt")

    /**
     * Reduces a class to the ordered codegen shape of its accessors. Property
     * accessors only — constructors and synthetic bridges carry compiler-version
     * noise without carrying transform signal.
     */
    private fun shapeOf(fixture: Class<*>): String {
        val bytes = fixture.classLoader
            .getResourceAsStream(fixture.name.replace('.', '/') + ".class")
            ?.use { it.readBytes() }
            ?: error("class file not on the test classpath: ${fixture.name}")

        val out = StringBuilder()
        val methods = sortedMapOf<String, List<String>>()

        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (!name.startsWith("get") && !name.startsWith("set")) return null
                    val ops = mutableListOf<String>()
                    methods["$name$descriptor"] = ops
                    return ShapeRecorder(ops)
                }
            },
            ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )

        out.append("# codegen shape: ").append(fixture.name).append('\n')
        for ((signature, ops) in methods) {
            out.append('\n').append(signature).append('\n')
            ops.forEach { out.append("  ").append(it).append('\n') }
        }
        return out.toString()
    }

    /** Records only the opcodes that carry transform signal. */
    private class ShapeRecorder(private val ops: MutableList<String>) : MethodVisitor(Opcodes.ASM9) {

        override fun visitVarInsn(opcode: Int, varIndex: Int) {
            // The dispatch-receiver bug shows up here and nowhere else: a setter
            // that loads slot 0 where it should load slot 1.
            ops += "LOAD $varIndex"
        }

        override fun visitLdcInsn(value: Any?) {
            ops += "CONST ${render(value)}"
        }

        override fun visitIntInsn(opcode: Int, operand: Int) {
            ops += "CONST $operand"
        }

        /**
         * Small integers compile to ICONST_*, which carry their value in the
         * opcode rather than an operand — so without this a compile-time-computed
         * index (the @LatticeEnum fallback, an argument position) would be
         * invisible to the golden and free to change silently.
         */
        override fun visitInsn(opcode: Int) {
            val literal = when (opcode) {
                Opcodes.ICONST_M1 -> "-1"
                Opcodes.ICONST_0, Opcodes.LCONST_0, Opcodes.FCONST_0, Opcodes.DCONST_0 -> "0"
                Opcodes.ICONST_1, Opcodes.LCONST_1, Opcodes.FCONST_1, Opcodes.DCONST_1 -> "1"
                Opcodes.ICONST_2, Opcodes.FCONST_2 -> "2"
                Opcodes.ICONST_3 -> "3"
                Opcodes.ICONST_4 -> "4"
                Opcodes.ICONST_5 -> "5"
                Opcodes.ACONST_NULL -> "null"
                else -> return
            }
            ops += "CONST $literal"
        }

        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) {
            ops += "FIELD $owner.$name:$descriptor"
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            ops += "INVOKE $owner.$name$descriptor"
        }

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            handle: Handle,
            vararg args: Any?,
        ) {
            ops += "INVOKEDYNAMIC $name$descriptor"
        }

        override fun visitTypeInsn(opcode: Int, type: String) {
            ops += "TYPE $type"
        }

        private fun render(value: Any?): String = when (value) {
            is String -> "\"$value\""
            else -> value.toString()
        }
    }

    private fun diff(expected: String, actual: String): String {
        val e = expected.lines()
        val a = actual.lines()
        return (0 until maxOf(e.size, a.size))
            .filter { e.getOrNull(it) != a.getOrNull(it) }
            .take(MAX_DIFF_LINES)
            .joinToString("\n") { "  line ${it + 1}: expected <${e.getOrNull(it)}> but was <${a.getOrNull(it)}>" }
    }

    private companion object {
        const val MAX_DIFF_LINES = 25

        val updating: Boolean get() = System.getProperty("lattice.golden.update") == "true"

        /**
         * Resolved from the module dir so the goldens are editable source, not a
         * read-only classpath resource — regeneration has to write them back.
         */
        val goldenDir: File
            get() = File(System.getProperty("lattice.module.dir") ?: ".", "src/androidUnitTest/golden")

        val FIXTURES: List<Class<*>> = listOf(
            GoldenScalars::class.java,
            GoldenTemporal::class.java,
            GoldenIdentifiers::class.java,
            GoldenBinary::class.java,
            GoldenEmbeds::class.java,
            GoldenLinks::class.java,
            GoldenLinkTarget::class.java,
            GoldenCollections::class.java,
            GoldenEnums::class.java,
        )
    }
}
