package org.clulab.reach.darpa

import org.clulab.odin._
import org.clulab.reach._
import org.clulab.reach.mentions._
import org.clulab.struct.DirectedGraph
import scala.annotation.tailrec


class DarpaActions extends Actions {

  import DarpaActions._

  /** Converts mentions to biomentions.
    * They are returned as mentions but they are biomentions with grounding, modifications, etc
    */
  def mkBioMention(mentions: Seq[Mention], state: State): Seq[Mention] =
    mentions.map(_.toBioMention)

  /** Unpacks RelationMentions into its arguments. A new BioTextBoundMention
    * will be created for each argument with the labels of the original RelationMention.
    * This is relying on Odin's behavior of assigning the same label of the RelationMention
    * to its arguments captured with a pattern (not mention captures).
    * This is required for RelationMentions whose arguments are used directly
    * by subsequent rules.
    * WARNING This method only handles RelationMentions. Other types of Mentions are deleted.
    */
  def unpackRelations(mentions: Seq[Mention], state: State): Seq[Mention] = mentions flatMap {
    case rel: RelationMention => for {
      (k, v) <- rel.arguments
      m <- v
    } yield m.toBioMention
    case _ => Nil
  }

  val mkEntities: Action = unpackRelations

  /** This action handles the creation of mentions from labels generated by the NER system.
    * Rules that use this action should run in an iteration following and rules recognizing
    * "custom" entities. This action will only create mentions if no other mentions overlap
    * with a NER label sequence.
    */
  def mkNERMentions(mentions: Seq[Mention], state: State): Seq[Mention] = {
    mentions flatMap { m =>
      val candidates = state.mentionsFor(m.sentence, m.tokenInterval)
      // do any candidates overlap the mention?
      val overlap = candidates.exists(_.tokenInterval.overlaps(m.tokenInterval))
      if (overlap) None else Some(m.toBioMention)
    }
  }

  /** This action gets RelationMentions that represents a PTM,
    * and attaches the modification to the target entity in place.
    * This action modifies mentions in-place. This action always returns
    * Nil, it assumes that the arguments are already in the state.
    */
  def storePTM(mentions: Seq[Mention], state: State): Seq[Mention] = {
    mentions foreach {
      case ptm: RelationMention if ptm matches "PTM" =>
        // convert first relation("entity") into BioMention
        val bioMention = ptm.arguments("entity").head.toBioMention
        // retrieve optional first relation("site")
        val site = ptm.arguments.get("site").map(_.head)
        // retrieves first relation("mod")
        // this is the TextBoundMention for the ModificationTrigger
        val evidence = ptm.arguments("mod").head
        // assigns label from mod
        val label = getModificationLabel(evidence.text)
        // if label is not unknown then add PTM modification to entity in-place
        if (label != "UNKNOWN") bioMention.modifications += PTM(label, Some(evidence), site)
      case _ => ()
    }
    // this action never returns anything
    // mutates mentions in-place
    // :(
    Nil
  }

  /** Gets RelationMentions that represent an EventSite,
    * and attaches the site to the corresponding entities in-place.
    * Later, if these entities are matched as participants in an event,
    * these sites will be "promoted" to that event and removed from the entity
    * (see siteSniffer for details)
    * This action always returns Nil and assumes that the arguments are already
    * in the state.
    */
  def storeEventSite(mentions: Seq[Mention], state: State): Seq[Mention] = {
    mentions foreach {
      case es: RelationMention if es matches "EventSite" =>
        // convert each relation("entity") into BioMention
        val bioMentions = es.arguments("entity").map(_.toBioMention)
        // retrieves all the captured sites
        val sites = es.arguments("site")
        // add all sites to each entity
        for {
          b <- bioMentions
          s <- sites
        } b.modifications += EventSite(site = s)
      case _ => ()
    }
    Nil
  }

  /** Gets RelationMentions that represent a Mutant,
    * and attaches the mutation to the corresponding event in-place.
    * This action always returns Nil and assumes that the arguments are already
    * in the state.
    */
  def storeMutants(mentions: Seq[Mention], state: State): Seq[Mention] = {
    mentions foreach {
      case m: RelationMention if m matches "Mutant" =>
        val bioMention = m.arguments("entity").head.toBioMention
        val mutants = m.arguments("mutant")
        mutants foreach { mutant =>
          bioMention.modifications += Mutant(evidence = mutant, foundBy = m.foundBy)
        }
    }
    Nil
  }

