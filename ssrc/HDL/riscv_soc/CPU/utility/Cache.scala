package utility

import chisel3._
import chisel3.util._
import config.Config
import freechips.rocketchip.tilelink.TLMessages.b

class CacheTable(
    width: Int, 
    setBits: Int, blockBits: Int
) extends Bundle {
    def idxBits = log2Up(width / 8)
    def tagBits = width - setBits - idxBits

    val tag = UInt(tagBits.W)
    val set = UInt(setBits.W)
    val block = UInt(blockBits.W)
    val idx = UInt(idxBits.W)

    def apply(x: UInt) = x.asTypeOf(this)
}

class ReplacementRequest(width: Int) extends Bundle {
    val addr = UInt(width.W)
    val data = UInt(width.W)
}

class AccessRequestIO(width: Int) extends Bundle {
    val valid = Input(Bool())
    val addr = Input(UInt(width.W))
    
    val hit = Output(Bool())
    val data = Output(UInt(width.W))
}

class CacheTemplate(
    base_width: Int = 32,
    set: Int, way: Int = 1, block_num: Int = 1, 
    name: String, 
    with_valid: Boolean = false,
    with_fence: Boolean = false, // TODO
) extends Module {
    val io = IO(new Bundle{
        val rreq = Flipped(ValidIO(new ReplacementRequest(base_width)))
        val areq = new AccessRequestIO(base_width)
    })

    val set_bits = log2Up(set)
    val block_bits = if (block_num > 1) log2Up(block_num) else 0
    print(s"new cache $name created with $set Sets, $way Ways, and $block_num Blocks\n")
    val table = new CacheTable(
        base_width, 
        set_bits, block_bits
    )

    val meta_Bundle = new Bundle {
        val valid = if (with_valid) Bool() else null
        val tag = UInt(table.tagBits.W)
    }

    val data_Bundle = new Bundle {
        val data = UInt(32.W)
    }

    val meta = Mem(set, Vec(way, meta_Bundle))
    val data = Mem((set * way), Vec(block_num, data_Bundle))

    // read
    val read_table = table(io.areq.addr)
    val read_tag = read_table.tag
    val read_set = read_table.set
    val read_block = read_table.block

    val meta_read_data = meta.read(read_set)

    val read_tag_euqal_vec = 
        VecInit(meta_read_data.map(_.tag === read_tag))
    val read_valid_vec = 
        if (with_valid) VecInit(meta_read_data.map(_.valid)) else VecInit(Seq.fill(way)(true.B))
    val read_way = PriorityEncoder(read_tag_euqal_vec)

    val read_index = if (way > 1) {
        Cat(read_set, read_way)
    } else {
        read_set
    }
    io.areq.data := data.read(read_index)(read_block).data
    io.areq.hit := VecInit((read_tag_euqal_vec zip read_valid_vec).map { case (tag_eq, valid) => tag_eq && valid }).asUInt.orR

    // write
    val replacement = ReplacementPolicy.fromString("setlru", way, set)

    val replace_table = table(io.rreq.bits.addr)
    val replace_tag = replace_table.tag
    val replace_set = replace_table.set
    val replace_way = replacement.way(replace_set)
    val replace_mask = UIntToOH(replace_way, way)
    val replace_tag_v = VecInit((0 until way).map(_ => Cat(true.B, replace_tag).asTypeOf(meta_Bundle)))
    val replace_data_v = VecInit((0 until block_num).map(_ => io.rreq.bits.data.asTypeOf(data_Bundle)))

    object cache_meta_write extends DPI {
        def functionName: String = name + "_cache_meta_write"
        override def inputNames: Option[Seq[String]] = Some(Seq(
            "set",
            "way",
            "tag",
        ))
    }

    object cache_data_write extends DPI {
        def functionName: String = name + "_cache_data_write"
        override def inputNames: Option[Seq[String]] = Some(Seq(
            "set",
            "way",
            "block",
            "data",
        ))
    }

    val shifter = RotateShifter(block_num, 1)
    
    val write_index = if (way > 1) {
        Cat(replace_set, replace_way)
    } else {
        replace_set
    }

    when(io.rreq.valid) {
        shifter.shift(1.U)
        data.write(write_index, replace_data_v, shifter.getData().asBools)

        cache_data_write.wrap_call(replace_set, replace_way, OHToUInt(shifter.getData()), io.rreq.bits.data)

        when(shifter.last()) {
            meta.write(replace_set, replace_tag_v, replace_mask.asBools)
            if (way > 1) replacement.access(replace_set, replace_way)

            cache_meta_write.wrap_call(replace_set, replace_way, replace_tag)
        }
    }.elsewhen(io.areq.hit && io.areq.valid) {
        if (way > 1) replacement.access(read_set, read_way)
    }
}
