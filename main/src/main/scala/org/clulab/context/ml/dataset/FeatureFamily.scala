package org.clulab.context.ml.dataset

/**
  * Created by enrique on 30/05/17.
  */
trait FeatureFamily;

sealed case class Positional() extends FeatureFamily;
sealed case class Depedency() extends FeatureFamily;
sealed case class Phi() extends FeatureFamily;
sealed case class Negation() extends FeatureFamily;
sealed case class Tails() extends FeatureFamily;
