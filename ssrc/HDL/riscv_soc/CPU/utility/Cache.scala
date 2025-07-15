package utility

import chisel3._
import chisel3.util._
import config.Config
import freechips.rocketchip.tilelink.TLMessages.b
import chisel3.util.experimental.loadMemoryFromFileInline
import freechips.rocketchip.diplomacy.AddressSet

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

class AccessRequestIO(width: Int, block_num: Int, ro: Boolean) extends Bundle {
    val ren = Input(Bool())
    val addr = Input(UInt(width.W))
    
    class WriteBack extends Bundle {
        val wen = Input(Bool())
        val wdata = Input(UInt(width.W))
        val wmask = Input(UInt(log2Ceil(width).W))
        val mmio = Output(Bool())
        val is_dirty = Output(Bool())
        val write_back_addr = Output(UInt(width.W))
        val write_back = Output(Vec(block_num, UInt(width.W)))
    }

    val wb = if (ro) None else Some(new WriteBack) 
    
    val hit = Output(Bool())
    val rdata = Output(UInt(width.W))
}

class CacheTemplate(
    base_width: Int = 32,
    set: Int, way: Int = 1, block_num: Int = 1, 
    mmio_range: Option[Seq[AddressSet]] = None,
    name: String, 
    with_valid: Boolean = false,
    with_fence: Boolean = false, // TODO
    is_async: Boolean = true,  // TODO
) extends Module {
    val io = IO(new Bundle{
        val rreq = Flipped(ValidIO(new ReplacementRequest(base_width)))
        val areq = new AccessRequestIO(base_width, block_num, mmio_range.isEmpty)
    })

    val set_bits = log2Up(set)
    val block_bits = if (block_num > 1) log2Up(block_num) else 0
    print(s"new cache $name created with $set Sets, $way Ways, and $block_num Blocks\n")
    val table = new CacheTable(
        base_width, 
        set_bits, block_bits
    )

    val meta_Bundle = new Bundle {
        val tag = UInt(table.tagBits.W)
    }

    val data_Bundle = new Bundle {
        val data = UInt(32.W)
    }

    val valid_vec = if (with_valid) {
        RegInit(VecInit(Seq.fill(set)(VecInit(Seq.fill(way)(false.B)))))
    } else {
        VecInit(Seq.fill(set)(VecInit(Seq.fill(way)(true.B))))
    }

    val dirty_vec = if (mmio_range.isDefined) {
        RegInit(VecInit(Seq.fill(set)(VecInit(Seq.fill(way)(false.B)))))
    } else {
        VecInit(Seq.fill(set)(VecInit(Seq.fill(way)(true.B))))
    }

    val meta = if (is_async) Mem(set, Vec(way, meta_Bundle)) else SyncReadMem(set, Vec(way, meta_Bundle))
    val data = if (is_async) Mem((set * way), Vec(block_num, data_Bundle)) else SyncReadMem((set * way), Vec(block_num, data_Bundle))

    // access
    val access_table = table(io.areq.addr)
    val access_tag = access_table.tag
    val access_set = access_table.set
    val access_block = access_table.block

    val meta_access_data = meta.read(access_set)
    
    val read_tag_euqal_vec = 
        VecInit(meta_access_data.map(_.tag === access_tag))
    val access_way = PriorityEncoder(read_tag_euqal_vec)
    val access_index = if (way > 1) {
        Cat(access_set, access_way)
    } else {
        access_set
    }

    // read
    val read_data = data.read(access_index)
    val read_block_data = read_data(access_block)

    io.areq.rdata := read_block_data.data
    val mmio_hit = mmio_range match {
        case Some(ranges) => ranges.map(_.contains(io.areq.addr)).reduce(_ || _)
        case None => false.B
    }

    val hit = VecInit((read_tag_euqal_vec zip valid_vec(access_set)).map { case (tag_eq, valid) => tag_eq && valid }).asUInt.orR && !mmio_hit
    io.areq.hit := hit

    // replace
    val replacement = ReplacementPolicy.fromString("setplru", way, set)

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

    // write
    if (mmio_range.isDefined) {
        io.areq.wb.get.mmio := mmio_hit
        io.areq.wb.get.write_back := read_data.asTypeOf(Vec(block_num, UInt(base_width.W)))

        io.areq.wb.get.is_dirty := dirty_vec(access_set)(access_way)
        io.areq.wb.get.write_back_addr := Cat(meta_access_data(replace_way).tag, replace_set, 0.U(block_bits.W), "b00"U(2.W))

        object cache_meta_dirt extends DPI {
            def functionName: String = name + "_cache_meta_dirt"
            override def inputNames: Option[Seq[String]] = Some(Seq(
                "set",
                "way",
            ))
        }

        when(io.areq.wb.get.wen && hit) {
            val wmask = io.areq.wb.get.wmask
            val wdata = io.areq.wb.get.wdata
            val merged_data = Wire(Vec(4, UInt(8.W)))
            val old = read_data(access_block).data.asTypeOf(new Bundle {
                val data = Vec(4, UInt(8.W))
            })
            val stu = wdata.asTypeOf(new Bundle {
                val data = Vec(4, UInt(8.W))
            })
            for (i <- 0 until wmask.getWidth - 1) {
                val new_data = Mux(wmask(i), stu.data(i), old.data(i))
                merged_data(i) := new_data
            }

            val din = VecInit(Seq.fill(block_num)(merged_data.asTypeOf(data_Bundle)))
            val write_mask4Cache = VecInit(Seq.fill(block_num)(false.B))
            write_mask4Cache(access_block) := true.B
            data.write(access_index, din, write_mask4Cache)

            cache_meta_dirt.wrap_call(access_set, access_way)
            dirty_vec(access_set)(access_way) := true.B
            cache_data_write.wrap_call(access_set, access_way, OHToUInt(write_mask4Cache), merged_data.asTypeOf(UInt(32.W)))
        }
    }

    when(io.rreq.valid) {
        // 通过 IO 接口控制 shifter
        shifter.io.shift := true.B
        shifter.io.shamt := 1.U
        shifter.io.left := true.B
        shifter.io.setEnable := false.B
        shifter.io.setData := DontCare
        
        data.write(write_index, replace_data_v, shifter.io.data.asBools)

        cache_data_write.wrap_call(replace_set, replace_way, OHToUInt(shifter.io.data), io.rreq.bits.data)

        when(shifter.io.data(shifter.io.data.getWidth - 1)) {  // last bit
            valid_vec(replace_set)(replace_way) := true.B
            meta.write(replace_set, replace_tag_v, replace_mask.asBools)
            if (way > 1) replacement.access(replace_set, replace_way)

            cache_meta_write.wrap_call(replace_set, replace_way, replace_tag)
        }
    }.otherwise {
        // 默认状态：不进行任何操作
        shifter.io.shift := false.B
        shifter.io.shamt := DontCare
        shifter.io.left := DontCare
        shifter.io.setEnable := false.B
        shifter.io.setData := DontCare
        
        when(io.areq.hit && io.areq.ren) {
            if (way > 1) replacement.access(access_set, access_way)
        }
    }
}
