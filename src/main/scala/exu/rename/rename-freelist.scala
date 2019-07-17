//******************************************************************************
// Copyright (c) 2015 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------
// Author: Christopher Celio
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//------------------------------------------------------------------------------
// Rename FreeList
//------------------------------------------------------------------------------
//------------------------------------------------------------------------------

package boom.exu

import chisel3._
import chisel3.util._
import boom.common._
import boom.util._
import freechips.rocketchip.config.Parameters

class RenameFreeList(
  val plWidth: Int,
  val numPregs: Int,
  val float: Boolean)
  (implicit p: Parameters) extends BoomModule
{
  private val pregSz = log2Ceil(numPregs)

  val io = IO(new BoomBundle()(p) {
    // Physical register requests.
    val reqs          = Input(Vec(plWidth, Bool()))
    val alloc_pregs   = Output(Vec(plWidth, Valid(UInt(pregSz.W))))

    // Pregs returned by the ROB.
    val dealloc_pregs = Input(Vec(plWidth, Valid(UInt(pregSz.W))))

    // Branch info for starting new allocation lists.
    val ren_br_tags   = Input(Vec(plWidth, Valid(UInt(brTagSz.W))))

    // Mispredict info for recovering speculatively allocated registers.
    val brinfo        = Input(new BrResolutionInfo)

    val debug = new Bundle {
      val pipeline_empty = Input(Bool())
      val freelist = Output(Bits(numPregs.W))
      val isprlist = Output(Bits(numPregs.W))
    }
  })

  // The free list register array and its branch allocation lists.
  val free_list = RegInit(UInt(numPregs.W), ~(1.U(numPregs.W)))
  val br_alloc_lists = Reg(Vec(maxBrCount, UInt(numPregs.W)))

  // Select pregs from the free list.
  val preg_sels = SelectFirstN(free_list, plWidth)

  // Allocations seen by branches in each pipeline slot.
  val alloc_masks = (preg_sels zip io.reqs).scanRight(0.U(numPregs.W))
                      {case ((preg, req), mask) => Mux(req, mask | preg, mask)}

  // Pregs returned by the ROB via commit or rollback.
  val dealloc_mask = io.dealloc_pregs.map(d =>
                       UIntToOH(d.bits)(numPregs-1,0) & Fill(numPregs, d.valid.asUInt)).reduce(_|_)

  val br_slots = VecInit(io.ren_br_tags.map(tag => tag.valid)).asUInt
  // Create branch allocation lists.
  for (i <- 0 until maxBrCount) {
    val list_req = VecInit(io.ren_br_tags.map(tag => UIntToOH(tag.bits)(i))).asUInt & br_slots
    val new_list = list_req.orR
    br_alloc_lists(i) := Mux(new_list, Mux1H(list_req, alloc_masks.slice(1, plWidth+1)),
                                       br_alloc_lists(i) | alloc_masks(0))
  }

  when (io.brinfo.mispredict) {
    // Recover pregs allocated past a mispredicted branch.
    free_list := (free_list | br_alloc_lists(io.brinfo.tag) | dealloc_mask) & ~(1.U(numPregs.W))
  } .otherwise {
    // Update the free list.
    free_list := (free_list & ~alloc_masks(0) | dealloc_mask) & ~(1.U(numPregs.W))
  }

  // Encode outputs.
  io.alloc_pregs zip preg_sels map {case (p,s) => p.bits  := OHToUInt(s)}
  io.alloc_pregs zip preg_sels map {case (p,s) => p.valid := s.orR}

  io.debug.freelist := free_list
  io.debug.isprlist := 0.U  // TODO track commit free list.

  assert (!(free_list & dealloc_mask).orR, "[freelist] Returning a free physical register.")

  val numLregs = if(float) 32 else 31
  assert (!io.debug.pipeline_empty || PopCount(free_list) >= (numPregs - numLregs - 1).U,
    "[freelist] Leaking physical registers.")
}
