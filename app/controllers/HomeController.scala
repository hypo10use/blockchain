package controllers

import javax.inject._
import play.api._
import play.api.mvc._
import play.api.mvc.Action
import io.circe.Json
import play.api.libs.circe.Circe
import scalaj.http._
import io.circe.jawn
import play.api.Logger
import scorex.crypto.hash._
import scorex.crypto.hash._
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.Address
import org.ergoplatform.ErgoAddress
import org.ergoplatform.appkit.impl.ErgoTreeContract

import scala.collection.JavaConverters.seqAsJavaListConverter


object client

/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */
@Singleton
class HomeController @Inject()(val controllerComponents: ControllerComponents) extends BaseController
  with Circe{

  /**
   * Create an Action to render an HTML page.
   *
   * The configuration in the `routes` file means that this method
   * will be called when the application receives a `GET` request with
   * a path of `/`.
   */

  def index() = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.index())
  }


  def exception(e: Throwable): Result = {
    BadRequest(s"""{"success": false, "message": "${e.getMessage}"}""").as("application/json")
  }

  def bet(address:String, guess: Int) =  Action {  implicit request: Request[AnyContent] =>
    val organizerAddr = "9fm2q6fv6nyQxPpkd6n111xjt9hGdeMCmTM74W5VfyDZ81EuKmf"
    val client = RestApiErgoClient.create("http://135.181.205.79:9053", NetworkType.MAINNET, "", "")
    val accessLogger: Logger = Logger("access")
    accessLogger.debug(client.toString())
      client.execute(ctx => {
      val minFee = 1000000
      val minToRaise = 5000000
      val deadlineHeight = 685222
      val secret = BigInt("187b05ba1eb459d3e347753e2fb9da0e2fb3211e3e1a896a0665666b6ab5a2a8", 16)
      val pkAddress: Address = Address.create(address) // should be changed

      val winnerScript =
        s"""{
           |  sigmaProp(
           |    allOf(Coll(
           |          // Valid Ticket
           |          INPUTS(1).tokens(0)._1 == SELF.tokens(0)._1,
           |          INPUTS(1).R4[Long].get <= SELF.R4[Long].get,
           |          INPUTS(1).R4[Long].get + INPUTS(1).R5[Long].get > SELF.R4[Long].get
           |    ))
           |  )
           |}""".stripMargin

      val winnerContract = ctx.compileContract(
        ConstantsBuilder.create()
          .item("deadlineHeight", 50)
          .build(),
        winnerScript)

      val winnerErgoTree = winnerContract.getErgoTree
      val winnerScriptHash: Digest32 = scorex.crypto.hash.Blake2b256(winnerErgoTree.bytes)
      val TicketScript =
        s"""{
           |  val refundPhaseSpend = HEIGHT > deadlineHeight &&
           |												 blake2b256(INPUTS(0).propositionBytes) == SELF.R6[Coll[Byte]].get &&
           |												 INPUTS(0).tokens(0)._1 == SELF.tokens(0)._1
           |
           |	val winnerPhaseSpend = HEIGHT > deadlineHeight &&
           |												 blake2b256(INPUTS(0).propositionBytes) == winnerScriptHash &&
           |												 INPUTS(0).tokens(0)._1 == SELF.tokens(0)._1
           |
           |	val receiverCheck = OUTPUTS(1).propositionBytes	== SELF.R7[Coll[Byte]].get &&
           |											OUTPUTS(1).value == SELF.tokens(0)._2 * ticketPrice &&
           |											INPUTS.size == 2
           |
           |  val receiverCheckWinner = OUTPUTS(0).propositionBytes == SELF.R7[Coll[Byte]].get &&
           |											      OUTPUTS(0).value == INPUTS(0).value
           |
           |	sigmaProp((receiverCheck && refundPhaseSpend) || (receiverCheckWinner && winnerPhaseSpend))
           |}""".stripMargin

      val raffleProjectAddress : Address = Address.create(organizerAddr)
      val ticketContract = ctx.compileContract(
        ConstantsBuilder.create()
          .item("deadlineHeight", 50)
          .item("ticketPrice", 1000000L)
          .item("winnerScriptHash", winnerScriptHash)
          .item("projectPubKey", raffleProjectAddress.getPublicKey)
          .build(),
        TicketScript)
      val ticketErgoTree = ticketContract.getErgoTree
      val ticketScriptHash: Digest32 = scorex.crypto.hash.Blake2b256(ticketErgoTree.bytes)

      val scriptTokenRepo =
        s"""{
           |	val totalSoldTicket = SELF.R4[Long].get
           |  val totalSoldTicketBI: BigInt = totalSoldTicket.toBigInt
           |	val totalRaised = totalSoldTicket * ticketPrice
           |	val projectCoef = SELF.R6[Long].get
           |	val winnerCoef = 100L - projectCoef
           |	sigmaProp(
           |		if (HEIGHT < deadlineHeight) {
           |			allOf(Coll(
           |						// validate Script
           |						OUTPUTS(0).propositionBytes == SELF.propositionBytes,
           |						blake2b256(OUTPUTS(1).propositionBytes) == ticketScriptHash,
           |            OUTPUTS(1).R6[Coll[Byte]].get == blake2b256(SELF.propositionBytes),
           |						// minERG
           |						INPUTS(1).value >= ticketPrice + 2 * minFee,
           |						// validate Register
           |						OUTPUTS(0).R4[Long].get == totalSoldTicket + (INPUTS(1).value - 2 * minFee) / ticketPrice,
           |						OUTPUTS(1).R4[Long].get == totalSoldTicket,
           |						OUTPUTS(1).R5[Long].get == (INPUTS(1).value - 2 * minFee) / ticketPrice,
           |						// validate Token
           |						OUTPUTS(0).tokens(0)._1 == SELF.tokens(0)._1,
           |						OUTPUTS(0).tokens(0)._2 == SELF.tokens(0)._2 - (INPUTS(1).value - 2 * minFee) / ticketPrice,
           |						OUTPUTS(0).tokens(1)._1 == SELF.tokens(1)._1, // Raffle Service Token
           |						OUTPUTS(1).tokens(0)._1 == SELF.tokens(0)._1,
           |						OUTPUTS(1).tokens(0)._2 == (INPUTS(1).value - 2 * minFee) / ticketPrice,
           |						// ERG Protect
           |						OUTPUTS(0).value == SELF.value + INPUTS(1).value - 2 * minFee,
           |						OUTPUTS(1).value == minFee,
           |						// same Coef
           |						OUTPUTS(0).R6[Long].get == projectCoef
           |						))
           |		}
           |		else {
           |  		if (totalRaised >= minToRaise) {
           |				allOf(Coll(
           |							// Validate Size
           |							INPUTS.size == 1 && OUTPUTS.size == 5,
           |              // Pay Back Raffle Service Token
           |              OUTPUTS(0).tokens(0)._1 == SELF.tokens(1)._1,
           |              OUTPUTS(0).tokens(0)._2 == 1,
           |              OUTPUTS(0).propositionBytes == servicePubKey.propBytes,
           |							// Project Box
           |							OUTPUTS(1).value >= totalRaised * projectCoef / 100,
           |							OUTPUTS(1).propositionBytes == servicePubKey.propBytes,
           |							// Validate Seed
           |							CONTEXT.dataInputs(0).tokens(0)._1 == oracleNebulaNFT,
           |							// Winner Box
           |							OUTPUTS(2).value  >= totalRaised * winnerCoef / 100,
           |							blake2b256(OUTPUTS(3).propositionBytes) == winnerScriptHash,
           |							OUTPUTS(2).R4[Long].get == ((byteArrayToBigInt(CONTEXT.dataInputs(0).id.slice(0, 15)).toBigInt + totalSoldTicketBI) % totalSoldTicketBI).toBigInt,
           |							OUTPUTS(2).tokens(0)._1 == SELF.tokens(0)._1,
           |              OUTPUTS(2).tokens(0)._2 == SELF.tokens(0)._2
           |         ))
           |			}
           |			else {
           |			if (totalRaised < minToRaise) {
           |				if(totalSoldTicket > 0){
           |					allOf(Coll(
           |								// validate Script
           |								OUTPUTS(0).propositionBytes == SELF.propositionBytes,
           |								// validate Token & ERG
           |								OUTPUTS(0).tokens(0)._1 == SELF.tokens(0)._1,
           |								OUTPUTS(0).value >= SELF.value - (OUTPUTS(0).tokens(0)._2 - SELF.tokens(0)._2) * ticketPrice,
           |								OUTPUTS(0).tokens(0)._2 > SELF.tokens(0)._2,
           |                OUTPUTS(0).R4[Long].get == SELF.R4[Long].get - (OUTPUTS(0).tokens(0)._2 - SELF.tokens(0)._2)
           |					))
           |				}
           |				else
           |				{
           |					allOf(Coll(
           |								// Pay Back Raffle Service Token
           |                OUTPUTS(0).tokens(0)._1 == SELF.tokens(1)._1,
           |                OUTPUTS(0).tokens(0)._2 == 1,
           |                OUTPUTS(0).propositionBytes == servicePubKey.propBytes
           |					))
           |				}
           |			}
           |			else {
           |				false
           |			}
           |		}
           |	})
           |}""".stripMargin

      val serviceAddress: Address = Address.create(organizerAddr)
      val contractTokenRepo = ctx.compileContract(
        ConstantsBuilder.create()
          .item("ticketPrice", 1000000L)
          .item("minToRaise", minToRaise)
          .item("deadlineHeight", deadlineHeight)
          .item("servicePubKey", serviceAddress.getPublicKey)
          .item("winnerScriptHash", winnerScriptHash)
          .item("minFee", 1000000L)
          .item("ticketScriptHash", ticketScriptHash)
          .build(),
        scriptTokenRepo)
      Ok(views.html.index())
    })
    }

}
