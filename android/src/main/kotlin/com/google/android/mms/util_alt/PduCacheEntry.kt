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

package com.google.android.mms.util_alt

import com.google.android.mms.pdu_alt.GenericPdu

/**
 * First-party Kotlin port of the vendored `PduCacheEntry` (Phase 5 · UNFY-134) —
 * the immutable value held in `PduCache`: a [GenericPdu] plus its message-box
 * and thread id. Behaviour-faithful 1:1: three read-only fields with the
 * vendored getter shapes.
 *
 * The getters are `val` properties (compiling to the Java `getPdu()` /
 * `getMessageBox()` / `getThreadId()` the still-Java `PduCache` calls), and
 * [pdu] is typed nullable to mirror the vendored `GenericPdu` reference field.
 * It references `GenericPdu` only as a type, so it resolves whether `GenericPdu`
 * is the current Java class or the in-flight Kotlin port.
 */
class PduCacheEntry(
    val pdu: GenericPdu?,
    val messageBox: Int,
    val threadId: Long,
)