  /** Gets a sequence of mentions that are candidates for becoming Ubiquitination
    * events and filters out the ones that have ubiquitin as a theme, since
    * a ubiquitin can't be ubiquitinated. Events that have ubiquitin as a cause
    * are also filtered out.
    */
  def mkUbiquitination(mentions: Seq[Mention], state: State): Seq[Mention] = {
    val filteredMentions = mentions.filterNot { ev =>
      // Only keep mentions that don't have ubiquitin as a theme
      ev.arguments("theme").exists(_.text.toLowerCase == "ubiquitin") ||
        // mention shouldn't have ubiquitin as a cause either, if there is a cause
        ev.arguments.get("cause").exists(_.exists(_.text.toLowerCase == "ubiquitin"))
    }
    // return biomentions
    filteredMentions.map(_.toBioMention)
  }

  /**
    * 1. Checks that the cause and theme of an "auto" event (ex. autophosphorylation) are the same <br>
    * 2. Splits valid event into BioEventMention (sans cause) and a BioRelationMention for a Regulation where
    * the original cause serves as the controller. <br>
    * NOTE: we cannot call splitSimpleEvent, as it requires that the controlled and controller do not overlap
    */
  def handleAutoEvent(mentions: Seq[Mention], state: State): Seq[Mention] = mentions flatMap {
    // only events with a theme and a cause are valid
    case e: EventMention if (e.arguments contains "cause") && (e.arguments contains "theme") =>
      val pairs = for {
        c <- e.arguments("cause")
        t <- e.arguments("theme")
        // remove cause from SimpleEvent
        ev = new BioEventMention(e - "cause" + ("theme" -> Seq(t)))
        // use cause of SimpleEvent to create a Regulation
        reg = new BioRelationMention(
          DarpaActions.REG_LABELS,
          Map("controller" -> Seq(c), "controlled" -> Seq(ev)),
          e.sentence, e.document, e.keep, e.foundBy
        )
        // negations should be propagated to the newly created Positive_regulation
        (negMods, otherMods) = e.toBioMention.modifications.partition(_.isInstanceOf[Negation])
      } yield {
        reg.modifications = negMods
        ev.modifications = otherMods
        Seq(reg, ev)
      }
      pairs.flatten
    case _  => Nil
  }

  def mkRegulation(mentions: Seq[Mention], state: State): Seq[Mention] = for {
    mention <- mentions
    // bioprocesses can't be controllers of regulations
    if bioprocessValid(mention)
    // controller/controlled paths shouldn't overlap.
    // NOTE this needs to be done on mentions coming directly from Odin
    // if !hasSynPathOverlap(mention)
    // switch label if needed based on negations
    regulation = removeDummy(switchLabel(mention.toBioMention))
    // If the Mention has both a controller and controlled, they should be distinct
    if hasDistinctControllerControlled(regulation)
  } yield regulation

  /**
    * Identical to mkRegulation, except mkActivation
    * will only allow activations where no overlapping regulation is present
    */
  def mkActivation(mentions: Seq[Mention], state: State): Seq[Mention] = for {
    // Prefer Activations with Events as the controller
    mention <- preferEventControllers(mentions)
    // bioprocesses can't activate biochemical entities
    if bioprocessValid(mention)
    // controller/controlled paths shouldn't overlap.
    // NOTE this needs to be done on mentions coming directly from Odin
    if !hasSynPathOverlap(mention)
    // switch label if needed based on negations
    activation = removeDummy(switchLabel(mention.toBioMention))
    // retrieve regulations that overlap this mention's controlled (Controller may be nested)
    controlleds = activation.arguments("controlled")
    regs = controlleds.flatMap(c => state.mentionsFor(activation.sentence, c.tokenInterval, "Regulation"))
    // Don't report an Activation if an Regulation intersects with one of the activation's controlleds
    // or if the Activation has no controller
    // or if it's controller and controlled are not distinct
    if regs.isEmpty && hasController(activation) && hasDistinctControllerControlled(activation)
  } yield activation

  /** For bindings that should not be split into pairs */
  def mkNaryBinding(mentions: Seq[Mention], state: State): Seq[Mention] = mentions map {
    case m: EventMention if m matches "Binding" =>
      // get the binding event participants
      // note that they could be called either "theme1" or "theme2"
      val themes = m.arguments.getOrElse("theme1", Nil) ++ m.arguments.getOrElse("theme2", Nil)
      val arguments = Map("theme" -> themes)
      m.copy(arguments = arguments)
  }

