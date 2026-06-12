package nzea_sim

import chisel3._
import chisel3.util._

/** Commit tracker — monitors commit messages, detects finisher/timeout, prints PASS/FAIL. Parameters match the original
  * commit_tracker.sv. `io.result` anchors the module to prevent CIRCT dead-code elimination.
  */
class CommitTracker(
    maxCycles: Int = 200000,
    finishDrain: Int = 20000,
    startPC: BigInt = BigInt("80000000", 16),
    postFinisherCommits: Int = 15
) extends Module {

  val io = IO(new Bundle {
    val commitMsgValid = Input(Bool())
    val commitMsgNextPC = Input(UInt(32.W))
    val commitMsgRdIndex = Input(UInt(5.W))
    val commitMsgRdValue = Input(UInt(32.W))
    val finishPassed = Input(Bool())
    val result = Output(UInt(2.W)) // anchor: 0=running, 1=pass, 2=fail
  })

  val pass = RegInit(false.B)
  val fail = RegInit(false.B)
  dontTouch(pass)
  dontTouch(fail)

  io.result := Mux(pass, 1.U, Mux(fail, 2.U, 0.U))

  val cycle = RegInit(0.U(32.W))
  val commitCount = RegInit(0.U(32.W))
  val finishedLatched = RegInit(false.B)
  val finishCycle = RegInit(0.U(32.W))
  val monitorActive = RegInit(false.B)
  val storedPC = RegInit(startPC.U(32.W))
  val postFinCnt = RegInit(0.U(8.W))

  val started = RegInit(false.B)

  when(!started) {
    started := true.B
    monitorActive := true.B
    printf("CPU started (cpu_reset released)\n")
  }

  when(monitorActive) {
    cycle := cycle + 1.U

    when(io.finishPassed && !finishedLatched) {
      printf("Finisher triggered (cycle=%d)\n", cycle)
      finishedLatched := true.B
      finishCycle := cycle + finishDrain.U
      postFinCnt := postFinisherCommits.U
    }

    when(finishedLatched && cycle >= finishCycle) {
      when(commitCount > 0.U) {
        printf("PASS: finisher triggered, %d commits\n", commitCount)
        pass := true.B
      }.otherwise {
        printf("FAIL: finisher triggered but no commits\n")
        fail := true.B
      }
      stop()
    }

    when(cycle > maxCycles.U) {
      printf("FAIL: timeout, %d commits\n", commitCount)
      fail := true.B
      stop()
    }

    val captureCommit = io.commitMsgValid &&
      (!finishedLatched || (finishedLatched && postFinCnt > 0.U))
    when(captureCommit) {
      when(finishedLatched) {
        postFinCnt := postFinCnt - 1.U
      }
      commitCount := commitCount + 1.U
      storedPC := io.commitMsgNextPC
      printf(
        "#%d (c%d): pc=%x next_pc=%x rd=x%d val=%x\n",
        commitCount + 1.U,
        cycle,
        storedPC,
        io.commitMsgNextPC,
        io.commitMsgRdIndex,
        io.commitMsgRdValue
      )
    }
  }

}
