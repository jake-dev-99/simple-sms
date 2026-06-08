/*
 * Copyright (C) 2015 Jacob Klinker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mms.service_alt

import android.content.Context
import dalvik.system.DexFile
import java.io.File

/**
 * First-party Kotlin port of the vendored `SystemPropertiesProxy` (Phase 5).
 * Behaviour-faithful 1:1: a reflective bridge to the hidden
 * `android.os.SystemProperties`. Ported as a Kotlin `object` with `@JvmStatic`
 * methods (the vendored class had a private ctor and only static methods); the
 * lone live consumer is `DownloadManager.isRoaming`, which calls
 * `get(context, key, null)`.
 *
 * **Faithful nullability detail:** the vendored code does `new String(def)` /
 * `new String(val)` before the reflective invoke. When `def`/`val` is null that
 * line throws `NullPointerException`, which the broad `catch` turns into "return
 * the default" — so `get(context, key, null)` returns **null** without ever
 * invoking `SystemProperties.get`. That null path is load-bearing (it's exactly
 * what `isRoaming` relies on), so it's preserved here via `!!` on the nullable
 * `def`/`value` arguments (same NPE, same catch, same null result). The
 * redundant `new String(key)` copies on the always-non-null `key` are collapsed
 * to `key` (behaviour-invisible: reflection compares string content).
 *
 * The boxed `Integer`/`Long`/`Boolean` returns of the (currently unused)
 * numeric/boolean getters are represented as Kotlin primitives — behaviour-
 * neutral, the values are never null.
 */
object SystemPropertiesProxy {

    /**
     * Get the value for the given key.
     *
     * @return an empty string if the key isn't found
     * @throws IllegalArgumentException if the key exceeds 32 characters
     */
    @JvmStatic
    @Throws(IllegalArgumentException::class)
    fun get(context: Context, key: String): String? {
        var ret: String? = ""

        try {
            val cl = context.classLoader
            val systemProperties = cl.loadClass("android.os.SystemProperties")

            // Parameters Types
            val paramTypes = arrayOf<Class<*>>(String::class.java)

            val get = systemProperties.getMethod("get", *paramTypes)

            // Parameters
            val params = arrayOf<Any>(key)

            // `as String?` mirrors the vendored nullable `(String)` cast (a null
            // invoke result returns null, rather than being coerced).
            ret = get.invoke(systemProperties, *params) as String?
        } catch (iAE: IllegalArgumentException) {
            throw iAE
        } catch (e: Exception) {
            ret = ""
            // TODO
        }

        return ret
    }

    /**
     * Get the value for the given key.
     *
     * @return if the key isn't found, return def if it isn't null, or an empty string otherwise
     * @throws IllegalArgumentException if the key exceeds 32 characters
     */
    @JvmStatic
    @Throws(IllegalArgumentException::class)
    fun get(context: Context, key: String, def: String?): String? {
        var ret = def

        try {
            val cl = context.classLoader
            val systemProperties = cl.loadClass("android.os.SystemProperties")

            // Parameters Types
            val paramTypes = arrayOf<Class<*>>(String::class.java, String::class.java)

            val get = systemProperties.getMethod("get", *paramTypes)

            // Parameters (def!! mirrors the vendored `new String(def)` — NPE on a null def)
            val params = arrayOf<Any>(key, def!!)

            // `as String?` mirrors the vendored nullable `(String)` cast.
            ret = get.invoke(systemProperties, *params) as String?
        } catch (iAE: IllegalArgumentException) {
            throw iAE
        } catch (e: Exception) {
            ret = def
            // TODO
        }

        return ret
    }

