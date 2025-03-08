package axi2chi

import zhujiang._
import zhujiang.chi._
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import _root_.circt.stage.FirtoolOption
import chisel3.stage.ChiselGeneratorAnnotation
import _root_.circt.stage._

object DDRWState {
    val width        = 3
    val Free         = "b000".U
    val SendDBIDResp = "b001".U
    val WaitData     = "b010".U
    val WriteData    = "b011".U
    val SendComp     = "b100".U
    val WaitCompAck  = "b101".U
}

class DDRWEntry(implicit p: Parameters) extends ZJBundle {
    val state     = UInt(DDRWState.width.W)
    val data      = Vec(zjParams.dmaParams.nrBeats, UInt(64.W))
    val mask      = Vec(zjParams.dmaParams.nrBeats, UInt(64.W))
    val half      = Bool()
    val last      = Bool()
    val addr      = Vec(zjParams.dmaParams.nrBeats, UInt(64.W))
    val txnid     = UInt(12.W)
    val dataValid = Vec(zjParams.dmaParams.nrBeats, Bool())
    val mmioReq   = Bool()
}

object DDRRState {
    val width    = 3
    val Free     = "b000".U
    val SendRec  = "b001".U
    val ReadDDR  = "b010".U
    val GetResp  = "b011".U
    val WaitCAck = "b100".U
}

class DDRREntry(implicit P: Parameters) extends ZJBundle {
    val state = UInt(DDRRState.width.W)
    val addr  = Vec(zjParams.dmaParams.nrBeats, UInt(64.W))
    val half  = Bool()
    val last  = Bool()
    val txnid = UInt(12.W)
    val data  = Vec(zjParams.dmaParams.nrBeats, UInt(dw.W))
    val index = UInt(1.W)

}

class FakeCHISlave(implicit p: Parameters) extends ZJModule {
    // ---------------------------------------------------------------------------------------------------------------------------------//
    // ----------------------------------------------------- IO Bundle -----------------------------------------------------------------//
    // ---------------------------------------------------------------------------------------------------------------------------------//
    val io = IO(new Bundle {

        // CHI interface
        val txreq = Flipped(DecoupledIO(new ReqFlit))
        val txdat = Flipped(DecoupledIO(new DataFlit))
        val txrsp = Flipped(Decoupled(new RespFlit))

        val rxrsp = DecoupledIO(new RespFlit)
        val rxdat = DecoupledIO(new DataFlit)
        val rxsnp = DecoupledIO(new SnoopFlit)

    })
    // ---------------------------------------------------------------------------------------------------------------------------------//
    // -------------------------------------------------- Reg and Wire Define ----------------------------------------------------------//
    // ---------------------------------------------------------------------------------------------------------------------------------//
    io.txrsp <> DontCare
    io.rxsnp <> DontCare

    val mem         = Seq.fill(zjParams.dmaParams.nrBeats) { Module(new MemHelper()) }
    val wrBufRegVec = RegInit(VecInit.fill(zjParams.dmaParams.axiEntrySize)(0.U.asTypeOf(new DDRWEntry)))
    val rdBufRegVec = RegInit(VecInit.fill(zjParams.dmaParams.axiEntrySize)(0.U.asTypeOf(new DDRREntry)))

    val wrBufFreeVec        = wrBufRegVec.map(_.state === DDRWState.Free)
    val wrBufSendDBIDVec    = wrBufRegVec.map(_.state === DDRWState.SendDBIDResp)
    val wrBufWaitDataVec    = wrBufRegVec.map(_.state === DDRWState.WaitData)
    val wrBufWrDataVec      = wrBufRegVec.map(_.state === DDRWState.WriteData)
    val wrBufSendCompVec    = wrBufRegVec.map(_.state === DDRWState.SendComp)
    val wrBufWaitCompAckVec = wrBufRegVec.map(_.state === DDRWState.WaitCompAck)

    val selFreeWrBuf     = PriorityEncoder(wrBufFreeVec)
    val selWrSendDBIDBuf = PriorityEncoder(wrBufSendDBIDVec)
    val selWrDataBuf     = PriorityEncoder(wrBufWrDataVec)
    val selWrWaitDataBuf = PriorityEncoder(wrBufWaitDataVec)
    val selWrSendCompBuf = PriorityEncoder(wrBufSendCompVec)

