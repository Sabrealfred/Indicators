package com.neopal.pet.ui.games

import java.lang.reflect.Method

/**
 * The seam these tests reach the games through, and the one thing about them worth arguing about.
 *
 * Every game in this package is one file: a `@Composable` screen with a frame loop, and beneath
 * it the logic that screen is a view of — a physics integrator, a solver, a reply transformation,
 * a genome read. All of that logic is declared `private` at the top level of its own file, which
 * in Kotlin means file-private: a test in a sibling file cannot see it by name, however much it
 * would like to. The three ways out of that were:
 *
 *  1. Copy the logic into the test. Then the test is about the copy, and the copy stops being the
 *     code the moment anybody edits the game. That is a golden value wearing a disguise.
 *  2. Widen the declarations to `internal`. That edits files this work does not own, and it puts
 *     a hole in a game's encapsulation to suit a test — the tail wagging the dog.
 *  3. Go through the JVM, which does not have file-private. A private top-level function is a
 *     private static method on the file's facade class; a private top-level class is a
 *     package-private class of its own name. Both are reachable with `setAccessible`.
 *
 * This is (3), and the cost is stated plainly: **these lookups are by name, so a rename in a game
 * file breaks them at run time rather than at compile time.** That is deliberate. A rename means
 * the thing being tested moved, and the test should say so loudly rather than quietly testing a
 * copy of what used to be there. What it must never do is fail for a reason that is not about the
 * game, so nothing here depends on anything the Compose compiler plugin generates — see
 * [screenNamed], which matches a screen by name and never by parameter list, because the plugin
 * appends two parameters to every composable and a test that counted them would pass on a
 * machine with no Android SDK and fail in CI.
 */
internal object NpGameReflect {

    /** Primitive classes, spelled this way because `Float::class.javaPrimitiveType` is nullable. */
    val F: Class<*> = java.lang.Float.TYPE
    val I: Class<*> = java.lang.Integer.TYPE
    val B: Class<*> = java.lang.Boolean.TYPE
    val LIST: Class<*> = java.util.List::class.java
    val RANDOM: Class<*> = kotlin.random.Random::class.java

    private const val PKG = "com.neopal.pet.ui.games"

    fun cls(simpleName: String): Class<*> = Class.forName("$PKG.$simpleName")

    /** A private top-level function: a private static method on the file's `…Kt` facade. */
    fun fn(file: String, name: String, vararg params: Class<*>): Method =
        cls(file).getDeclaredMethod(name, *params).apply { isAccessible = true }

    /** A member of a private top-level class. Package-private, so it still needs opening up. */
    fun member(owner: String, name: String, vararg params: Class<*>): Method =
        cls(owner).getDeclaredMethod(name, *params).apply { isAccessible = true }

    /**
     * A no-argument member whose *type* is a Compose value class, such as anything returning a
     * `Color`.
     *
     * These cannot be looked up by their source name. Kotlin compiles a property of an inline
     * class type to a mangled getter — `getColor` becomes `getColor-0d7_KjU`, returning the
     * underlying primitive rather than a `Color` — so [member] finds nothing and throws. The hash
     * is derived from the signature, so it is neither guessable nor stable enough to write down;
     * the name is matched by prefix instead, and the value comes back as whatever primitive the
     * class is inlined to.
     *
     * This is worth spelling out because it is the second time this project has been bitten by
     * the difference between a Compose *signature* and a Compose *compilation*. The first was a
     * missing `@DslMarker` on `DrawScope`, which compiled locally and failed in CI, and the
     * lesson recorded in PLAN.md was that a check which cannot fail the way the real thing fails
     * is not the check you thought you had. The scratchpad harness now declares its stand-in
     * `Color` as a value class for exactly that reason, and it does reproduce this failure.
     */
    fun valueMember(owner: String, name: String): Method {
        val candidates = cls(owner).declaredMethods.filter {
            it.parameterCount == 0 && (it.name == name || it.name.startsWith("$name-"))
        }
        if (candidates.size != 1) {
            throw AssertionError("$owner has ${candidates.size} members called $name")
        }
        return candidates.first().apply { isAccessible = true }
    }

    /** A private top-level `val`: a private static field on the facade. */
    fun constant(file: String, name: String): Any? =
        cls(file).getDeclaredField(name).apply { isAccessible = true }.get(null)

    /**
     * The screen function of a game file, found by name alone.
     *
     * Never by signature: the Compose compiler plugin rewrites every `@Composable` to take a
     * `Composer` and a change mask on the end, so the parameter list here is not the parameter
     * list in the source and is not the same in every build. Name and arity-at-least are the
     * parts that mean something.
     */
    fun screenNamed(file: String, name: String): Method {
        // Filtered to public statics as well as by name, so that a synthetic the plugin happens
        // to emit alongside the screen cannot turn this into an ambiguity.
        val candidates = cls(file).declaredMethods.filter {
            it.name == name &&
                java.lang.reflect.Modifier.isPublic(it.modifiers) &&
                java.lang.reflect.Modifier.isStatic(it.modifiers)
        }
        if (candidates.size != 1) {
            throw AssertionError("$PKG.$file has ${candidates.size} public functions called $name")
        }
        return candidates.first()
    }
}