    /**
     * Get the value for the given key, and return as an integer.
     *
     * @param key the key to lookup
     * @param def a default value to return
     * @return the key parsed as an integer, or def if the key isn't found or
     * cannot be parsed
     * @throws IllegalArgumentException if the key exceeds 32 characters
     */
    @JvmStatic
    @Throws(IllegalArgumentException::class)
    fun getInt(context: Context, key: String, def: Int): Int {
        var ret = def

        try {
            val cl = context.classLoader
            val systemProperties = cl.loadClass("android.os.SystemProperties")

            // Parameters Types
            val paramTypes = arrayOf<Class<*>>(String::class.java, Int::class.javaPrimitiveType!!)

            val getInt = systemProperties.getMethod("getInt", *paramTypes)

            // Parameters
            val params = arrayOf<Any>(key, def)

            ret = getInt.invoke(systemProperties, *params) as Int
        } catch (iAE: IllegalArgumentException) {
            throw iAE
        } catch (e: Exception) {
            ret = def
            // TODO
        }

        return ret
    }

    /**
     * Get the value for the given key, and return as a long.
     *
     * @param key the key to lookup
     * @param def a default value to return
     * @return the key parsed as a long, or def if the key isn't found or
     * cannot be parsed
     * @throws IllegalArgumentException if the key exceeds 32 characters
     */
    @JvmStatic
    @Throws(IllegalArgumentException::class)
    fun getLong(context: Context, key: String, def: Long): Long {
        var ret = def

        try {
            val cl = context.classLoader
            val systemProperties = cl.loadClass("android.os.SystemProperties")

            // Parameters Types
            val paramTypes = arrayOf<Class<*>>(String::class.java, Long::class.javaPrimitiveType!!)

            val getLong = systemProperties.getMethod("getLong", *paramTypes)

            // Parameters
            val params = arrayOf<Any>(key, def)

            ret = getLong.invoke(systemProperties, *params) as Long
        } catch (iAE: IllegalArgumentException) {
            throw iAE
        } catch (e: Exception) {
            ret = def
            // TODO
        }

        return ret
    }

    /**
     * Get the value for the given key, returned as a boolean.
     * Values 'n', 'no', '0', 'false' or 'off' are considered false.
     * Values 'y', 'yes', '1', 'true' or 'on' are considered true.
     * (case insensitive).
     * If the key does not exist, or has any other value, then the default
     * result is returned.
     *
     * @param key the key to lookup
     * @param def a default value to return
     * @return the key parsed as a boolean, or def if the key isn't found or is
     * not able to be parsed as a boolean.
     * @throws IllegalArgumentException if the key exceeds 32 characters
     */
    @JvmStatic
    @Throws(IllegalArgumentException::class)
    fun getBoolean(context: Context, key: String, def: Boolean): Boolean {
        var ret = def

        try {
            val cl = context.classLoader
            val systemProperties = cl.loadClass("android.os.SystemProperties")

            // Parameters Types
            val paramTypes = arrayOf<Class<*>>(String::class.java, Boolean::class.javaPrimitiveType!!)

            val getBoolean = systemProperties.getMethod("getBoolean", *paramTypes)

            // Parameters
            val params = arrayOf<Any>(key, def)

            ret = getBoolean.invoke(systemProperties, *params) as Boolean
        } catch (iAE: IllegalArgumentException) {
            throw iAE
        } catch (e: Exception) {
            ret = def
            // TODO
        }

        return ret
    }

    /**
     * Set the value for the given key.
     *
     * @throws IllegalArgumentException if the key exceeds 32 characters
     * @throws IllegalArgumentException if the value exceeds 92 characters
     */
    @JvmStatic
    @Throws(IllegalArgumentException::class)
    fun set(context: Context, key: String, value: String?) {
        try {
            @Suppress("UNUSED_VARIABLE", "DEPRECATION")
            val df = DexFile(File("/system/app/Settings.apk"))
            @Suppress("UNUSED_VARIABLE")
            val cl = context.classLoader
            val systemProperties = Class.forName("android.os.SystemProperties")

            // Parameters Types
            val paramTypes = arrayOf<Class<*>>(String::class.java, String::class.java)

            val set = systemProperties.getMethod("set", *paramTypes)

            // Parameters (value!! mirrors the vendored `new String(val)` — NPE on a null value)
            val params = arrayOf<Any>(key, value!!)

            set.invoke(systemProperties, *params)
        } catch (iAE: IllegalArgumentException) {
            throw iAE
        } catch (e: Exception) {
            // TODO
        }
    }
}