    val rdBufFreeVec     = rdBufRegVec.map(_.state === DDRRState.Free)
    val rdBufRdMemVec    = rdBufRegVec.map(_.state === DDRRState.ReadDDR)
    val rdBufSendDataVec = rdBufRegVec.map(_.state === DDRRState.GetResp)
    val rdBufSendRecVec  = rdBufRegVec.map(_.state === DDRRState.SendRec)

    val selFreeRdBuf     = PriorityEncoder(rdBufFreeVec)
    val selRdMemBuf      = PriorityEncoder(rdBufRdMemVec)
    val selSendRdDataBuf = PriorityEncoder(rdBufSendDataVec)
    val selSendRecBuf    = PriorityEncoder(rdBufSendRecVec)

    val readReqValid    = WireInit(io.txreq.fire && (io.txreq.bits.Opcode === ReqOpcode.ReadOnce || io.txreq.bits.Opcode === ReqOpcode.ReadNoSnp))
    val readSendReceipt = WireInit(readReqValid & io.txreq.bits.Order === "b11".U)
    val readReceipt     = WireInit(0.U.asTypeOf(new RespFlit))

    val dbidValid = WireInit((io.txreq.bits.Opcode === ReqOpcode.WriteUniqueFull || io.txreq.bits.Opcode === ReqOpcode.WriteUniquePtl) & io.txreq.fire)

    val mask = WireInit(VecInit(Seq.fill(8) { 0.U(8.W) }))

    val nrBeat = zjParams.dmaParams.nrBeats

    // def toDataID(x: UInt): UInt = {
    //     require(nrBeat == 1 | nrBeat == 2 | nrBeat == 4)
    //     if (nrBeat == 1) { "b00".U }
    //     else if (nrBeat == 2) { Mux(x === 0.U, "b00".U, "b10".U) }
    //     else if (nrBeat == 4) { x }
    //     else { 0.U }
    // }

    // def toBeatNum(x: UInt): UInt = {
    //     if (nrBeat == 1) { assert(x === "b00".U); 0.U }
    //     else if (nrBeat == 2) { assert(x === "b00".U | x === "b10".U); Mux(x === "b00".U, 0.U, 1.U) }
    //     else if (nrBeat == 4) { x }
    //     else { 0.U }
    // }

    // ---------------------------------------------------------------------------------------------------------------------------------//
    // ------------------------------------------------------- Logic -------------------------------------------------------------------//
    // ---------------------------------------------------------------------------------------------------------------------------------//
    val readMemValid = rdBufRdMemVec.reduce(_ | _)
    mem.foreach { case m => m.clk := clock }
    mem.zipWithIndex.foreach { case (m, i) =>
        m.ren  := readMemValid
        m.rIdx := rdBufRegVec(selRdMemBuf).addr(i)
    }

    /*
     * Write data to fake mem
     */
    val be = io.txdat.bits.BE(7, 0).asTypeOf(Vec(8, UInt(1.W)))
    mask.zip(be).foreach { case (m, b) =>
        when(b === 1.U) {
            m := 255.U
        }.otherwise {
            m := 0.U
        }
    }

    mem.zipWithIndex.foreach { case (m, i) =>
        m.wIdx  := wrBufRegVec(selWrDataBuf).addr(i)
        m.wen   := wrBufRegVec(selWrDataBuf).dataValid(i)
        m.wdata := wrBufRegVec(selWrDataBuf).data.asTypeOf(Vec(nrBeat, UInt(64.W)))(i)
    }

