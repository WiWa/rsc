// Copyright (c) 2017-2019 Twitter, Inc.
// Licensed under the Apache License, Version 2.0 (see LICENSE.md).
package rsc.scalasig

import java.util.Arrays
import scala.collection.mutable
import scala.meta.scalasig.lowlevel._

final class Entries private () {
  private var entries = new Array[Entry](1024)
  private var offset = 0
  private val cache = new java.util.HashMap[Key, Ref]

  def getOrElseUpdate(key: Key)(fn: => Entry): Ref = {
    val e = cache.getOrDefault(key, -1)
    if (e != -1) {
      e
    } else {
      val requestedLen = offset + 1
      if (requestedLen > entries.length) {
        val entries1 = new Array[Entry](requestedLen * 2)
        Array.copy(entries, 0, entries1, 0, offset)
        entries = entries1
      }
      val ref = offset
      cache.put(key, ref)
      offset += 1
      val entry = fn
      entries(ref) = entry
      ref
    }
  }

  def update(fn: => Entry): Ref = {
    val requestedLen = offset + 1
    if (requestedLen > entries.length) {
      val entries1 = new Array[Entry](requestedLen * 2)
      Array.copy(entries, 0, entries1, 0, offset)
      entries = entries1
    }
    val ref = offset
    offset += 1
    val entry = fn
    entries(ref) = entry
    ref
  }

  def toArray: Array[Entry] = {
    Arrays.copyOfRange(entries, 0, offset)
  }
}

object Entries {
  def apply(): Entries = {
    new Entries
  }
}
