package coinffeine.peer.amounts

import org.scalatest.prop.PropertyChecks

import coinffeine.common.test.UnitTest
import coinffeine.model.bitcoin.BitcoinFeeCalculator
import coinffeine.model.bitcoin.test.FixedBitcoinFee
import coinffeine.model.currency._
import coinffeine.model.exchange.Exchange.Amounts
import coinffeine.model.payment.PaymentProcessor

class DefaultAmountsCalculatorTest extends UnitTest with PropertyChecks {

  "The exchange amounts computation" must "reject non positive bitcoin amounts" in new Fixture {
    an [IllegalArgumentException] shouldBe thrownBy {
      instance.exchangeAmountsFor(0.BTC, 1.EUR)
    }
  }

  it must "reject non positive fiat amounts" in new Fixture {
    an [IllegalArgumentException] shouldBe thrownBy {
      instance.exchangeAmountsFor(1.BTC, 0.EUR)
    }
  }

  it must "respect the passed gross amounts" in new Fixture {
    forAnyAmounts { (bitcoinAmount, fiatAmount, amounts) =>
      amounts.grossBitcoinExchanged shouldBe bitcoinAmount
      amounts.grossFiatExchanged shouldBe fiatAmount
    }
  }

  it must "compute the net bitcoin amount" in new Fixture {
    forAnyAmounts { (bitcoinAmount, _, amounts) =>
      amounts.grossBitcoinExchanged - amounts.netBitcoinExchanged shouldBe (txFee * 3)
    }
  }

  it must "reject non positive net bitcoin amounts" in new Fixture {
    an [IllegalArgumentException] shouldBe thrownBy {
      instance.exchangeAmountsFor(txFee * HappyPathTransactions, 100.EUR)
    }
  }

  it must "compute the net fiat amount" in new Fixture {
    val completeStepWithFee = paymentProcessor.amountPlusFee(stepSize)
    val partialStep = stepSize / 2
    val partialStepWithFee = paymentProcessor.amountPlusFee(partialStep)
    withClue("for a complete step") {
      instance.exchangeAmountsFor(1.BTC, completeStepWithFee).netFiatExchanged shouldBe stepSize
    }
    withClue("for a number of complete steps") {
      instance.exchangeAmountsFor(1.BTC, completeStepWithFee * 2).netFiatExchanged shouldBe (
        stepSize * 2)
    }
    withClue("for an amount not fitting a fixed number of completed steps") {
      val complexAmount = stepSize * 2 + partialStep
      val complexAmountWithFee = completeStepWithFee * 2 + partialStepWithFee
      instance.exchangeAmountsFor(1.BTC, complexAmountWithFee).netFiatExchanged shouldBe
        complexAmount
    }
  }

  it must "reject non-positive net fiat amounts" in new Fixture {
    an [IllegalArgumentException] shouldBe thrownBy {
      instance.exchangeAmountsFor(1.BTC, paymentProcessor.amountPlusFee(0.01.EUR) - 0.01.EUR)
    }
  }

  it must "have all intermediate steps but the last of the optimum size for the payment processor" in
    new Fixture {
      forAnyAmounts { amounts =>
        amounts.intermediateSteps.init.forall(_.fiatAmount == paymentProcessor.bestStepSize(Euro))
      }
    }

  it must "have all intermediate steps splitting the net bitcoin amount and a tx fee" in new Fixture {
    forAnyAmounts { amounts =>
      val amountToSplit = amounts.netBitcoinExchanged + txFee
      amounts.intermediateSteps.foreach { step =>
        withClue(s"at step $step") {
          step.depositSplit.toSeq.sum shouldBe amountToSplit
        }
      }
    }
  }

  it must "have last step splitting the deposited amount" in new Fixture {
    forAnyAmounts { amounts =>
      val splitAmount = amounts.finalStep.depositSplit.toSeq.sum
      val depositAmount = amounts.deposits.map(_.input).toSeq.sum
      splitAmount shouldBe (depositAmount - txFee * HappyPathTransactions)
    }
  }

  it must "have steps summing up to the total fiat amount" in new Fixture {
    forAnyAmounts { (_, fiatAmount, amounts) =>
      val spentAmount = amounts.intermediateSteps.map(s => s.fiatAmount + s.fiatFee).sum
      (fiatAmount - spentAmount).value should be <= paymentProcessor.calculateFee(0.01.EUR).value
    }
  }

  it must "have per-step increasing progress reports" in new Fixture {
    val amounts = instance.exchangeAmountsFor(1.BTC, 90.EUR)
    val consecutiveProgress = pairsOf(amounts.intermediateSteps.map(_.progress))
    consecutiveProgress.foreach { case (prevProgress, nextProgress) =>
      prevProgress.bitcoinsTransferred.buyer.value should be < nextProgress.bitcoinsTransferred.buyer.value
      prevProgress.bitcoinsTransferred.seller.value should be < nextProgress.bitcoinsTransferred.seller.value
    }
    amounts.intermediateSteps.last.progress shouldBe amounts.finalStep.progress
  }

  it must "require the buyer to deposit two steps worth of bitcoins and a fee" in new Fixture {
    forAnyAmounts { amounts =>
      val stepSize = bitcoinStepSize(amounts)
      amounts.deposits.buyer.input should be (stepSize * 2 + txFee)
    }
  }