  def mkBinding(mentions: Seq[Mention], state: State): Seq[Mention] = mentions flatMap {
    case m: EventMention if m.matches("Binding") =>
      // themes in a subject position
      val theme1s = m.arguments.getOrElse("theme1", Nil).map(_.toBioMention)
      // themes in an object position
      val theme2s = m.arguments.getOrElse("theme2", Nil).map(_.toBioMention)
      (theme1s, theme2s) match {
        case (t1s, Nil) if t1s.length > 1 => mkBindingsFromPairs(t1s.combinations(2).toList, m)
        case (Nil, t2s) if t2s.length > 1 => mkBindingsFromPairs(t2s.combinations(2).toList, m)
        case (gen1, Nil) if gen1.exists(t => t matches "Generic_entity") =>
          Seq(new BioEventMention(m - "theme1" - "theme2" + ("theme" -> gen1)))
        case (Nil, gen2) if gen2.exists(t => t matches "Generic_entity") =>
          Seq(new BioEventMention(m - "theme1" - "theme2" + ("theme" -> gen2)))
        case (t1s, t2s) =>
          val pairs = for {
            t1 <- t1s
            t2 <- t2s
          } yield List(t1, t2)
          mkBindingsFromPairs(pairs, m)
        // bindings with 0 or 1 themes should be deleted
        case _ => Nil
      }
  }

  def mkBindingsFromPairs(pairs: Seq[Seq[BioMention]], original: EventMention): Seq[Mention] = for {
    Seq(theme1, theme2) <- pairs
    if !sameEntityID(theme1, theme2)
  } yield {
    if (theme1.text.toLowerCase == "ubiquitin") {
      val arguments = Map("theme" -> Seq(theme2))
      new BioEventMention(original.copy(labels = taxonomy.hypernymsFor("Ubiquitination"), arguments = arguments))
    } else if (theme2.text.toLowerCase == "ubiquitin") {
      val arguments = Map("theme" -> Seq(theme1))
      new BioEventMention(original.copy(labels = taxonomy.hypernymsFor("Ubiquitination"), arguments = arguments))
    } else {
      val arguments = Map("theme" -> Seq(theme1, theme2))
      new BioEventMention(original.copy(arguments = arguments))
    }
  }

  /**
    * Promote any Sites in the Modifications of a SimpleEvent argument to an event argument "site"
    */
  def siteSniffer(mentions: Seq[Mention], state: State): Seq[Mention] = mentions flatMap {
    case m: BioEventMention if m matches "SimpleEvent" =>
      val additionalSites: Seq[Mention] = m.arguments.values.flatten.toSeq.flatMap { case m: BioMention =>
        // get EventSite Modifications
        val eventSites: Seq[EventSite] = m.modifications.toSeq flatMap {
          case es:EventSite => Some(es)
          case _ => None
        }
        // Remove EventSite modifications
        eventSites.foreach(m.modifications -= _)
        // get sites from EventSites
        eventSites.map(_.site)
      }

      // Gather up our sites
      val allSites = additionalSites ++ m.arguments.getOrElse("site", Nil)

      // Do we have any sites?
      if (allSites.isEmpty) Seq(m)
      else {
        // Create a separate EventMention for each Site
        // FIXME this splitting seems arbitrary
        // why do each SimpleEvent has a single site?
        // if it is the theme's site, then why are we extracting sites for all args?
        for (site <- allSites.distinct) yield {
          new BioEventMention(m + ("site" -> Seq(site)))
        }
      }

    // If it isn't a SimpleEvent, assume there is nothing more to do
    case m => Seq(m)
  }

  def keepIfValidArgs(mentions: Seq[Mention], state: State): Seq[Mention] =
    mentions.filter(validArguments(_, state))

