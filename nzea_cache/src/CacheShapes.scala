package nzea_cache

import me.jiuyang.zaozi.*
import me.jiuyang.zaozi.default.{*, given}
import me.jiuyang.zaozi.valuetpe.*

/** The line as a storage row holds it: the tag above the line's words. A single-port macro pads its row to whole bytes,
  * and that padding rides above the tag — it is not part of the line, so the cache strips it before the cast.
  */
class CacheLine(tagBits: Int, words: Int, wordBits: Int) extends Bundle:
    val tag = Aligned(UInt(tagBits))
    val data = Aligned(Vec(words, UInt(wordBits)))

/** An address as the cache decomposes it: the tag above the set, the word inside the line below it, the byte at the
  * bottom.
  */
class CacheAddr(addrWidth: Int, indexBits: Int, offsetBits: Int, byteBits: Int) extends Bundle:
    val tag = Aligned(UInt(addrWidth - indexBits - offsetBits))
    val set = Aligned(UInt(indexBits))
    val word = Aligned(UInt(offsetBits - byteBits))
    val byte = Aligned(UInt(byteBits))