  it must "require the seller to deposit one steps worth of bitcoins apart from the gross amount" in
    new Fixture {
      forAnyAmounts { (bitcoinAmount, _, amounts) =>
        val stepSize = bitcoinStepSize(amounts)
        amounts.deposits.seller.input should be (bitcoinAmount + stepSize)
      }
    }

  it must "refund deposited amounts but one step worth of bitcoins" in new Fixture {
    val amounts = instance.exchangeAmountsFor(1.BTC, 100.EUR)
    val lostAmount = bitcoinStepSize(amounts)
    withClue(s"should lost $lostAmount:") {
      amounts.deposits.buyer.input - amounts.refunds.buyer shouldBe lostAmount
      amounts.deposits.seller.input - amounts.refunds.seller shouldBe lostAmount
    }
  }

  it must "have all but last steps of the same fiat size" in new Fixture {
    forAnyAmounts { amounts =>
      amounts.intermediateSteps.init.map(_.fiatAmount).toSet should (have size 0 or have size 1)
    }
  }

  it must "have all but last steps of about same bitcoin size" in new Fixture {
    forAnyAmounts { amounts =>
      val exchangedAmounts =
        txFee +: amounts.intermediateSteps.init.map(_.depositSplit.buyer)
      val stepIncrements = pairsOf(exchangedAmounts).map { case (prev, next) => (next - prev).value }
      if (stepIncrements.nonEmpty) {
        stepIncrements.max should equal(stepIncrements.min +- Bitcoin.fromSatoshi(1).value)
      }
    }
  }

  it must "charge bitcoin transaction fees to the seller" in new Fixture {
    forAnyAmounts { (bitcoinAmount, _, amounts) =>
      import amounts._
      finalStep.depositSplit.buyer - bitcoinRequired.buyer shouldBe netBitcoinExchanged
      bitcoinRequired.seller - finalStep.depositSplit.seller shouldBe grossBitcoinExchanged
    }
  }

  val exampleCases = Table(("bitcoinAmount", "fiatAmount"),
    1.BTC -> 500.EUR,
    2.BTC -> 6000.EUR,
    0.3.BTC -> 370.2.EUR,
    100.BTC -> 12.05.EUR,
    1.BTC -> 10.03.EUR
  )

  abstract class Fixture(val processorFee: BigDecimal = 0.02,
                         processorStepSize: BigDecimal = 10,
                         val txFee: Bitcoin.Amount = 0.002.BTC) {
    val paymentProcessor: PaymentProcessor = new FixedFeeProcessor(processorFee, processorStepSize)
    val stepSize = paymentProcessor.bestStepSize(Euro)
    val bitcoinFeeCalculator: BitcoinFeeCalculator = new FixedBitcoinFee(txFee)
    val instance = new DefaultAmountsCalculator(
      new DefaultStepwisePaymentCalculator(paymentProcessor), bitcoinFeeCalculator)

    type Euros = Euro.type

    def forAnyAmounts(test: Amounts[Euros] => Unit): Unit = {
      forAnyAmounts((_, _, amounts) => test(amounts))
    }

    def forAnyAmounts(test: (Bitcoin.Amount, CurrencyAmount[Euros], Amounts[Euros]) => Unit): Unit = {
      forAll (exampleCases) { (bitcoin, fiat) =>
        withClue(s"exchanging $bitcoin for $fiat: ") {
          test(bitcoin, fiat, instance.exchangeAmountsFor(bitcoin, fiat))
        }
      }
    }

    protected def bitcoinStepSize(amounts: Amounts[Euros]): Bitcoin.Amount = {
      val price = amounts.netBitcoinExchanged.value / amounts.netFiatExchanged.value
      val expectedStepSize = CurrencyAmount.closestAmount(stepSize.value * price, Bitcoin)
      val buyerAmounts = txFee +: amounts.intermediateSteps.map(_.depositSplit.buyer)
      val actualStepSize = pairsOf(buyerAmounts).map { case (prevStep, nextStep) =>
        nextStep - prevStep
      }.reduce(_ max _)

      val satoshi = CurrencyAmount.smallestAmount(Bitcoin)
      val oneCentInBitcoin = CurrencyAmount.closestAmount(price * 0.01, Bitcoin)
      val expectedRoundingError = satoshi + oneCentInBitcoin

      actualStepSize.value shouldEqual expectedStepSize.value +- expectedRoundingError.value

      actualStepSize
    }
  }

  class FixedFeeProcessor(fee: BigDecimal, stepSize: BigDecimal) extends PaymentProcessor {

    override def calculateFee[C <: FiatCurrency](amount: CurrencyAmount[C]) =
      CurrencyAmount.exactAmount(fee, amount.currency)

    override def bestStepSize[C <: FiatCurrency](currency: C) =
      CurrencyAmount.exactAmount(stepSize, currency)
  }

  private def pairsOf[A](elems: Iterable[A]): Seq[(A, A)] = elems.iterator.sliding(2, 1)
    .withPartial(x = false)
    .map { case Seq(left, right) => (left, right) }
    .toSeq
}
