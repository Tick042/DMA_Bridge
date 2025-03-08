package testAxi2Chi

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import zhujiang._
import zhujiang.chi._
import zhujiang.axi._
import xijiang._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util.SeqToAugmentedSeq
import freechips.rocketchip.diplomacy._
import axi2chi._
import Utils.GenerateVerilog

class Axi2ChiTop(implicit p: Parameters) extends ZJModule {
    private val dmaParams = zjParams.dmaParams
    private val axiParams = AxiParams(dataBits = dw, addrBits = raw, idBits = dmaParams.idBits)
    val Nodes             = Node(nodeType = NodeType.RI, splitFlit = true)
    val io = IO(new Bundle {

        // AXI4 standard interface
        val axi_aw = Flipped(Decoupled(new AWFlit(axiParams)))
        val axi_w  = Flipped(Decoupled(new WFlit(axiParams)))
        val axi_b  = Decoupled(new BFlit(axiParams))
        val axi_r  = Decoupled(new RFlit(axiParams))
        val axi_ar = Flipped(Decoupled(new ARFlit(axiParams)))

    })
    val axi2chi  = Module(new Axi2Chi(Nodes))
    val chislave = Module(new FakeCHISlave)

    // Connection
    io.axi_aw <> axi2chi.axi.aw
    io.axi_w  <> axi2chi.axi.w
    io.axi_b  <> axi2chi.axi.b

    io.axi_ar <> axi2chi.axi.ar
    io.axi_r  <> axi2chi.axi.r

    chislave.io.txreq <> axi2chi.icn.tx.req.get
    chislave.io.txdat <> axi2chi.icn.tx.data.get
    chislave.io.txrsp <> axi2chi.icn.tx.resp.get

    chislave.io.rxdat <> axi2chi.icn.rx.data.get
    chislave.io.rxrsp <> axi2chi.icn.rx.resp.get
    chislave.io.rxsnp <> DontCare
}

object Axi2ChiTop extends App {
    val config = new Config((_, _, _) => { case ZJParametersKey =>
        ZJParameters()
    })

    GenerateVerilog(args, () => new Axi2ChiTop()(config), name = "Axi2ChiTop", split = true)
}
