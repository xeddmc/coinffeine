package coinffeine.peer.exchange.protocol.impl

import scala.util.Try

import coinffeine.model.bitcoin._
import coinffeine.model.currency.{Bitcoin, FiatCurrency}
import coinffeine.model.exchange._

private[impl] class DepositValidator(amounts: Exchange.Amounts[_ <: FiatCurrency],
                                     requiredSignatures: Both[PublicKey],
                                     network: Network) {

  def validate(transactions: Both[ImmutableTransaction]): Both[Try[Unit]] = Both(
    buyer = requireValidBuyerFunds(transactions.buyer),
    seller = requireValidSellerFunds(transactions.seller)
  )

  def requireValidBuyerFunds(transaction: ImmutableTransaction): Try[Unit] =
    requireValidFunds(BuyerRole, transaction)

  def requireValidSellerFunds(transaction: ImmutableTransaction): Try[Unit] =
    requireValidFunds(SellerRole, transaction)

  private def requireValidFunds(role: Role, transaction: ImmutableTransaction): Try[Unit] = Try {
    val funds = transaction.get.getOutput(0)
    requireValidMultisignature(funds)
    val committedFunds: Bitcoin.Amount = funds.getValue
    val expectedFunds = role.select(amounts.deposits).output
    require(
      committedFunds == expectedFunds,
      s"$committedFunds committed by the $role while $expectedFunds were expected")
  }

  private def requireValidMultisignature(funds: MutableTransactionOutput): Unit = {
    val MultiSigInfo(possibleKeys, requiredKeyCount) = MultiSigInfo.fromScript(funds.getScriptPubKey)
      .getOrElse(throw new IllegalArgumentException(
        "Transaction with funds is invalid because is not sending the funds to a multisig"))
    require(requiredKeyCount == 2, "Funds are sent to a multisig that do not require 2 keys")
    require(possibleKeys.map(_.toAddress(network)) == requiredSignatures.toSeq.map(_.toAddress(network)),
      "Possible keys in multisig script does not match the expected keys")
  }
}