    /*
     *  FSM Updata
     */
    rdBufRegVec.zipWithIndex.foreach { case (r, i) =>
        switch(r.state) {
            is(DDRRState.Free) {
                val hit = selFreeRdBuf === i.U && readReqValid
                when(hit) {
                    r.state   := Mux(readSendReceipt, DDRRState.SendRec, DDRRState.ReadDDR)
                    r.addr(0) := Mux(io.txreq.bits.Addr(5), io.txreq.bits.Addr - 32.U, io.txreq.bits.Addr)
                    r.addr(1) := Mux(io.txreq.bits.Addr(5), io.txreq.bits.Addr, io.txreq.bits.Addr + 32.U)
                    r.half    := io.txreq.bits.Size <= 5.U
                    r.last    := io.txreq.bits.Addr(5)
                    r.txnid   := io.txreq.bits.TxnID
                    r.index   := Mux(io.txreq.bits.Size === "b101".U, io.txreq.bits.Addr(5), 0.U)
                }
            }
            is(DDRRState.SendRec) {
                val hit = io.rxrsp.fire && io.rxrsp.bits.Opcode === RspOpcode.ReadReceipt && io.rxrsp.bits.TxnID === r.txnid
                r.state := Mux(hit, DDRRState.ReadDDR, r.state)
            }
            is(DDRRState.ReadDDR) {
                val hit = selRdMemBuf === i.U & readMemValid
                r.state := Mux(hit, DDRRState.GetResp, r.state)
                when(hit) {
                    // r.data  := Cat(mem.map(_.rdata)).asTypeOf(Vec(nrBeat, UInt(dw.W)))
                    r.data := mem.map(_.rdata)
                }
            }
            is(DDRRState.GetResp) {
                val hit = r.half && io.rxdat.fire && io.rxdat.bits.TxnID === r.txnid ||
                    !r.half && io.rxdat.fire && io.rxdat.bits.DataID === 2.U && io.rxdat.bits.TxnID === r.txnid
                val indexIncrHit = !r.half && io.rxdat.fire && (io.rxdat.bits.DataID === 0.U).asBool && io.rxdat.bits.TxnID === r.txnid
                r.index := Mux(indexIncrHit, 1.U(1.W), r.index)
                when(hit) {
                    // r.state := DDRRState.WaitCAck
                    r := 0.U.asTypeOf(r)
                }
            }
            is(DDRRState.WaitCAck) {
                val hit = io.txrsp.fire && io.txrsp.bits.TxnID === i.U && io.txrsp.bits.TgtID === 7.U
                when(hit) {
                    r := 0.U.asTypeOf(r)
                }
            }
        }
    }