  /**
    * Splits a SimpleEvent with a theme and a cause into a ComplexEvent.
    * Requires that the controlled and controller do not overlap
    */
  def splitSimpleEvents(mentions: Seq[Mention], state: State): Seq[Mention] = mentions flatMap {
    case m: EventMention if (m matches "SimpleEvent") & (m.arguments.keySet contains "cause") =>
      // Do we have a regulation?
      val causes: Seq[Mention] = m.arguments("cause")
      val themes: Seq[Mention] = m.arguments("theme")
      val (negMods, otherMods) = m.toBioMention.modifications.partition(_.isInstanceOf[Negation])
      val nonCauseArgs = m.arguments - "cause"
      val controlledArgs = nonCauseArgs.values.flatten.toSet

      val splitEvs = for {
        theme <- themes
      } yield {
        val evArgs = m.arguments - "cause" - "theme" ++ Map("theme" -> Seq(theme))
        val ev = new BioEventMention(m.copy(arguments = evArgs), direct = true)
        // modifications other than negations belong to the SimpleEvent
        ev.modifications = otherMods
        ev
      }

      val splitRegs = for {
        ev <- splitEvs
        cause <- causes
        // controller shouldn't overlap with controlled arguments
        if !controlledArgs.contains(cause)
      } yield {
        val regArgs = Map("controlled" -> Seq(ev), "controller" -> Seq(cause))
        val reg = new BioRelationMention(m.copy(labels = DarpaActions.REG_LABELS, arguments = regArgs).toRelationMention)
        // negations should be propagated to the newly created Positive_regulation
        reg.modifications = negMods
        reg
      }

      splitEvs ++ splitRegs
    case m => Seq(m.toBioMention)
  }

  /** global action for EventEngine */
  def cleanupEvents(mentions: Seq[Mention], state: State): Seq[Mention] = {
    val r1 = siteSniffer(mentions, state)
    val r2 = keepIfValidArgs(r1, state)
    val r3 = NegationHandler.detectNegations(r2, state)
    val r4 = HypothesisHandler.detectHypotheses(r3, state)
    val r5 = splitSimpleEvents(r4, state)
    r5
  }
}

object DarpaActions {

  def hasNegativePolarity(m: Mention): Boolean = if (m.label.toLowerCase startsWith "negative") true else false
  // These labels are given to the Regulation created when splitting a SimpleEvent with a cause
  val REG_LABELS = taxonomy.hypernymsFor("Positive_regulation")

  // These are used to detect semantic inversions of regulations/activations. See DarpaActions.countSemanticNegatives
  val SEMANTIC_NEGATIVE_PATTERN = "attenu|block|deactiv|decreas|degrad|delet|diminish|disrupt|impair|imped|inhibit|knockdown|knockout|limit|loss|lower|negat|reduc|reliev|repress|restrict|revers|silenc|slow|starv|suppress|supress".r

  val MODIFIER_LABELS = "amod".r

  // patterns for "reverse" modifications
  val deAcetylatPat     = "(?i)de-?acetylat".r
  val deFarnesylatPat   = "(?i)de-?farnesylat".r
  val deGlycosylatPat   = "(?i)de-?glycosylat".r
  val deHydrolyPat      = "(?i)de-?hydroly".r
  val deHydroxylatPat   = "(?i)de-?hydroxylat".r
  val deMethylatPat     = "(?i)de-?methylat".r
  val dePhosphorylatPat = "(?i)de-?phosphorylat".r
  val deRibosylatPat    = "(?i)de-?ribosylat".r
  val deSumoylatPat     = "(?i)de-?sumoylat".r
  val deUbiquitinatPat  = "(?i)de-?ubiquitinat".r


  // HELPER FUNCTIONS


  /** retrieves the appropriate modification label */
  def getModificationLabel(text: String): String = text.toLowerCase match {
    case string if deAcetylatPat.findPrefixOf(string).isDefined => "Deacetylation"
    case string if deFarnesylatPat.findPrefixOf(string).isDefined => "Defarnesylation"
    case string if deGlycosylatPat.findPrefixOf(string).isDefined => "Deglycosylation"
    case string if deHydrolyPat.findPrefixOf(string).isDefined => "Dehydrolysis"
    case string if deHydroxylatPat.findPrefixOf(string).isDefined => "Dehydroxylation"
    case string if deMethylatPat.findPrefixOf(string).isDefined => "Demethylation"
    case string if dePhosphorylatPat.findPrefixOf(string).isDefined => "Dephosphorylation"
    case string if deRibosylatPat.findPrefixOf(string).isDefined => "Deribosylation"
    case string if deSumoylatPat.findPrefixOf(string).isDefined => "Desumoylation"
    case string if deUbiquitinatPat.findPrefixOf(string).isDefined => "Deubiquitination"
    case string if string contains "acetylat" => "Acetylation"
    case string if string contains "farnesylat" => "Farnesylation"
    case string if string contains "glycosylat" => "Glycosylation"
    case string if string contains "hydroly" => "Hydrolysis"
    case string if string contains "hydroxylat" => "Hydroxylation"
    case string if string contains "methylat" => "Methylation"
    case string if string contains "phosphorylat" => "Phosphorylation"
    case string if string contains "ribosylat" => "Ribosylation"
    case string if string contains "sumoylat" => "Sumoylation"
    case string if string contains "ubiquitinat" => "Ubiquitination"
    case _ => "UNKNOWN"
  }

