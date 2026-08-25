/*
 * Copyright (C) 2021 Square, Inc.
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
package app.cash.zipline.loader

import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.IOException

data class ZiplineFile(
  val ziplineVersion: Int,
  val jsBytecode: ByteString,
) {
  fun writeTo(sink: BufferedSink) {
    sink.write(MAGIC_PREFIX)
    sink.writeInt(ziplineVersion)
    sink.writeInt(SECTION_HEADER_JS_BYTECODE)
    sink.writeInt(jsBytecode.size)
    sink.write(jsBytecode)
  }

  fun toByteString(): ByteString {
    val buffer = Buffer()
    writeTo(buffer)
    return buffer.readByteString()
  }

  companion object {
    /**
     * Reads from a bufferedSource to return a ZiplineFile.
     * This throws an IOException if the content is not a supported ZiplineFile.
     */
    fun read(source: BufferedSource): ZiplineFile {
      var jsBytecode: ByteString? = null
      if (source.readByteString(8) != MAGIC_PREFIX) {
        throw IOException("not a zipline file")
      }
      val ziplineVersion = source.readInt()
      if (ziplineVersion != CURRENT_ZIPLINE_VERSION) {
        throw IOException(
          "unsupported version [version=$ziplineVersion][currentVersion=$CURRENT_ZIPLINE_VERSION]",
        )
      }
      while (!source.exhausted()) {
        val sectionHeader = source.readInt()
        val sectionLength = source.readInt()
        jsBytecode = source.readSection(jsBytecode, sectionHeader, sectionLength)
      }

      return ZiplineFile(
        ziplineVersion = ziplineVersion,
        jsBytecode = jsBytecode ?: throw IOException("JS bytecode section missing"),
      )
    }

    private fun BufferedSource.readSection(
      jsBytecode: ByteString? = null,
      sectionHeader: Int,
      sectionLength: Int,
    ): ByteString? = when (sectionHeader) {
      SECTION_HEADER_JS_BYTECODE -> {
        if (jsBytecode != null) {
          throw IOException("multiple JS bytecode sections")
        }
        readByteString(sectionLength.toLong())
      }

      else -> {
        // Ignore unexpected section.
        skip(sectionLength.toLong())
        jsBytecode
      }
    }

    fun ByteString.toZiplineFile() = read(Buffer().write(this))

    /** True if [this] starts with the zipline container magic bytes. */
    fun ByteString.isZiplineFile(): Boolean = startsWith(MAGIC_PREFIX)
  }
}

private val MAGIC_PREFIX = "ZIPLINE\u0000".encodeUtf8()
val CURRENT_ZIPLINE_VERSION = 20211020
private val SECTION_HEADER_JS_BYTECODE = 1
