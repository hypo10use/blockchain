
import org.ergoplatform.compiler.ErgoScalaCompiler._
import org.ergoplatform.playgroundenv.utils.ErgoScriptCompiler
import org.ergoplatform.playground._
import java.math.BigInteger
import org.ergoplatform.Pay2SAddress
import sigmastate.eval.Extensions._
import scorex.crypto.hash.Digest32
import scorex.crypto.hash.{Blake2b256}
import special.collection.Coll

///////////////////////////////////////////////////////////////////////////////////
// Create Pin Lock Contract //
///////////////////////////////////////////////////////////////////////////////////
// Create a Pin Lock script which requires the user to submit a PIN number
// Pin number initially is hashed before being stored on-chain in R4.
// To withdraw user must submit the Pin number which gets posted
// in R4 of the output box as cleartext and hashed to check against
// the stored hash in the input box R4.

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

val TicketScript =
  s"""{
     |  val winnerPhaseSpend = HEIGHT > deadlineHeight &&
     |                         1 == 1 &&
     |                         INPUTS(0).tokens(0)._1 == SELF.tokens(0)._1
     |
     |  val receiverCheckWinner = OUTPUTS(0).propositionBytes == SELF.R7[Coll[Byte]].get &&
     |                            OUTPUTS(0).value == INPUTS(0).value
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

val blockchainSim = newBlockChainSimulationScenario("Quid Games")
val pinNumber = "3"
val userParty = blockchainSim.newParty("Abdi")
val winnerContract = ErgoScriptCompiler.compile(
Map(), winnerScript)

val newRound = blockchainSim.newParty("Round 1")

val ticketContract = ErgoScriptCompiler.compile(
Map("deadlineHeight" -> 50, 
   "ticketPrice" -> 1000000L,
   "projectPubKey" -> newRound.wallet.getAddress.pubKey),
TicketScript)

val raffleContract = ErgoScriptCompiler.compile(
  Map(
   "ticketPrice" -> 1000000L,
      "minToRaise" -> 600000000000L,
      "deadlineHeight" -> 50000000,
      "minFee" -> 1000000L),
  scriptTokenRepo)

val quidToken = blockchainSim.newToken("QUID")
val quidTokenAmount = 1000L

newRound.generateUnspentBoxes(
      toSpend       = 10000000000L,
      tokensToSpend = List(quidToken -> 1001L))


userParty.generateUnspentBoxes(
      toSpend       = 1000000000,
      tokensToSpend = List(quidToken -> 1001L))


//in1
val gameBox = Box(value = 1000000L,
                          script = raffleContract,
                  token = (quidToken -> quidTokenAmount),
                          registers = Map(R4 -> 0L, R5 -> 50L, R6 -> 10L))

//in2
val participantBox = Box(value = 4000000L,
                          script = contract(userParty.wallet.getAddress.pubKey))

val generateGameBox = Transaction(
      inputs       = newRound.selectUnspentBoxes(toSpend = 1000000L),
      outputs      = List(gameBox),
      fee          = MinTxFee,
      sendChangeTo = newRound.wallet.getAddress
    )

val generateGameBoxSigned = newRound.wallet.sign(generateGameBox)
blockchainSim.send(generateGameBoxSigned)
newRound.generateUnspentBoxes(
      toSpend       = 10000000000L,
      tokensToSpend = List(quidToken -> 1001L))


val generateParticipantBox = Transaction(
      inputs       = userParty.selectUnspentBoxes(toSpend = 4000000L, tokensToSpend = List(quidToken -> quidTokenAmount)),
      outputs      = List(participantBox),
      fee          = MinTxFee,
      sendChangeTo = userParty.wallet.getAddress
    )



val generateParticipantBoxSigned = userParty.wallet.sign(generateParticipantBox)
blockchainSim.send(generateParticipantBoxSigned)

//out1
val newGameBox = Box(value = 3000000L,
                    script = raffleContract,
                     token = (quidToken -> 998L),
                     registers = Map(R4 -> 2L, R5 -> 50L, R6 -> 10L)
                    )
val raffleErgoTree = raffleContract.ergoTree
val scriptTokenRepoHash: Digest32 = scorex.crypto.hash.Blake2b256(raffleErgoTree.bytes)
val propByte = userParty.wallet.getAddress.pubKey

//out2
val ticket = Box(value = 1000000L,
                script = ticketContract,
                token = (quidToken -> (2L)),
                registers = Map(R4 -> 0L, R5 -> 2L, R6 ->scriptTokenRepoHash, R7 -> propByte)
                )


// TRANSACTION
val purchaseTransaction = Transaction(
      inputs       = List(generateGameBoxSigned.outputs(0), generateParticipantBoxSigned.outputs(0)),
      outputs      = List(newGameBox, ticket),
      fee          = MinTxFee,
      sendChangeTo = userParty.wallet.getAddress
    )

// SUBMIT TRANSACTION
val purchaseTransactionSigned = userParty.wallet.sign(purchaseTransaction)
blockchainSim.send(purchaseTransactionSigned)


println("Game Box: ", purchaseTransactionSigned.outputs(0))
println("Ticket: ", purchaseTransactionSigned.outputs(1))
