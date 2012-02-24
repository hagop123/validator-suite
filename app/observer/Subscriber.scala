package org.w3.vs.observer

import scala.collection.mutable.Set
import akka.actor.TypedActor
import play.api.libs.iteratee.{Enumerator, PushEnumerator}
import akka.actor.Props

/**
 * A Subscriber that can subscribe to an Observer
 * Then the Observer can broadcast message to the Subcriber
 */
trait Subscriber {
  def enumerator: Enumerator[message.ObservationUpdate]
  def subscribe(): Unit
  def unsubscribe(): Unit
  def broadcast(msg: message.ObservationUpdate): Unit
  def broadcast(msgs: Iterable[message.ObservationUpdate]): Unit
}

// TODO to be moved
class SubscriberImpl(observer: Observer) extends Subscriber {
  
  val enumerator = Enumerator.imperative[message.ObservationUpdate]( onStart = this.subscribe() )
  
  def subscribe(): Unit = observer.subscribe(this)
  
  def unsubscribe(): Unit = observer.unsubscribe(this)
  
  // catch java.nio.channels.ClosedChannelException
  def broadcast(msg: message.ObservationUpdate): Unit = {
    try { 
      enumerator.push(msg)
    } catch {
      case e: java.nio.channels.ClosedChannelException => unsubscribe(); enumerator.close
    }
  }
  
  def broadcast(msgs: Iterable[message.ObservationUpdate]): Unit = msgs.map(enumerator.push)
  
}

/*class DashboardSubscriber(observer: Observer) extends Subscriber {
  
  def subscribe(): Unit = {
    observer.subscribe(this)
    
  }
  
}
class JobSubscriber extends Subscriber {}*/