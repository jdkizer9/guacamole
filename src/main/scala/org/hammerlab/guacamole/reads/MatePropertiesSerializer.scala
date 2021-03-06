/**
 * Licensed to Big Data Genomics (BDG) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The BDG licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.hammerlab.guacamole.reads

import com.esotericsoftware.kryo.io.{ Input, Output }
import com.esotericsoftware.kryo.{ Kryo, Serializer }

/**
 * Mix-in for {de,}serializing MateProperties objects in other Serializers.
 */
trait CanSerializeMatePropertiesOption {
  lazy val matePropertiesSerializer = new MatePropertiesSerializer

  def write(kryo: Kryo, output: Output, matePropertiesOpt: Option[MateProperties]) = {
    matePropertiesOpt match {
      case None =>
        output.writeBoolean(false)
      case Some(mateProperties) =>
        output.writeBoolean(true)
        matePropertiesSerializer.write(kryo, output, mateProperties)
    }
  }

  def read(kryo: Kryo, input: Input): Option[MateProperties] = {
    if (input.readBoolean()) {
      Some(matePropertiesSerializer.read(kryo, input, classOf[MateProperties]))
    } else {
      None
    }
  }
}

class MatePropertiesSerializer extends Serializer[MateProperties] {
  def write(kryo: Kryo, output: Output, obj: MateProperties) = {
    output.writeBoolean(obj.isFirstInPair)
    obj.inferredInsertSize match {
      case None =>
        output.writeBoolean(false)
      case Some(insertSize) =>
        output.writeBoolean(true)
        output.writeInt(insertSize)
    }
    if (obj.isMateMapped) {
      output.writeBoolean(true)
      output.writeString(obj.mateReferenceContig.get)
      output.writeLong(obj.mateStart.get)
    } else {
      output.writeBoolean(false)
    }
    output.writeBoolean(obj.isMatePositiveStrand)
  }

  def read(kryo: Kryo, input: Input, klass: Class[MateProperties]): MateProperties = {
    val isFirstInPair = input.readBoolean()
    val hasInferredInsertSize = input.readBoolean()
    val inferredInsertSize = if (hasInferredInsertSize) Some(input.readInt()) else None

    val isMateMapped = input.readBoolean()
    val mateReferenceContig = if (isMateMapped) Some(input.readString()) else None
    val mateStart = if (isMateMapped) Some(input.readLong()) else None

    val isMatePositiveStrand = input.readBoolean()

    MateProperties(
      isFirstInPair,
      inferredInsertSize,
      isMateMapped,
      mateReferenceContig,
      mateStart,
      isMatePositiveStrand)
  }
}
