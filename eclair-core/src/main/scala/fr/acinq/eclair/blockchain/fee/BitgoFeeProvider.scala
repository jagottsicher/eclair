/*
 * Copyright 2018 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.blockchain.fee

import fr.acinq.bitcoin.{BinaryData, Block}
import gigahorse.support.okhttp.Gigahorse
import org.json4s.DefaultFormats
import org.json4s.JsonAST.{JInt, JValue}
import org.json4s.jackson.JsonMethods

import scala.concurrent.{ExecutionContext, Future}

class BitgoFeeProvider(chainHash: BinaryData)(implicit ec: ExecutionContext) extends FeeProvider {

  import BitgoFeeProvider._

  implicit val formats = DefaultFormats

  val uri = chainHash match {
    case Block.LivenetGenesisBlock.hash => "https://www.bitgo.com/api/v2/btc/tx/fee"
    case _ => "https://test.bitgo.com/api/v2/tbtc/tx/fee"
  }

  override def getFeerates: Future[FeeratesPerKB] =
    for {
      res <- Gigahorse.withHttp(Gigahorse.config) { http =>
        http.run(Gigahorse.url(uri).get, Gigahorse.asString)
      }
      json = JsonMethods.parse(res).extract[JValue]
      feeRanges = parseFeeRanges(json)
    } yield extractFeerates(feeRanges)
}

object BitgoFeeProvider {

  case class BlockTarget(block: Int, fee: Long)

  def parseFeeRanges(json: JValue): Seq[BlockTarget] = {
    val blockTargets = json \ "feeByBlockTarget"
    blockTargets.foldField(Seq.empty[BlockTarget]) {
      // BitGo returns estimates in Satoshi/KB, which is what we want
      case (list, (strBlockTarget, JInt(feePerKB))) => list :+ BlockTarget(strBlockTarget.toInt, feePerKB.longValue())
    }
  }

  def extractFeerate(feeRanges: Seq[BlockTarget], maxBlockDelay: Int): Long = {
    // first we keep only fee ranges with a max block delay below the limit
    val belowLimit = feeRanges.filter(_.block <= maxBlockDelay)
    // out of all the remaining fee ranges, we select the one with the minimum higher bound
    belowLimit.map(_.fee).min
  }

  def extractFeerates(feeRanges: Seq[BlockTarget]): FeeratesPerKB =
    FeeratesPerKB(
      block_1 = extractFeerate(feeRanges, 1),
      blocks_2 = extractFeerate(feeRanges, 2),
      blocks_6 = extractFeerate(feeRanges, 6),
      blocks_12 = extractFeerate(feeRanges, 12),
      blocks_36 = extractFeerate(feeRanges, 36),
      blocks_72 = extractFeerate(feeRanges, 72))

}
