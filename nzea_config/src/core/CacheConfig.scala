package nzea_config.core

/** Configuration for a set-associative I-Cache ([[nzea_cache.SetAssoc]]).
  *
  * Use `Some(CacheConfig(...))` to enable the cache between IFU and the instruction bus fabric. Use `None` to bypass
  * the cache entirely. No defaults here (rule 6): construction sites pass explicit geometry.
  */
case class CacheConfig(
    nSets: Int,
    nWays: Int,
    lineBits: Int
)