  /** Gets a sequence of mentions and returns only the ones that have
    * Event controllers. If none is found, returns all mentions.
    */
  def preferEventControllers(mentions: Seq[Mention]): Seq[Mention] = {
    // get events that have a SimpleEvent as a controller
    // assuming that events can only have one controller
    val eventsWithSimpleController = mentions.filter { m =>
      m.arguments.contains("controller") && m.arguments("controller").head.matches("Event")
    }
    if (eventsWithSimpleController.nonEmpty) eventsWithSimpleController else mentions
  }

  /** Gets a mention. If it is an EventMention with a polarized label
    * and it is negated an odd number of times, returns a new mention
    * with the label flipped. Or else it returns the mention unmodified */
  def switchLabel(mention: Mention): BioMention = mention.toBioMention match {
    // We can only attempt to flip the polarity of ComplexEvents with a trigger
    case ce: BioEventMention if ce matches "ComplexEvent" =>
      val trigger = ce.trigger
      val arguments = ce.arguments.values.flatten
      // get token indices to exclude in the negation search
      // do not exclude args as they may involve regulations
      val excluded = trigger.tokenInterval.toSet
      // count total number of negatives between trigger and each argument
      val numNegatives = arguments.map(arg => countSemanticNegatives(trigger, arg, excluded)).sum
      // does the label need to be flipped?
      numNegatives % 2 != 0 match {
        // odd number of negatives
        case true =>
          val newLabels = flipLabel(ce.label) +: ce.labels.tail
          // trigger labels should match event labels
          val newTrigger = ce.trigger.copy(labels = newLabels)
          // return new mention with flipped label
          new BioEventMention(ce.copy(labels = newLabels, trigger = newTrigger))
        // return mention unmodified
        case false => ce
      }
    case m => m
  }

  /** Gets a trigger, an argument and a set of tokens to be ignored.
    * Returns the number of semantic negatives found in the shortest possible path
    * between the trigger and the argument.
    */
  def countSemanticNegatives(trigger: Mention, arg: Mention, excluded: Set[Int]): Int = {
    // it is possible for the trigger and the arg to be in different sentences because of coreference
    if (trigger.sentence != arg.sentence) return 0
    val deps = trigger.sentenceObj.dependencies.get
    // find the shortest path between any token in the trigger and any token in the argument
    var shortestPath: Seq[Int] = null
    for (tok1 <- trigger.tokenInterval; tok2 <- arg.tokenInterval) {
      val path = deps.shortestPath(tok1, tok2, ignoreDirection = true)
      if (shortestPath == null || path.length < shortestPath.length) {
        shortestPath = path
      }
    }
    val shortestPathWithMods = addAdjectivalModifiers(shortestPath, deps)
    // get all tokens considered negatives
    val negatives = for {
      tok <- shortestPathWithMods
      if !excluded.contains(tok)
      lemma = trigger.sentenceObj.lemmas.get(tok)
      if SEMANTIC_NEGATIVE_PATTERN.findFirstIn(lemma).isDefined
    } yield tok
    // return number of negatives
    negatives.size
  }

  /**
    * Adds adjectival modifiers to all elements in the given path
    * This is necessary so we can properly inspect the semantic negatives,
    *   which are often not in the path, but modify tokens in it,
    *   "*decreased* PTPN13 expression increases phosphorylation of EphrinB1"
    */
  def addAdjectivalModifiers(tokens: Seq[Int], deps: DirectedGraph[String]): Seq[Int] = for {
    t <- tokens
    token <- t +: getModifiers(t, deps)
  } yield token

  def getModifiers(token: Int, deps: DirectedGraph[String]): Seq[Int] = for {
    (tok, dep) <- deps.getOutgoingEdges(token)
    if MODIFIER_LABELS.findFirstIn(dep).isDefined
  } yield tok

