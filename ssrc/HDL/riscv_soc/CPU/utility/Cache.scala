package utility

import chisel3._
import chisel3.util._
import config.Config

class CacheTable(setBits: Int) extends Bundle {
    def idxBits = 2
    def tagBits = 32 - setBits - idxBits

    val tag = UInt(tagBits.W)
    val set = UInt(setBits.W)
    val idx = UInt(idxBits.W)

    def apply(x: UInt) = x.asTypeOf(this)
    def GetTag(x: UInt): UInt = x >> (setBits + idxBits)
    def GetSet(x: UInt): UInt = x(setBits + idxBits - 1, idxBits)
}

class ReplacementRequest extends Bundle {
    val addr = UInt(32.W)
    val data = UInt(32.W)
}

class AccessRequestIO extends Bundle {
    val addr = Input(UInt(32.W))
    
    val hit = Output(Bool())
    val data = Output(UInt(32.W))
}

class CacheTemplate(
    set: Int, way: Int = 1, 
    name: String, 
    with_valid: Boolean = false,
    with_fence: Boolean = false, // TODO
) extends Module {
    val io = IO(new Bundle{
        val rreq = Flipped(ValidIO(new ReplacementRequest))
        val areq = new AccessRequestIO
    })

    val set_bits = log2Up(set)
    print(s"new cache $name created with set_bits = $set_bits\n")
    val table = new CacheTable(set_bits)

    val meta_Bundle = new Bundle {
        val valid = if (with_valid) Bool() else null
        val tag = UInt(table.tagBits.W)
    }

    val meta = Mem(set, Vec(way, meta_Bundle))
    val data = Mem(set, Vec(way, UInt(32.W)))

    // read
    val read_table = table(io.areq.addr)
    val read_tag = read_table.tag
    val read_set = read_table.set

    val read_tag_euqal_vec = 
        VecInit(meta.read(read_set).map(_.tag === read_tag))
    val read_valid_vec = 
        if (with_valid) VecInit(meta.read(read_set).map(_.valid)) else VecInit(Seq.fill(way)(true.B))
    val read_way = PriorityEncoder(read_tag_euqal_vec)
    io.areq.data := data.read(read_set)(read_way)
    io.areq.hit := VecInit((read_tag_euqal_vec zip read_valid_vec).map { case (tag_eq, valid) => tag_eq && valid }).asUInt.orR

    // write
    val replacement = ReplacementPolicy.fromString("setlru", way, set)

    val replace_table = table(io.rreq.bits.addr)
    val replace_tag = replace_table.tag
    val replace_set = replace_table.set
    val replace_way = replacement.way(replace_set)
    val replace_mask = UIntToOH(replace_way, way)
    val replace_tag_v = VecInit((0 until way).map(_ => Cat(true.B, replace_tag).asTypeOf(meta_Bundle)))
    val replace_data_v = VecInit((0 until way).map(_ => io.rreq.bits.data))

    object cache_access extends DPI {
        def functionName: String = name + "_cache_access"
        override def inputNames: Option[Seq[String]] = Some(Seq(
            "is_replace",
            "set",
            "way",
            "tag",
            "data",
        ))
    }

    when(io.rreq.valid) {
        meta.write(replace_set, replace_tag_v, replace_mask.asBools)
        data.write(replace_set, replace_data_v, replace_mask.asBools)

        if (way > 1) replacement.access(replace_set, replace_way)

        cache_access.wrap_call(true.B, replace_set, replace_way, replace_tag, io.rreq.bits.data)
    }.elsewhen(io.areq.hit) {
        if (way > 1) replacement.access(read_set, read_way)

        cache_access.wrap_call(false.B, read_set, read_way, 0.U.asTypeOf(replace_tag), 0.U.asTypeOf(io.rreq.bits.data))
    }
}
