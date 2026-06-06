/*
 * Copyright (C) 2007 Esmertec AG.
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.mms

/**
 * A generic exception that is thrown by the Mms client.
 *
 * First-party Kotlin port of the vendored `MmsException`; behaviour preserved
 * 1:1, including the four constructors and the `serialVersionUID`. `open` so the
 * (still-Java) codec can both subclass it (`InvalidHeaderValueException`) and
 * throw/catch it.
 */
open class MmsException : Exception {
    /**
     * Creates a new MmsException.
     */
    constructor() : super()

    /**
     * Creates a new MmsException with the specified detail message.
     *
     * @param message the detail message.
     */
    constructor(message: String?) : super(message)

    /**
     * Creates a new MmsException with the specified cause.
     *
     * @param cause the cause.
     */
    constructor(cause: Throwable?) : super(cause)

    /**
     * Creates a new MmsException with the specified detail message and cause.
     *
     * @param message the detail message.
     * @param cause the cause.
     */
    constructor(message: String?, cause: Throwable?) : super(message, cause)

    companion object {
        private const val serialVersionUID = -7323249827281485390L
    }
}