  /** gets a polarized label and returns it flipped */
  def flipLabel(label: String): String =
    if (label startsWith "Positive_")
      "Negative_" + label.substring(9)
    else if (label startsWith "Negative_")
      "Positive_" + label.substring(9)
    else sys.error("ERROR: Must have a polarized label here!")


  /** Test whether the given mention has a controller argument. */
  def hasController(mention: Mention): Boolean = mention.arguments.get("controller").isDefined

  /** Test whether the mention has a bioprocess controller and, if so, if its use is legitimate */
  def bioprocessValid(m: Mention): Boolean = {
    (m.arguments.getOrElse("controller", Nil).map(_.label), m.arguments.getOrElse("controlled", Nil).map(_.label)) match {
      case (irrelevant, _) if !irrelevant.contains("BioProcess") => true
      case (procController, procControlled)
        if procController.contains("BioProcess") & procControlled.contains("BioProcess") => true
      case other => false // e.g. BioProcess controller but BioChemical controlled
    }
  }

  /** Gets a mention and checks that the controller and controlled are different.
    * Returns true if either the controller or the controlled is missing,
    * or if they are both present and are distinct.
    */
  def hasDistinctControllerControlled(m: Mention): Boolean = {
    val controlled = m.arguments.getOrElse("controlled", Nil).flatMap(_.toBioMention.grounding).toSet
    val controller = m.arguments.getOrElse("controller", Nil).flatMap(_.toBioMention.grounding).toSet
    controlled.intersect(controller).isEmpty
  }

  /** checks if a mention has a controller/controlled
    * arguments with syntactic paths from the trigger
    * that overlap
    */
  def hasSynPathOverlap(m: Mention): Boolean = {
    val controlled = m.arguments.getOrElse("controlled", Nil)
    val controller = m.arguments.getOrElse("controller", Nil)
    if (m.paths.isEmpty) false
    else if (controlled.isEmpty || controller.isEmpty) false
    else {
      // we are only concerned with the first controlled and controller
      val p1 = m.getPath("controlled", controlled.head)
      val p2 = m.getPath("controller", controller.head)
      if (p1.nonEmpty && p2.nonEmpty) {
        p1.head == p2.head
      } else false
    }
  }

  /** Returns true if both mentions are grounded to the same entity */
  def sameEntityID(m1: BioMention, m2: BioMention): Boolean = {
    require(m1.isGrounded, "mention must be grounded")
    require(m2.isGrounded, "mention must be grounded")
    m1.grounding == m2.grounding
  }

  def removeDummy(m: BioMention): BioMention = m match {
    // we only need to do this for events
    case em: BioEventMention => new BioEventMention(em - "dummy")
    case _ => m
  }

  def validArguments(mention: Mention, state: State): Boolean = mention.toBioMention match {
    // TextBoundMentions don't have arguments
    case _: BioTextBoundMention => true
    // RelationMentions don't have triggers, so we can't inspect the path
    case _: BioRelationMention => true
    // EventMentions are the only ones we can really check
    case m: BioEventMention =>
      // get simple chemicals in arguments
      val args = m.arguments.values.flatten
      val simpleChemicals = args.filter(_ matches "Simple_chemical")
      // if there are no simple chemicals then we are done
      if (simpleChemicals.isEmpty) true
      else {
        for (chem <- simpleChemicals) {
          if (proteinBetween(m.trigger, chem, state)) {
            return false
          }
        }
        true
      }
  }

  def proteinBetween(trigger: Mention, arg: Mention, state: State): Boolean = {
    // it is possible for the trigger and the arg to be in different sentences
    // because of coreference
    if (trigger.sentence != arg.sentence) false
    else trigger.sentenceObj.dependencies match {
      // if for some reason we don't have dependencies
      // then there is nothing we can do
      case None => false
      case Some(deps) => for {
        tok1 <- trigger.tokenInterval
        tok2 <- arg.tokenInterval
        path = deps.shortestPath(tok1, tok2, ignoreDirection = true)
        node <- path
        // FIXME: Why is this Gene_or_gene_product?  What about Protein, GENE, etc.?
        if state.mentionsFor(trigger.sentence, node, "Gene_or_gene_product").nonEmpty
        if !consecutivePreps(path, deps)
      } return true
        // if we reach this point then we are good
        false
    }
  }

