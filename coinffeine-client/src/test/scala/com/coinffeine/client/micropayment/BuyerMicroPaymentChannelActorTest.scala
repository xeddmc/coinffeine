package com.coinffeine.client.micropayment

import scala.concurrent.duration._

import akka.actor.Props
import akka.testkit.TestProbe
import org.joda.time.DateTime

import com.coinffeine.client.CoinffeineClientTest
import com.coinffeine.client.exchange.MockProtoMicroPaymentChannel
import com.coinffeine.client.micropayment.MicroPaymentChannelActor.{ExchangeSuccess, StartMicroPaymentChannel}
import com.coinffeine.common.PeerConnection
import com.coinffeine.common.Currency.Euro
import com.coinffeine.common.Currency.Implicits._
import com.coinffeine.common.bitcoin.TransactionSignature
import com.coinffeine.common.exchange.Exchange
import com.coinffeine.common.paymentprocessor.{Payment, PaymentProcessor}
import com.coinffeine.common.protocol.ProtocolConstants
import com.coinffeine.common.protocol.gateway.MessageGateway.{ReceiveMessage, Subscribe}
import com.coinffeine.common.protocol.messages.brokerage.{Market, OrderSet}
import com.coinffeine.common.protocol.messages.exchange.{PaymentProof, StepSignatures}

class BuyerMicroPaymentChannelActorTest extends CoinffeineClientTest("buyerExchange") {
  val listener = TestProbe()
  val paymentProcessor = TestProbe()
  val exchangeInfo = sampleExchangeInfo
  val protocolConstants = ProtocolConstants()
  val mockExchange = new MockProtoMicroPaymentChannel(exchangeInfo)
  override val broker: PeerConnection = exchangeInfo.broker.connection
  override val counterpart: PeerConnection = exchangeInfo.counterpart.connection
  val actor = system.actorOf(Props[BuyerMicroPaymentChannelActor[Euro.type]], "buyer-exchange-actor")
  val dummySig = TransactionSignature.dummy
  listener.watch(actor)

  "The buyer exchange actor" should "subscribe to the relevant messages when initialized" in {
    gateway.expectNoMsg()
    actor ! StartMicroPaymentChannel(
      exchange, exchangeInfo.role, mockExchange, protocolConstants, paymentProcessor.ref, gateway.ref, Set(listener.ref))
    val Subscribe(filter) = gateway.expectMsgClass(classOf[Subscribe])
    val otherId = Exchange.Id("other-id")
    val relevantOfferAccepted = StepSignatures(exchangeId, 5, dummySig, dummySig)
    val irrelevantOfferAccepted = StepSignatures(otherId, 2, dummySig, dummySig)
    val anotherPeer = PeerConnection("some-random-peer")
    filter(fromCounterpart(relevantOfferAccepted)) should be (true)
    filter(ReceiveMessage(relevantOfferAccepted, anotherPeer)) should be (false)
    filter(fromCounterpart(irrelevantOfferAccepted)) should be (false)
    val randomMessage = OrderSet.empty(Market(Euro))
    filter(ReceiveMessage(randomMessage, exchangeInfo.counterpart.connection)) should be (false)
  }

  it should "respond to step signature messages by sending a payment until all steps are done" in {
    for (i <- 1 to exchangeInfo.parameters.breakdown.intermediateSteps) {
      actor ! fromCounterpart(StepSignatures(exchangeInfo.id, i, dummySig, dummySig))
      paymentProcessor.expectMsgClass(classOf[PaymentProcessor.Pay[_]])
      paymentProcessor.reply(PaymentProcessor.Paid(
        Payment(s"payment$i", "sender", "receiver", 1.EUR, DateTime.now(), "description")
      ))
      shouldForward(PaymentProof(exchangeInfo.id, s"payment$i")) to counterpart
      gateway.expectNoMsg(100 milliseconds)
    }
  }

  it should "send a notification to the listeners once the exchange has finished" in {
    actor ! fromCounterpart(
      StepSignatures(exchangeInfo.id, exchangeInfo.parameters.breakdown.totalSteps, dummySig, dummySig))
    listener.expectMsg(ExchangeSuccess)
  }
}