package nzea_tile.platform

import chisel3._
import chisel3.util.Valid
import nzea_core.retire.CommitMsg

trait HasCommitMsg {
  def commit_msg: Valid[CommitMsg]
}