  // hacky solution to the prepositional attachment problem
  // that affects the proteinBetween method
  def consecutivePreps(path: Seq[Int], deps: DirectedGraph[String]): Boolean = {
    val pairs = for (i <- path.indices.tail) yield (path(i-1), path(i))
    val edges = for ((n1, n2) <- pairs) yield {
      deps.getEdges(n1, n2, ignoreDirection = true).map(_._3)
    }
    for {
      i <- edges.indices.tail
      if edges(i-1).exists(_.startsWith("prep"))
      if edges(i).exists(_.startsWith("prep"))
    } return true
    false
  }

  /** Recursively converts an Event to an Entity with the appropriate modifications representing its state.
    * SimpleEvent -> theme + PTM <br>
    * Binding -> Complex (treated as an Entity) <br>
    * ComplexEvent -> recursive call on controlled (the event's "output") <br>
    *
    * @param asOutput value of true to generate a Mention's output (ex. theme or controlled)
    */
  @tailrec
  final def convertEventToEntity(m: Mention, asOutput: Boolean = true, negated: Boolean = false): BioMention = m.toBioMention match {

    //
    // cases appropriate to either output (ex. theme/controlled) or other (ex. controller)
    //

    // no conversion needed
    // FIXME: consider case of "activated RAS".
    // We may want to add a Negation mod to this entity conditionally
    // or add a PTM with the label "UNKNOWN" and negate it (depending on value of negated)
    case entity if entity matches "Entity" => entity

    // These are event triggers used by coref (a SimpleEvent without a theme)
    // FIXME: should these be handled differently?
    case generic if generic matches "Generic_event" => generic

    // convert a binding into a Complex so that it is treated as an entity
    case binding if binding matches "Binding" =>
      new BioRelationMention(
        taxonomy.hypernymsFor("Complex"),
        binding.arguments,
        binding.sentence,
        binding.document,
        binding.keep,
        binding.foundBy
      )

    // convert event to PTM on its theme.
    // negate PTM according to current value of "negated"
    // (this SimpleEvent may have been the controlled to some negative ComplexEvent)
    case se: BioEventMention if se matches "SimpleEvent" =>
      // get the theme of the event (assume only one theme)
      val entity = se.arguments("theme").head.toBioMention
      // get an optional site (assume only one site)
      val siteOption = se.arguments.get("site").map(_.head)
      // create new mention for the entity
      val modifiedEntity = new BioTextBoundMention(entity)
      // attach a modification based on the event trigger
      val label = DarpaActions.getModificationLabel(se.label)
      BioMention.copyAttachments(entity, modifiedEntity)
      modifiedEntity.modifications += PTM(label, evidence = Some(se.trigger), site = siteOption, negated)
      modifiedEntity

    //
    // cases for the generation of output
    //

    // dig into the controlled (event's "output" or the part that is altered in some way)
    case posEvent if asOutput && (posEvent matches "ComplexEvent") && (!DarpaActions.hasNegativePolarity(posEvent)) =>
      // get the controlled of the event (assume only one controlled)
      val controlled = posEvent.arguments("controlled").head.toBioMention
      convertEventToEntity(controlled, asOutput, negated)

    // dig into the controlled (event's "output" or the part that is altered in some way)
    // ComplexEvents with negative polarity "negate" the ptm of the contained entity
    // (see https://github.com/clulab/reach/issues/184)
    case negEvent if asOutput && (negEvent matches "ComplexEvent") && DarpaActions.hasNegativePolarity(negEvent) =>
      // get the controlled of the event (assume only one controlled)
      val controlled = negEvent.arguments("controlled").head.toBioMention
      // negate the underlying PTM
      // if received event has negative polarity (see issue #184)
      convertEventToEntity(controlled, asOutput, negated=true)

    //
    // cases for the uncovering of controllers (i.e., asOutput == false)
    //

    // dig into the controller
    case posEvent if (posEvent matches "ComplexEvent") && (!DarpaActions.hasNegativePolarity(posEvent)) =>
      // get the controller of the event (assume only one controller)
      val controller = posEvent.arguments("controller").head.toBioMention
      convertEventToEntity(controller, asOutput = false, negated)

    // dig into the controller
    case negEvent if (negEvent matches "ComplexEvent") && DarpaActions.hasNegativePolarity(negEvent) =>
      // get the controller of the event (assume only one controller)
      val controller = negEvent.arguments("controller").head.toBioMention
      // negate the underlying PTM
      // if received event has negative polarity (see issue #184)
      convertEventToEntity(controller, asOutput = false, negated=true)
  }
}
