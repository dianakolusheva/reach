package org.clulab.reach

import org.scalatest.{Matchers, FlatSpec}
import TestUtils._
import org.clulab.reach.grounding._
import org.clulab.reach.grounding.ReachKBKeyTransforms._
import org.clulab.reach.grounding.ReachMiscLookups._

/**
  * Unit tests of additional lookup tables and knowledge bases.
  *   Written by: Tom Hicks. 7/10/2016.
  *   Last Modified: Add is-a tests for protein kinase and protein domains.
  */
class TestMiscLookups extends FlatSpec with Matchers {

  // Tests of the singleton Protein Kinases set:
  val pkl = ProteinKinaseIds

  "Protein Kinases" should "test that contains method works" in {
    (pkl.contains("NOT-IN-KB")) should be (false) // not in KB
    (pkl.contains("not-in-kb")) should be (false) // not in KB
    (pkl.contains("P00000")) should be (false)    // not in KB
    (pkl.contains("Q00000")) should be (false)    // not in KB
    (pkl.contains("Q1")) should be (false)        // not in KB
    (pkl.contains("QPQPQP")) should be (false)    // not in KB
    (pkl.contains("Q99999")) should be (false)    // not a kinase
    (pkl.contains("Q99998")) should be (false)    // not a kinase
    (pkl.contains("O08560")) should be (true)     // first entry in UP list
    (pkl.contains("o08560")) should be (false)    // entries are uppercase
    (pkl.contains("P31749")) should be (true)     // AKT1_HUMAN
    (pkl.contains("p31749")) should be (false)    // entries are uppercase
    (pkl.contains("P31750")) should be (true)     // AKT1_MOUSE
    (pkl.contains("Q13882")) should be (true)     // PTK6_HUMAN
    (pkl.contains("Q8AYK6")) should be (true)     // last entry in UP list
    (pkl.contains("Q2M2I8")) should be (true)     // first in SP list
    (pkl.contains("P43404")) should be (true)     // last in SP list
    (pkl.contains("Q3UHJ0")) should be (true)     // middle of SP list
  }

  "Protein Kinases" should "test that isProteinKinase method works" in {
    (isProteinKinase("NOT-IN-KB")) should be (false) // not in KB
    (isProteinKinase("not-in-kb")) should be (false) // not in KB
    (isProteinKinase("P00000")) should be (false)    // not in KB
    (isProteinKinase("Q00000")) should be (false)    // not in KB
    (isProteinKinase("Q1")) should be (false)        // not in KB
    (isProteinKinase("QPQPQP")) should be (false)    // not in KB
    (isProteinKinase("Q99999")) should be (false)    // not a kinase
    (isProteinKinase("Q99998")) should be (false)    // not a kinase
    (isProteinKinase("O08560")) should be (true)     // first entry in UP list
    (isProteinKinase("o08560")) should be (false)    // entries are uppercase
    (isProteinKinase("P31749")) should be (true)     // AKT1_HUMAN
    (isProteinKinase("p31749")) should be (false)    // entries are uppercase
    (isProteinKinase("P31750")) should be (true)     // AKT1_MOUSE
    (isProteinKinase("Q13882")) should be (true)     // PTK6_HUMAN
    (isProteinKinase("Q8AYK6")) should be (true)     // last entry in UP list
    (isProteinKinase("Q2M2I8")) should be (true)     // first in SP list
    (isProteinKinase("P43404")) should be (true)     // last in SP list
    (isProteinKinase("Q3UHJ0")) should be (true)     // middle of SP list
  }


  // Tests of the singleton Protein Domains Short Names set:
  val pds = ProteinDomainShortNames

  "Protein Domain Short Names" should "test that contains method works" in {
    (pds.contains("NOT-IN-KB")) should be (false) // not in KB
    (pds.contains("not-in-kb")) should be (false) // not in KB
    (pds.contains("P00000")) should be (false)    // not in KB
    (pds.contains("Q00000")) should be (false)    // not in KB
    (pds.contains("Q1")) should be (false)        // not in KB
    (pds.contains("P31749")) should be (false)    // not in KB
    (pds.contains("14_3_3")) should be (true)     // first entry in list
    (pds.contains("AAA")) should be (false)       // entries are lowercase
    (pds.contains("aaa")) should be (true)
    (pds.contains("AICARFT_IMPCHas")) should be (false) // entries are lowercase
    (pds.contains("aicarft_impchas")) should be (true)
    (pds.contains("HAT")) should be (false)       // entries are lowercase
    (pds.contains("hat")) should be (true)
    (pds.contains("ZU5")) should be (false)       // entries are lowercase
    (pds.contains("zu5")) should be (true)        // last entry in list
    (pds.contains("Germane")) should be (false)    // entries are lowercase
    (pds.contains("germane")) should be (true)    // odd but true
  }

  "Protein Domain Short Names" should "test that isProteinDomain method works" in {
    (isProteinDomain("NOT-IN-KB")) should be (false) // not in KB
    (isProteinDomain("not-in-kb")) should be (false) // not in KB
    (isProteinDomain("P00000")) should be (false)    // not in KB
    (isProteinDomain("Q00000")) should be (false)    // not in KB
    (isProteinDomain("Q1")) should be (false)        // not in KB
    (isProteinDomain("P31749")) should be (false)    // not in KB
    (isProteinDomain("14_3_3")) should be (true)     // first entry in list
    (isProteinDomain("AAA")) should be (true)        // case should not matter
    (isProteinDomain("aaa")) should be (true)
    (isProteinDomain("AICARFT_IMPCHas")) should be (true)
    (isProteinDomain("aicarft_impchas")) should be (true)
    (isProteinDomain("HAT")) should be (true)
    (isProteinDomain("hat")) should be (true)
    (isProteinDomain("ZU5")) should be (true)
    (isProteinDomain("zu5")) should be (true)        // last entry in list
    (isProteinDomain("Germane")) should be (true)
    (isProteinDomain("germane")) should be (true)    // odd but true
  }

}

// Save: useful for testing reverse lookup KBs (TBD):
// pds.theKB.foreach { case (k, entries) =>              // for DEBUGGING
//   println(s"${k} => ${entries.toString()}") }         // for DEBUGGING