    wrBufRegVec.zipWithIndex.foreach { case (w, i) =>
        switch(w.state) {
            is(DDRWState.Free) {
                val hit = io.txreq.fire && (io.txreq.bits.Opcode === ReqOpcode.WriteUniqueFull || io.txreq.bits.Opcode === ReqOpcode.WriteUniquePtl || io.txreq.bits.Opcode === ReqOpcode.WriteNoSnpFull || io.txreq.bits.Opcode === ReqOpcode.WriteNoSnpPtl) &&
                    selFreeWrBuf === i.U
                when(hit) {
                    w.state   := DDRWState.SendDBIDResp
                    w.addr(0) := Mux(io.txreq.bits.Addr(5), io.txreq.bits.Addr - 32.U, io.txreq.bits.Addr)
                    w.addr(1) := Mux(io.txreq.bits.Addr(5), io.txreq.bits.Addr, io.txreq.bits.Addr + 32.U)
                    w.half    := io.txreq.bits.Size <= 5.U
                    w.last    := io.txreq.bits.Addr(5)
                    w.txnid   := io.txreq.bits.TxnID
                    w.mmioReq := io.txreq.bits.Addr(raw - 1)
                }
            }
            is(DDRWState.SendDBIDResp) {
                val hit = io.rxrsp.fire && (io.rxrsp.bits.Opcode === RspOpcode.DBIDResp || io.rxrsp.bits.Opcode === RspOpcode.CompDBIDResp) && selWrSendDBIDBuf === i.U
                w.state := Mux(hit, DDRWState.WaitData, w.state)
            }
            is(DDRWState.WaitData) {
                val dataHit = io.txdat.fire && io.txdat.bits.TxnID === i.U && (io.txdat.bits.Opcode === DatOpcode.NCBWrDataCompAck || io.txdat.bits.Opcode === DatOpcode.NonCopyBackWriteData)
                val index   = Mux(io.txdat.bits.DataID === 2.U || w.last, 1.U, 0.U)
                val stateHit = io.txdat.fire && io.txdat.bits.DataID === 2.U && io.txdat.bits.TxnID === i.U ||
                    w.half && io.txdat.bits.TxnID === i.U && io.txdat.fire && io.txdat.bits.DataID === 0.U
                when(dataHit) {
                    w.data(index)      := io.txdat.bits.Data(63, 0)
                    w.mask(index)      := mask.asTypeOf(UInt(64.W))
                    w.dataValid(index) := true.B
                }
                w.state := Mux(stateHit, DDRWState.WriteData, w.state)
            }
            is(DDRWState.WriteData) {
                val hit = selWrDataBuf === i.U
                w.state := Mux(hit && !w.mmioReq, DDRWState.SendComp, w.state)
                when(hit && w.mmioReq) {
                    w := 0.U.asTypeOf(w)
                }
            }
            is(DDRWState.SendComp) {
                val hit = io.rxrsp.fire && io.rxrsp.bits.TxnID === w.txnid && io.rxrsp.bits.Opcode === RspOpcode.Comp
                when(hit) {
                    w.state := DDRWState.WaitCompAck
                }
            }
            is(DDRWState.WaitCompAck) {
                val hit = io.txrsp.fire && io.txrsp.bits.TxnID === i.U && io.txrsp.bits.Opcode === RspOpcode.CompAck && io.txrsp.bits.TgtID === 0.U
                when(hit) {
                    w := 0.U.asTypeOf(w)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------------------------//
    // ---------------------------------------------------- IO Interface ---------------------------------------------------------------//
    // ---------------------------------------------------------------------------------------------------------------------------------//

    val sendDbidRespValid = wrBufSendDBIDVec.reduce(_ | _)
    val sendReceRespValid = rdBufSendRecVec.reduce(_ | _)
    val sendCompValid     = wrBufSendCompVec.reduce(_ | _)

    io.txdat.ready := true.B
    io.txreq.ready := wrBufFreeVec.reduce(_ | _) && rdBufFreeVec.reduce(_ | _)

    io.rxrsp.valid := sendDbidRespValid || sendReceRespValid || sendCompValid
    io.rxrsp.bits  := DontCare
    io.rxrsp.bits.Opcode := Mux(
        sendDbidRespValid && !wrBufRegVec(selWrSendDBIDBuf).mmioReq,
        RspOpcode.DBIDResp,
        Mux(
            sendDbidRespValid && wrBufRegVec(selWrSendDBIDBuf).mmioReq,
            RspOpcode.CompDBIDResp,
            Mux(sendReceRespValid, RspOpcode.ReadReceipt, Mux(sendCompValid, RspOpcode.Comp, 0.U))
        )
    )
    io.rxrsp.bits.DBID := Mux(sendDbidRespValid, selWrSendDBIDBuf, Mux(sendCompValid, selWrSendCompBuf, 0.U))
    io.rxrsp.bits.TxnID := Mux(
        sendDbidRespValid,
        wrBufRegVec(selWrSendDBIDBuf).txnid,
        Mux(sendReceRespValid, rdBufRegVec(selSendRecBuf).txnid, Mux(sendCompValid, wrBufRegVec(selWrSendCompBuf).txnid, 0.U))
    )

    io.rxdat.valid        := rdBufSendDataVec.reduce(_ | _)
    io.rxdat.bits         := DontCare
    io.rxdat.bits.Data    := rdBufRegVec(selSendRdDataBuf).data(rdBufRegVec(selSendRdDataBuf).index)
    io.rxdat.bits.DataID  := Mux(rdBufRegVec(selSendRdDataBuf).half, 0.U, Mux(rdBufRegVec(selSendRdDataBuf).index === 1.U, 2.U, 0.U))
    io.rxdat.bits.DataID  := Mux(rdBufRegVec(selSendRdDataBuf).index === 1.U & !rdBufRegVec(selSendRdDataBuf).half, 2.U, 
                                Mux(rdBufRegVec(selSendRdDataBuf).last & rdBufRegVec(selSendRdDataBuf).half, 2.U, 0.U))
    io.rxdat.bits.TxnID   := rdBufRegVec(selSendRdDataBuf).txnid
    io.rxdat.bits.Opcode  := DatOpcode.CompData
    io.rxdat.bits.HomeNID := 7.U
    io.rxdat.bits.DBID    := selSendRdDataBuf

    io.txrsp.ready := true.B

    // ---------------------------------------------------------------------------------------------------------------------------------//
    // ----------------------------------------------------- Assertion -----------------------------------------------------------------//
    // ---------------------------------------------------------------------------------------------------------------------------------//
}

object FakeCHISlave extends App {
    private val config = new Config((_, _, _) => { case ZJParametersKey =>
        ZJParameters()
    })
    private val gen = () => new FakeCHISlave()(config)
    (new ChiselStage).execute(
        Array("--target", "verilog") ++ args,
        Seq(
            FirtoolOption("-O=debug")
        ) ++ Seq(ChiselGeneratorAnnotation(gen))
    )
}
