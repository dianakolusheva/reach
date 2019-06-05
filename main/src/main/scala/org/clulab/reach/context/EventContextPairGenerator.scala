package org.clulab.reach.context

import org.clulab.reach.mentions.{BioEventMention, BioMention, BioTextBoundMention}

class EventContextPairGenerator(mentions:Seq[BioMention], sentenceWindow:Option[Int] = None) {

  type Pair = (BioEventMention, BioTextBoundMention)
  type EventID = String
  type ContextID = (String, String)
  // Collect the event mentions
  val evtMentions = mentions collect  {
    case evt:BioEventMention => evt
  }

  val ctxMentions = mentions collect {
    case ctx: BioTextBoundMention => ctx
  }

  val pairs:Seq[Pair] = for(evt <- evtMentions; ctx <- ctxMentions) yield (evt, ctx)
  def yieldContextEventPairs():Seq[Pair] = {
    /*val filteredPairs = sentenceWindow match {
      case Some(bound) =>
        pairs.filter {
          case (evt, ctx) =>
            Math.abs(evt.sentence - ctx.sentence) <= bound
        }
      case None =>
        pairs
    }
    filteredPairs*/
    pairs

  }


}
