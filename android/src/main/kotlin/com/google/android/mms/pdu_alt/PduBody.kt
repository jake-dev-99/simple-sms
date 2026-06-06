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

package com.google.android.mms.pdu_alt

import java.nio.charset.Charset
import java.util.Vector

/**
 * The body of an MMS PDU: an ordered list of [PduPart]s plus four lookup maps
 * (by content-id / content-location / name / filename). First-party Kotlin port
 * of the vendored `PduBody`; logic preserved 1:1.
 *
 * Consumed by the (still-Java) `pdu_alt` codec (`PduComposer`, `PduParser`,
 * `PduPersister`, `MultimediaMessagePdu`, `RetrieveConf`, `SendReq`) and by
 * first-party Kotlin (`Transaction`, `SmilPresentationBuilder`). `getPartsNum`
 * is a `val partsNum` property so it compiles to the same `getPartsNum()` for
 * Java callers and reads as `.partsNum` for the Kotlin consumers; the rest stay
 * methods.
 */
class PduBody {
    private val mParts: Vector<PduPart> = Vector()

    private val mPartMapByContentId: MutableMap<String, PduPart> = HashMap()
    private val mPartMapByContentLocation: MutableMap<String, PduPart> = HashMap()
    private val mPartMapByName: MutableMap<String, PduPart> = HashMap()
    private val mPartMapByFileName: MutableMap<String, PduPart> = HashMap()

    private fun putPartToMaps(part: PduPart) {
        // Put part to mPartMapByContentId.
        val contentId = part.contentId
        if (null != contentId) {
            mPartMapByContentId[String(contentId, Charset.defaultCharset())] = part
        }

        // Put part to mPartMapByContentLocation.
        val contentLocation = part.contentLocation
        if (null != contentLocation) {
            val clc = String(contentLocation, Charset.defaultCharset())
            mPartMapByContentLocation[clc] = part
        }

        // Put part to mPartMapByName.
        val name = part.name
        if (null != name) {
            val clc = String(name, Charset.defaultCharset())
            mPartMapByName[clc] = part
        }

        // Put part to mPartMapByFileName.
        val fileName = part.filename
        if (null != fileName) {
            val clc = String(fileName, Charset.defaultCharset())
            mPartMapByFileName[clc] = part
        }
    }

    /**
     * Appends the specified part to the end of this body.
     *
     * @param part part to be appended
     * @return true when success, false when fail
     * @throws NullPointerException when part is null
     */
    fun addPart(part: PduPart?): Boolean {
        if (null == part) {
            throw NullPointerException()
        }

        putPartToMaps(part)
        return mParts.add(part)
    }

    /**
     * Inserts the specified part at the specified position.
     *
     * @param index index at which the specified part is to be inserted
     * @param part part to be inserted
     * @throws NullPointerException when part is null
     */
    fun addPart(index: Int, part: PduPart?) {
        if (null == part) {
            throw NullPointerException()
        }

        putPartToMaps(part)
        mParts.add(index, part)
    }

    /**
     * Removes the part at the specified position.
     *
     * @param index index of the part to return
     * @return part at the specified index
     */
    fun removePart(index: Int): PduPart {
        return mParts.removeAt(index)
    }

    /**
     * Remove all of the parts.
     */
    fun removeAll() {
        mParts.clear()
    }

    /**
     * Get the part at the specified position.
     *
     * @param index index of the part to return
     * @return part at the specified index
     */
    fun getPart(index: Int): PduPart {
        return mParts[index]
    }

    /**
     * Get the index of the specified part.
     *
     * @param part the part object
     * @return index the index of the first occurrence of the part in this body
     */
    fun getPartIndex(part: PduPart): Int {
        return mParts.indexOf(part)
    }

    /**
     * The number of parts.
     */
    val partsNum: Int
        get() = mParts.size

    /**
     * Get pdu part by content id.
     *
     * @param cid the value of content id.
     * @return the pdu part.
     */
    fun getPartByContentId(cid: String): PduPart? {
        return mPartMapByContentId[cid]
    }

    /**
     * Get pdu part by Content-Location. Content-Location of part is
     * the same as filename and name(param of content-type).
     *
     * @param contentLocation the value of filename.
     * @return the pdu part.
     */
    fun getPartByContentLocation(contentLocation: String): PduPart? {
        return mPartMapByContentLocation[contentLocation]
    }

    /**
     * Get pdu part by name.
     *
     * @param name the value of filename.
     * @return the pdu part.
     */
    fun getPartByName(name: String): PduPart? {
        return mPartMapByName[name]
    }

    /**
     * Get pdu part by filename.
     *
     * @param filename the value of filename.
     * @return the pdu part.
     */
    fun getPartByFileName(filename: String): PduPart? {
        return mPartMapByFileName[filename]
    }
}
