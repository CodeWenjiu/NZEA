package nzea_config

/** Configuration for a set-associative I-Cache ([[nzea_cache.SetAssoc]]).
  *
  * Use `Some(CacheConfig(...))` to enable the cache between IFU and the instruction bus fabric. Use `None` to bypass
  * the cache entirely.
  */
case class CacheConfig(
    nSets: Int = 16,
    nWays: Int = 8,
    lineBits: Int = 32
)
