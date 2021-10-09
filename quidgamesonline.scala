
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
         |            OUTPUTS(0).tokens(1)._1 == SELF.tokens(1)._1, // Raffle Service Token
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
val pinNumber = "1293"
val userParty = blockchainSim.newParty("Abdi")
val winnerContract = ErgoScriptCompiler.compile(
Map(), winnerScript)

val newRound = blockchainSim.newParty("Round 1")

val ticketContract = ErgoScriptCompiler.compile(
Map("deadlineHeight" -> 50, 
   "ticketPrice" -> 1000000L,
   "projectPubKey" -> newRound.wallet.getAddress.pubKey),
TicketScript)

val raffleTokenId: String = "298cbf467b7c5fd38fd3dd8cea35d6c3911f6960db6f6a66548f242a41742870"
val raffleContract = ErgoScriptCompiler.compile(
  Map(
   "ticketPrice" -> 1000000L,
      "minToRaise" -> 600000000000L,
      "deadlineHeight" -> 50000000,
      "minFee" -> 1000000L),
  scriptTokenRepo)

val quidToken = blockchainSim.newToken("QUID")
val quidTokenAmount = 40L

userParty.generateUnspentBoxes(
      toSpend       = 1000000000,
      tokensToSpend = List(quidToken -> quidTokenAmount))

val ticketToken = blockchainSim.newToken("TICKET")

val gameBox = Box(value = 1000000L,
                          script = raffleContract,
                        token = (quidToken -> quidTokenAmount),
                          registers = Map(R4 -> pinNumber.getBytes(), R5 -> pinNumber.getBytes(), R6 -> pinNumber.getBytes()))


val participantBox = Box(value = 4000000L,
                          script = contract(userParty.wallet.getAddress.pubKey))

val generateGameBox = Transaction(
      inputs       = userParty.selectUnspentBoxes(toSpend = 1000000L),
      outputs      = List(gameBox),
      fee          = MinTxFee,
      sendChangeTo = userParty.wallet.getAddress
    )

userParty.printUnspentAssets()
println("-----------")

val generateGameBoxSigned = userParty.wallet.sign(generateGameBox)
blockchainSim.send(generateGameBoxSigned)

val generateParticipantBox = Transaction(
      inputs       = userParty.selectUnspentBoxes(toSpend = 4000000L),
      outputs      = List(participantBox),
      fee          = MinTxFee,
      sendChangeTo = userParty.wallet.getAddress
    )

val generateParticipantBoxSigned = userParty.wallet.sign(generateParticipantBox)
blockchainSim.send(generateParticipantBoxSigned)

val newGameBox = Box(value = 3000000L,
                    script = raffleContract,
                     token = (quidToken -> quidTokenAmount),
                     registers = Map(R4 -> pinNumber.getBytes(), R5 -> pinNumber.getBytes(), R6 -> pinNumber.getBytes())
                    )

val ticket = Box(value = 1000000L,
                script = ticketContract,
                registers = Map(R4 -> pinNumber.getBytes(), R5 -> pinNumber.getBytes(), R6 -> pinNumber.getBytes(), R7 -> pinNumber.getBytes()),
                 token = (quidToken -> 20)
                )



val purchaseTransaction = Transaction(
      inputs       = List(generateGameBoxSigned.outputs(0), generateParticipantBoxSigned.outputs(0)),
      outputs      = List(newGameBox, ticket),
      fee          = MinTxFee,
      sendChangeTo = userParty.wallet.getAddress
    )


val purchaseTransactionSigned = userParty.wallet.sign(purchaseTransaction)
blockchainSim.send(purchaseTransactionSigned)


val pinLockScript = s"""
  sigmaProp(INPUTS(0).R4[Coll[Byte]].get == blake2b256(OUTPUTS(0).R4[Coll[Byte]].get))
""".stripMargin
val pinLockContract = ErgoScriptCompiler.compile(Map(), pinLockScript)

// Build the P2S Address of the contract.
// This is not needed for the code at hand, but is demonstrated here as a reference
// to see how to acquire the P2S address so you can use contracts live on mainnet.
val contractAddress = Pay2SAddress(pinLockContract.ergoTree)
println("Pin Lock Contract Address: " + contractAddress)
println("-----------")




// Define example user input

// Hash the pin number
val hashedPin = Blake2b256(pinNumber.getBytes())
// Define initial nanoErgs in the user's wallet
val userFunds = 100000000
// Generate initial userFunds in the user's wallet
userParty.printUnspentAssets()
println("-----------")



///////////////////////////////////////////////////////////////////////////////////
// Deposit Funds Into Pin Lock Contract //
///////////////////////////////////////////////////////////////////////////////////
// Create an output box with the user's funds locked under the contract
val pinLockBox      = Box(value = userFunds/2,
                          script = pinLockContract,
                          register = (R4 -> hashedPin))
// Create the deposit transaction which locks the users funds under the contract
val depositTransaction = Transaction(
      inputs       = userParty.selectUnspentBoxes(toSpend = userFunds),
      outputs      = List(pinLockBox),
      fee          = MinTxFee,
      sendChangeTo = userParty.wallet.getAddress
    )

// Print depositTransaction
println(depositTransaction)
// Sign the depositTransaction
val depositTransactionSigned = userParty.wallet.sign(depositTransaction)
// Submit the tx to the simulated blockchain
blockchainSim.send(depositTransactionSigned)
userParty.printUnspentAssets()
println("-----------")



///////////////////////////////////////////////////////////////////////////////////
// Withdraw Funds Locked Under Pin Lock Contract //
///////////////////////////////////////////////////////////////////////////////////
// Create an output box which withdraws the funds to the user
// Subtracts `MinTxFee` from value to account for tx fee which
// must be paid.
val withdrawBox      = Box(value = userFunds/2 - MinTxFee,
                          script = contract(userParty.wallet.getAddress.pubKey),
                          register = (R4 -> pinNumber.getBytes()))

// Create the withdrawTransaction
val withdrawTransaction = Transaction(
      inputs       = List(depositTransactionSigned.outputs(0)),
      outputs      = List(withdrawBox),
      fee          = MinTxFee,
      sendChangeTo = userParty.wallet.getAddress
    )
// Print withdrawTransaction
println(withdrawTransaction)
// Sign the withdrawTransaction
val withdrawTransactionSigned = userParty.wallet.sign(withdrawTransaction)
// Submit the withdrawTransaction
blockchainSim.send(withdrawTransactionSigned)

// Print the user's wallet, which shows that the coins have been withdrawn (with same total as initial, minus the MinTxFee * 2)
userParty.printUnspentAssets()
println("-----------")
