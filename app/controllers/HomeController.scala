package controllers

import javax.inject._
import play.api._
import play.api.mvc._
import play.api.mvc.Action
import io.circe.Json
import play.api.libs.circe.Circe
import scalaj.http._
import io.circe.jawn
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

  def bet(address:String, guess: Int): String =  {

    val client = RestApiErgoClient.create("http://78.46.251.133:9052/swagger", NetworkType.TESTNET, " ")

    client.execute(ctx => {
      val minFee = 1000000
      val secret = BigInt("187b05ba1eb459d3e347753e2fb9da0e2fb3211e3e1a896a0665666b6ab5a2a8", 16)
      val prover = ctx.newProverBuilder()
        .withDLogSecret(secret.bigInteger)
        .build()
      val pkAddress: Address = Address.create(address) // should be changed
      val listBoxes = ctx.getUnspentBoxesFor(pkAddress)
      var sumBoxes: Long = 0

      val txB = ctx.newTxBuilder()

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

      val winnerContract2 = ctx.compileContract(
        ConstantsBuilder.create()
          .item("deadlineHeight", 50)
          .build(),
        winnerScript)

      val winnerErgoTree = winnerContract2.getErgoTree

      val TicketScript =
        s"""{
           |  val winnerPhaseSpend = HEIGHT > deadlineHeight
           |
           |  val receiverCheckWinner = OUTPUTS(0).R8[Long].get == SELF.R8[Long].get
           |
           |  sigmaProp(receiverCheckWinner && winnerPhaseSpend)
           |}""".stripMargin


      val ticketContract = ctx.compileContract(
        ConstantsBuilder.create()
          .item("deadlineHeight", 50)
          .item("ticketPrice", 1000000L)
          .build(),
        TicketScript)
      val ticketErgoTree = ticketContract.getErgoTree

      val scriptTokenRepo =
        s"""{
           |  val totalSoldTicket = SELF.R4[Long].get
           |  val totalSoldTicketBI: BigInt = totalSoldTicket.toBigInt
           |  val totalRaised = totalSoldTicket * ticketPrice
           |  sigmaProp(
           |    if (HEIGHT < deadlineHeight) {
           |      allOf(Coll(
           |            // validate Script
           |            OUTPUTS(0).propositionBytes == SELF.propositionBytes,
           |            OUTPUTS(1).R6[Coll[Byte]].get == blake2b256(SELF.propositionBytes),
           |            // minERG
           |            INPUTS(1).value >= ticketPrice + 2 * minFee,
           |            // validate Register
           |            OUTPUTS(0).R4[Long].get == totalSoldTicket + (INPUTS(1).value - 2 * minFee) / ticketPrice,
           |            OUTPUTS(1).R4[Long].get == totalSoldTicket,
           |            OUTPUTS(1).R5[Long].get == (INPUTS(1).value - 2 * minFee) / ticketPrice,
           |            // validate Token
           |            OUTPUTS(0).tokens(0)._1 == SELF.tokens(0)._1,
           |            OUTPUTS(0).tokens(0)._2 == SELF.tokens(0)._2 - (INPUTS(1).value - 2 * minFee) / ticketPrice,

           |            //OUTPUTS(0).tokens(1)._1 == SELF.tokens(1)._1, // Raffle Service Token

           |            OUTPUTS(1).tokens(0)._1 == SELF.tokens(0)._1,
           |            OUTPUTS(1).tokens(0)._2 == (INPUTS(1).value - 2 * minFee) / ticketPrice,
           |            // ERG Protect
           |            OUTPUTS(0).value == SELF.value + INPUTS(1).value - 2 * minFee,
           |            OUTPUTS(1).value == minFee,
           |            ))
           |    }
           |    else {
           |        allOf(Coll(
           |              // Validate Size
           |              INPUTS.size == 1 && OUTPUTS.size == 5,
           |              // Winner Box
           |              OUTPUTS(0).value  >= totalRaised,
           |              OUTPUTS(0).R4[Long].get == ((byteArrayToBigInt(CONTEXT.dataInputs(0).id.slice(0, 15)).toBigInt + totalSoldTicketBI) % totalSoldTicketBI).toBigInt,
           |              OUTPUTS(0).tokens(0)._1 == SELF.tokens(0)._1,
           |              OUTPUTS(0).tokens(0)._2 == SELF.tokens(0)._2
           |         ))
           |
           |  })
           |}""".stripMargin


      val raffleContract = ctx.compileContract(
        ConstantsBuilder.create()
          .item("ticketPrice", 1000000L)
          .item("minToRaise", 600000000000L)
          .item("deadlineHeight", 50000000)
          .item("minFee", 1000000L)
          .build(),
        scriptTokenRepo)



      val winnerContract = ctx.compileContract(
        ConstantsBuilder.create()
          .build(), winnerScript)

      //in1
      val gameBox = txB.outBoxBuilder()
        .value(1000000)
        .tokens()
        .registers(ErgoValue.of(0L),ErgoValue.of(50L), ErgoValue.of(10L))
        .contract(raffleContract)
        .build()

      val participantAddress: Address = Address.create("9egoqjL1F5dJg4oUdLZLMZw8C968dfoMoRp4ytH9FXfJBbpk8bu") // should be changed
      //in2
      val participantBox =  txB.outBoxBuilder()
        .value(4000000)
        .registers(ErgoValue.of(55L),ErgoValue.of(33L))
        .contract(new ErgoTreeContract(participantAddress.getErgoAddress.script))
        .build()


      //out1
      val newGameBox = txB.outBoxBuilder()
        .value(3000000)
        .contract(raffleContract)
        .registers(ErgoValue.of(2L), ErgoValue.of(50L), ErgoValue.of(10L))
        .build()

      val raffleErgoTree = raffleContract.getErgoTree
      val scriptTokenRepoHash: Digest32 = scorex.crypto.hash.Blake2b256(raffleErgoTree.bytes)
      val propByte = new ErgoTreeContract(participantAddress.getErgoAddress.script)

      //out2
      val ticket = txB.outBoxBuilder()
        .value(1000000)
        .contract(ticketContract)
        .registers(ErgoValue.of(0L), ErgoValue.of(2L), ErgoValue.of(10L), ErgoValue.of(scriptTokenRepoHash), ErgoValue.of(propByte.getErgoTree.bytes), ErgoValue.of(guess))
        .build()

      val listBoxes2 = ctx.getUnspentBoxesFor(participantAddress)
      val tx = txB.boxesToSpend(listBoxes2)
        .fee(1000000)
        .outputs(newGameBox, ticket)
        .sendChangeTo(participantAddress.getErgoAddress)
        .build()
      return "test"
    })
  }


  def check(address:String, id: Int): UnsignedTransaction =  {

    val client = RestApiErgoClient.create("http://78.46.251.133:9052/swagger", NetworkType.TESTNET, " ")

    client.execute(ctx => {
      val participantAddress: Address = Address.create(address) // should be changed
      val TicketScript =
        s"""{
           |  val winnerPhaseSpend = HEIGHT > deadlineHeight
           |
           |  val receiverCheckWinner = OUTPUTS(0).R8[Long].get == SELF.R8[Long].get
           |
           |  sigmaProp(receiverCheckWinner && winnerPhaseSpend)
           |}""".stripMargin

      val scriptTokenRepo =
        s"""{
           |  val totalSoldTicket = SELF.R4[Long].get
           |  val totalSoldTicketBI: BigInt = totalSoldTicket.toBigInt
           |  val totalRaised = totalSoldTicket * ticketPrice
           |  sigmaProp(
           |    if (HEIGHT < deadlineHeight) {
           |      allOf(Coll(
           |            // validate Script
           |            OUTPUTS(0).propositionBytes == SELF.propositionBytes,
           |            OUTPUTS(1).R6[Coll[Byte]].get == blake2b256(SELF.propositionBytes),
           |            // minERG
           |            INPUTS(1).value >= ticketPrice + 2 * minFee,
           |            // validate Register
           |            OUTPUTS(0).R4[Long].get == totalSoldTicket + (INPUTS(1).value - 2 * minFee) / ticketPrice,
           |            OUTPUTS(1).R4[Long].get == totalSoldTicket,
           |            OUTPUTS(1).R5[Long].get == (INPUTS(1).value - 2 * minFee) / ticketPrice,
           |            // validate Token
           |            OUTPUTS(0).tokens(0)._1 == SELF.tokens(0)._1,
           |            OUTPUTS(0).tokens(0)._2 == SELF.tokens(0)._2 - (INPUTS(1).value - 2 * minFee) / ticketPrice,

           |            //OUTPUTS(0).tokens(1)._1 == SELF.tokens(1)._1, // Raffle Service Token

           |            OUTPUTS(1).tokens(0)._1 == SELF.tokens(0)._1,
           |            OUTPUTS(1).tokens(0)._2 == (INPUTS(1).value - 2 * minFee) / ticketPrice,
           |            // ERG Protect
           |            OUTPUTS(0).value == SELF.value + INPUTS(1).value - 2 * minFee,
           |            OUTPUTS(1).value == minFee,
           |            ))
           |    }
           |    else {
           |        allOf(Coll(
           |              // Validate Size
           |              INPUTS.size == 1 && OUTPUTS.size == 5,
           |              // Winner Box
           |              OUTPUTS(0).value  >= totalRaised,
           |              OUTPUTS(0).R4[Long].get == ((byteArrayToBigInt(CONTEXT.dataInputs(0).id.slice(0, 15)).toBigInt + totalSoldTicketBI) % totalSoldTicketBI).toBigInt,
           |              OUTPUTS(0).tokens(0)._1 == SELF.tokens(0)._1,
           |              OUTPUTS(0).tokens(0)._2 == SELF.tokens(0)._2
           |         ))
           |
           |  })
           |}""".stripMargin


      val raffleContract = ctx.compileContract(
        ConstantsBuilder.create()
          .item("ticketPrice", 1000000L)
          .item("minToRaise", 600000000000L)
          .item("deadlineHeight", 50000000)
          .item("minFee", 1000000L)
          .build(),
        scriptTokenRepo)


      val ticketContract = ctx.compileContract(
        ConstantsBuilder.create()
          .item("deadlineHeight", 50)
          .item("ticketPrice", 1000000L)
          .build(),
        TicketScript)
      val ticketErgoTree = ticketContract.getErgoTree
      val raffleErgoTree = raffleContract.getErgoTree
      val scriptTokenRepoHash: Digest32 = scorex.crypto.hash.Blake2b256(raffleErgoTree.bytes)
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
      val propByte = new ErgoTreeContract(participantAddress.getErgoAddress.script)
      val winnerContract = ctx.compileContract(
        ConstantsBuilder.create()
          .build(),
        winnerScript)

      val sumOfAllBet = 42L
      val txB = ctx.newTxBuilder()
      //winnerbox
      val winnerBox = txB.outBoxBuilder()
        .value(1000000)
        .contract(winnerContract)
        .registers(ErgoValue.of(0L), ErgoValue.of(0L), ErgoValue.of(10L), ErgoValue.of(propByte.getErgoTree.bytes), ErgoValue.of(sumOfAllBet))
        .build()


      //out2
      val ticket = txB.outBoxBuilder()
        .value(1000000)
        .contract(ticketContract)
        .build()

      val listBoxes = ctx.getUnspentBoxesFor(participantAddress)
      //in2
      val check = txB.outBoxBuilder()
        .value(4000000)
        .registers(ErgoValue.of(0L), ErgoValue.of(0L) , ErgoValue.of(0L), ErgoValue.of(propByte.getErgoTree.bytes))
        .contract(new ErgoTreeContract(participantAddress.getErgoAddress.script))
        .build()


      val tx = txB.boxesToSpend(listBoxes)
        .fee(1000000)
        .outputs(check)
        .sendChangeTo(participantAddress.getErgoAddress)
        .build()
      return tx
    })

  }


  def submit(signedTx: SignedTransaction): String =  {
    val client = RestApiErgoClient.create("http://78.46.251.133:9052/swagger", NetworkType.TESTNET, " ")

    client.execute(ctx => {
      val txId = ctx.sendTransaction(signedTx)
      return txId
    })
  }
}
