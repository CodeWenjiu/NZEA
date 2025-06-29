package utility

import chisel3._
import chisel3.util._
import config.Config

class CacheTableAddr(setBits: Int) extends Bundle {
    def idxBits = 2
    def tagBits = 32 - setBits - idxBits

    val tag = UInt(tagBits.W)
    val set = UInt(setBits.W)
    val idx = UInt(idxBits.W)

    def GetTag(x: UInt): UInt = {
        x >> (setBits + idxBits)
    }
    def GetSet(x: UInt): UInt = {
        x(setBits + idxBits - 1, idxBits)
    }
}

class ReplacementRequest extends Bundle {
    val addr = UInt(32.W)
    val data = UInt(32.W)
}

class CacheTemplate(set: Int, way: Int = 1, name: String) extends Module {
    val io = IO(new Bundle{
        val addr = Input(UInt(32.W))
        val rreq = Flipped(ValidIO(Input(new ReplacementRequest)))
        val data = ValidIO(Output(UInt(32.W)))
    })

    val set_bits = log2Up(set)
    print(s"new cache $name created with set_bits = $set_bits\n")
    val table = new CacheTableAddr(set_bits)

    val meta = Mem(set, Vec(way, UInt(table.tagBits.W)))
    val data = Mem(set, Vec(way, UInt(32.W)))

    // read
    val read_tag = table.GetTag(io.addr)
    val read_set = table.GetSet(io.addr)

    val read_tag_euqal_vec = 
        VecInit(meta.read(read_set).map(_ === read_tag))
    val read_way = PriorityEncoder(read_tag_euqal_vec)
    io.data.bits := data.read(read_set)(read_way)
    io.data.valid := read_tag_euqal_vec.asUInt.orR

    // write
    val replacement = ReplacementPolicy.fromString("setlru", way, set)

    val replace_tag = table.GetTag(io.rreq.bits.addr)
    val replace_set = table.GetSet(io.rreq.bits.addr)
    val replace_way = replacement.way(replace_set)
    val replace_mask = UIntToOH(replace_way, way)
    val replace_tag_v = VecInit((0 until way).map(_ => replace_tag))
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

        if(Config.Simulate) cache_access.wrap_call(true.B, replace_set, replace_way, replace_tag, io.rreq.bits.data)
    }.elsewhen(io.data.valid) {
        if (way > 1) replacement.access(read_set, read_way)

        if(Config.Simulate) cache_access.wrap_call(false.B, read_set, read_way, 0.U.asTypeOf(replace_tag), 0.U.asTypeOf(io.rreq.bits.data))
    }
}
